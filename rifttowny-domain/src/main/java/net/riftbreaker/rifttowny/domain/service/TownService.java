package net.riftbreaker.rifttowny.domain.service;

import net.riftbreaker.rifttowny.domain.event.DomainEvent;
import net.riftbreaker.rifttowny.domain.flag.FlagOverrides;
import net.riftbreaker.rifttowny.domain.flag.FlagTarget;
import net.riftbreaker.rifttowny.domain.naming.NameCheck;
import net.riftbreaker.rifttowny.domain.naming.NamePolicy;
import net.riftbreaker.rifttowny.domain.naming.OrganisationName;
import net.riftbreaker.rifttowny.domain.org.ChangeDenial;
import net.riftbreaker.rifttowny.domain.org.Invitation;
import net.riftbreaker.rifttowny.domain.org.OrganisationScope;
import net.riftbreaker.rifttowny.domain.org.Outcome;
import net.riftbreaker.rifttowny.domain.org.Resident;
import net.riftbreaker.rifttowny.domain.org.ResidentId;
import net.riftbreaker.rifttowny.domain.org.Town;
import net.riftbreaker.rifttowny.domain.org.TownId;
import net.riftbreaker.rifttowny.domain.role.Permission;
import net.riftbreaker.rifttowny.domain.role.RoleBook;
// RoleBook is used both for the authority lookup and for stripping a departed resident's roles.
import net.riftbreaker.rifttowny.domain.role.SystemRole;
import net.riftbreaker.rifttowny.domain.store.ChangeRefusedException;
import net.riftbreaker.rifttowny.domain.store.CivicStore;
import net.riftbreaker.rifttowny.domain.store.CivicTransaction;

import java.time.Clock;
import net.riftbreaker.rifttowny.api.ChunkKey;
import net.riftbreaker.rifttowny.domain.territory.ClaimKind;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Town lifecycle.
 *
 * <p>Every operation runs inside one transaction, and the events it produces are queued in that
 * same transaction. Nothing here half-happens: a founding that fails leaves no resident pointing at
 * a town that does not exist, and no announcement describing one.</p>
 *
 * <p><strong>Authority is checked here, not in the command layer.</strong> The public API reaches
 * these same methods, so a check that lived only in a command would be a check anybody could walk
 * around. Every method that needs a permission takes the actor and resolves it against the town's
 * role book before touching anything.</p>
 *
 * <p>Uniqueness is checked <em>inside</em> the transaction rather than before it. Checking first
 * would leave a window in which two founders both saw the name free; the unique constraint on
 * {@code name_normalised} is the real guard, and the check here is how it becomes a message instead
 * of a stack trace.</p>
 */
public final class TownService {

    private final CivicStore store;
    private final NamePolicy namePolicy;
    private final Clock clock;
    private final net.riftbreaker.rifttowny.domain.territory.TerritoryIndex index;
    private final CivicCacheRefresher civic;
    private final FlagOverrides overrides;
    private final net.riftbreaker.rifttowny.domain.territory.RuinIndex ruins;
    private final Duration ruinReclaimDelay;
    private final Duration ruinLifetime;
    private final net.riftbreaker.rifttowny.domain.bank.CivicPrices prices;
    private final net.riftbreaker.rifttowny.domain.bank.PlayerWallet wallet;
    private final net.riftbreaker.rifttowny.domain.justice.Outlaws outlaws;

    /**
     * @param index needed only so disbanding can drop the town's chunks from the in-memory cache.
     *        A disbanded town whose claims lingered there would keep protecting land the database
     *        says is wilderness, and no command would be able to clear it
     */
    public TownService(
            final CivicStore store,
            final NamePolicy namePolicy,
            final Clock clock,
            final net.riftbreaker.rifttowny.domain.territory.TerritoryIndex index
    ) {
        this(store, namePolicy, clock, index, CivicCacheRefresher.none(), FlagOverrides.empty());
    }

    /**
     * @param civic told after every successful change. Membership, trust and leadership all decide
     *        protection, and a change that did not reach the cache would leave a kicked player still
     *        building and a new resident still locked out
     */
    public TownService(
            final CivicStore store,
            final NamePolicy namePolicy,
            final Clock clock,
            final net.riftbreaker.rifttowny.domain.territory.TerritoryIndex index,
            final CivicCacheRefresher civic
    ) {
        this(store, namePolicy, clock, index, civic, FlagOverrides.empty());
    }

    /**
     * @param overrides needed only so disbanding can drop the town's flag overrides from memory.
     *        They are removed from storage inside the transaction; this is the in-memory half
     */
    public TownService(
            final CivicStore store,
            final NamePolicy namePolicy,
            final Clock clock,
            final net.riftbreaker.rifttowny.domain.territory.TerritoryIndex index,
            final CivicCacheRefresher civic,
            final FlagOverrides overrides
    ) {
        this(store, namePolicy, clock, index, civic, overrides,
                net.riftbreaker.rifttowny.domain.territory.RuinIndex.empty(),
                Duration.ZERO, Duration.ZERO);
    }

    /**
     * @param ruins the in-memory ruin index, given the chunks a disbanded town gives up
     * @param ruinReclaimDelay how long the ruin lies open before anybody may rebuild it
     * @param ruinLifetime how long those chunks stay a ruin before reverting. {@link Duration#ZERO}
     *        switches ruins off, and a disbanded town's land goes straight back to wilderness
     */
    public TownService(
            final CivicStore store,
            final NamePolicy namePolicy,
            final Clock clock,
            final net.riftbreaker.rifttowny.domain.territory.TerritoryIndex index,
            final CivicCacheRefresher civic,
            final FlagOverrides overrides,
            final net.riftbreaker.rifttowny.domain.territory.RuinIndex ruins,
            final Duration ruinReclaimDelay,
            final Duration ruinLifetime
    ) {
        this(store, namePolicy, clock, index, civic, overrides, ruins, ruinReclaimDelay,
                ruinLifetime,
                net.riftbreaker.rifttowny.domain.bank.CivicPrices.free(),
                net.riftbreaker.rifttowny.domain.bank.PlayerWallet.absent());
    }

    /**
     * @param prices what founding costs. Charged to the founder's own wallet, because the town does
     *        not exist yet to pay for itself
     * @param wallet where that money comes from
     */
    public TownService(
            final CivicStore store,
            final NamePolicy namePolicy,
            final Clock clock,
            final net.riftbreaker.rifttowny.domain.territory.TerritoryIndex index,
            final CivicCacheRefresher civic,
            final FlagOverrides overrides,
            final net.riftbreaker.rifttowny.domain.territory.RuinIndex ruins,
            final Duration ruinReclaimDelay,
            final Duration ruinLifetime,
            final net.riftbreaker.rifttowny.domain.bank.CivicPrices prices,
            final net.riftbreaker.rifttowny.domain.bank.PlayerWallet wallet
    ) {
        this(store, namePolicy, clock, index, civic, overrides, ruins, ruinReclaimDelay,
                ruinLifetime, prices, wallet,
                net.riftbreaker.rifttowny.domain.justice.Outlaws.empty());
    }

    /**
     * @param outlaws the in-memory outlaw book. A merge lifts the survivor's outlawries against the
     *        people it is absorbing, and the book has to be told or it goes on naming them - the
     *        listing would show members as outlawed, and a pardon would be refused as NOT_OUTLAWED
     *        because the row is already gone
     */
    public TownService(
            final CivicStore store,
            final NamePolicy namePolicy,
            final Clock clock,
            final net.riftbreaker.rifttowny.domain.territory.TerritoryIndex index,
            final CivicCacheRefresher civic,
            final FlagOverrides overrides,
            final net.riftbreaker.rifttowny.domain.territory.RuinIndex ruins,
            final Duration ruinReclaimDelay,
            final Duration ruinLifetime,
            final net.riftbreaker.rifttowny.domain.bank.CivicPrices prices,
            final net.riftbreaker.rifttowny.domain.bank.PlayerWallet wallet,
            final net.riftbreaker.rifttowny.domain.justice.Outlaws outlaws
    ) {
        this.outlaws = Objects.requireNonNull(outlaws, "outlaws");
        this.store = Objects.requireNonNull(store, "store");
        this.namePolicy = Objects.requireNonNull(namePolicy, "namePolicy");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.index = Objects.requireNonNull(index, "index");
        this.civic = Objects.requireNonNull(civic, "civic");
        this.overrides = Objects.requireNonNull(overrides, "overrides");
        this.ruins = Objects.requireNonNull(ruins, "ruins");
        this.ruinReclaimDelay = Objects.requireNonNull(ruinReclaimDelay, "ruinReclaimDelay");
        this.ruinLifetime = Objects.requireNonNull(ruinLifetime, "ruinLifetime");
        this.prices = Objects.requireNonNull(prices, "prices");
        this.wallet = Objects.requireNonNull(wallet, "wallet");
    }

    /**
     * Founds a town.
     *
     * <p>Needs no permission: anyone may found a town. It creates the town's role book in the same
     * transaction, because a town without one cannot answer a single permission question — and a
     * later repair would have to invent which roles it should have had.</p>
     *
     * @param founderName the founder's current Minecraft name, recorded if they are new to RiftTowny
     */
    public CompletableFuture<ServiceResult<Town>> found(
            final ResidentId founder, final String founderName, final String rawName) {
        Objects.requireNonNull(founder, "founder");
        Objects.requireNonNull(founderName, "founderName");

        final NameCheck check = namePolicy.check(rawName);
        if (!(check instanceof NameCheck.Accepted accepted)) {
            return completed(ServiceResult.nameRejected(check.problems()));
        }
        final OrganisationName name = accepted.name();

        // Charged to the founder, and it leaves the economy rather than landing in the treasury of
        // the town it paid for - a founding fee that funded the new town would be a fee in name
        // only. Zero by default, in which case the wallet is never touched.
        return PlayerCharge.charging(wallet, founder, prices.townFounding(wallet.currency()),
                () -> foundInTransaction(founder, founderName, name));
    }

    private CompletableFuture<ServiceResult<Town>> foundInTransaction(
            final ResidentId founder, final String founderName, final OrganisationName name) {
        return refreshing(transaction(transaction -> {
            if (transaction.towns().findByName(name.normalised()).isPresent()) {
                throw new ChangeRefusedException(ChangeDenial.NAME_TAKEN);
            }

            // The id is minted before the founder is asked to join it, because joinTown is where
            // the one-town-per-resident rule lives and it needs a target. Minting an id for a
            // founding that is then refused costs nothing; a UUID is not a scarce resource.
            final TownId id = TownId.random();
            final Resident resident = transaction.residents().find(founder)
                    .orElseGet(() -> Resident.newcomer(founder, founderName, clock.instant()));
            final Resident joined = require(resident.joinTown(id));

            // Written in this order deliberately: Town.restore refuses a mayor who is not one of
            // its residents, so the resident row has to exist before the town can be read back.
            final Town town = Town.found(id, name, founder, UUID.randomUUID(), clock.instant());
            transaction.residents().save(joined);
            transaction.towns().save(town);
            transaction.roles().save(
                    RoleBook.defaultsFor(OrganisationScope.TOWN, id.value(), clock.instant()));
            transaction.publish(new DomainEvent.TownFounded(id, name, founder), correlation("found", id));
            return town;
        }), Town::id);
    }

    /**
     * Offers a place in the town to a player.
     *
     * <p>Requires {@link Permission#INVITE_RESIDENT}. The offer alone changes nothing — the player
     * has to accept it — which is the whole point: joining a town moves what a player may do and
     * where, and a town that could conscript somebody would be deciding that for them.</p>
     */
    public CompletableFuture<ServiceResult<Invitation>> invite(
            final ResidentId actor, final TownId townId, final ResidentId who) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(who, "who");
        Objects.requireNonNull(townId, "townId");

        return transaction(transaction -> {
            final Town town = town(transaction, townId);
            requirePermission(transaction, town, actor, Permission.INVITE_RESIDENT);
            if (town.hasResident(who)) {
                throw new ChangeRefusedException(ChangeDenial.ALREADY_IN_THIS_TOWN);
            }
            final Resident resident = resident(transaction, who);
            if (resident.town().isPresent()) {
                throw new ChangeRefusedException(ChangeDenial.ALREADY_IN_ANOTHER_TOWN);
            }

            final Invitation invitation = Invitation.offer(
                    townId, Invitation.Invitee.of(who), actor, clock.instant());
            transaction.invitations().save(invitation);
            return invitation;
        });
    }

    /** Withdraws an offer. Requires {@link Permission#INVITE_RESIDENT}. */
    public CompletableFuture<ServiceResult<ResidentId>> withdrawInvitation(
            final ResidentId actor, final TownId townId, final ResidentId who) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(who, "who");
        Objects.requireNonNull(townId, "townId");

        return transaction(transaction -> {
            final Town town = town(transaction, townId);
            requirePermission(transaction, town, actor, Permission.INVITE_RESIDENT);
            if (!transaction.invitations().delete(townId, Invitation.Invitee.of(who))) {
                throw new ChangeRefusedException(ChangeDenial.NO_INVITATION);
            }
            return who;
        });
    }

    /**
     * A player accepting a town's offer.
     *
     * <p>Needs no permission: it is their own decision, and the town already made its half of it by
     * sending the invitation. The offer is consumed in the same transaction as the join, so one
     * invitation cannot be accepted twice.</p>
     */
    public CompletableFuture<ServiceResult<Town>> acceptInvitation(
            final ResidentId who, final TownId townId) {
        Objects.requireNonNull(who, "who");
        Objects.requireNonNull(townId, "townId");

        return pardoning(refreshing(transaction(transaction -> {
            final Optional<Invitation> invitation =
                    transaction.invitations().find(townId, Invitation.Invitee.of(who));
            if (invitation.isEmpty()) {
                throw new ChangeRefusedException(ChangeDenial.NO_INVITATION);
            }
            if (invitation.get().hasExpired(clock.instant())) {
                // Not deleted here: the refusal rolls the transaction back, so the tidy-up would go
                // with it. The sweep owns that, and the listings already hide lapsed offers.
                throw new ChangeRefusedException(ChangeDenial.INVITATION_EXPIRED);
            }

            final Town updated = admit(transaction, townId, who);
            transaction.invitations().delete(townId, Invitation.Invitee.of(who));
            return updated;
        }), Town::id), who);
    }

    /**
     * Joins a town that has declared itself open, with no invitation.
     *
     * <p>The one way into a town that nobody in it agreed to individually — which is exactly what
     * {@code /town set open} means, and why it is a setting a town has to turn on rather than the
     * default.</p>
     *
     * <p>Openness is read from the town <em>inside</em> the transaction, not from the cache. A town
     * that closed a moment ago must refuse the join that was already in flight, and a cache is by
     * construction a little behind.</p>
     *
     * <p>Any outstanding invitation is cleared on the way through. Somebody who was invited and
     * then walked in through the open door should not be left with an offer they can accept
     * again.</p>
     */
    public CompletableFuture<ServiceResult<Town>> joinOpenTown(
            final ResidentId who, final TownId townId) {
        Objects.requireNonNull(who, "who");
        Objects.requireNonNull(townId, "townId");

        return pardoning(refreshing(transaction(transaction -> {
            final Town town = town(transaction, townId);
            if (!town.profile().open()) {
                throw new ChangeRefusedException(ChangeDenial.TOWN_IS_NOT_OPEN);
            }
            final Town updated = admit(transaction, townId, who);
            transaction.invitations().delete(townId, Invitation.Invitee.of(who));
            return updated;
        }), Town::id), who);
    }

    /** Turns an offer down, so it stops appearing in the player's list. */
    public CompletableFuture<ServiceResult<TownId>> declineInvitation(
            final ResidentId who, final TownId townId) {
        Objects.requireNonNull(who, "who");
        Objects.requireNonNull(townId, "townId");

        return transaction(transaction -> {
            if (!transaction.invitations().delete(townId, Invitation.Invitee.of(who))) {
                throw new ChangeRefusedException(ChangeDenial.NO_INVITATION);
            }
            return townId;
        });
    }

    /** Every town that has offered this player a place, lapsed offers excluded. */
    public CompletableFuture<List<Invitation>> invitationsFor(final ResidentId who) {
        Objects.requireNonNull(who, "who");
        return store.inTransaction(transaction -> {
            final java.time.Instant now = clock.instant();
            return transaction.invitations().to(Invitation.Invitee.of(who)).stream()
                    .filter(invitation -> !invitation.hasExpired(now))
                    .toList();
        });
    }

    /** Every offer a town has outstanding. */
    public CompletableFuture<List<Invitation>> invitationsFrom(final TownId townId) {
        Objects.requireNonNull(townId, "townId");
        return store.inTransaction(transaction -> {
            final java.time.Instant now = clock.instant();
            return transaction.invitations().from(townId).stream()
                    .filter(invitation -> !invitation.hasExpired(now))
                    .toList();
        });
    }

    /**
     * Adds a resident without asking them.
     *
     * <p>Requires {@link Permission#INVITE_RESIDENT}, and <strong>bypasses the player's
     * consent</strong> — which is why no command reaches it. It exists for administration and for
     * migration imports, where the consent already happened somewhere else. The player-facing path
     * is {@link #invite} and {@link #acceptInvitation}, and anything wired to this instead would be
     * quietly undoing that.</p>
     */
    public CompletableFuture<ServiceResult<Town>> join(
            final ResidentId actor, final ResidentId who, final TownId townId) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(who, "who");
        Objects.requireNonNull(townId, "townId");

        return pardoning(refreshing(transaction(transaction -> {
            final Town town = town(transaction, townId);
            requirePermission(transaction, town, actor, Permission.INVITE_RESIDENT);
            return admit(transaction, townId, who);
        }), Town::id), who);
    }

    /**
     * The membership change itself, shared by the consented path and the forced one.
     *
     * <p>Admission lifts any outlawry the town held against them, for the reason {@link Town#admit}
     * gives for clearing their trust in the same breath: holding both would leave a member carrying
     * an outsider state, and it is the state {@code OutlawService.declare} refuses outright as
     * CANNOT_OUTLAW_A_RESIDENT. Without this a town could invite somebody it had outlawed and then
     * find its own resident named on {@code /town outlaw list}, with the pardon that would clear it
     * refused as NOT_OUTLAWED once they left again.</p>
     */
    private Town admit(
            final CivicTransaction transaction, final TownId townId, final ResidentId who) {
        final Town town = town(transaction, townId);
        final Resident resident = resident(transaction, who);
        final Resident joined = require(resident.joinTown(townId));
        final Outcome<Town> admitted = town.admit(who);
        final Town updated = require(admitted);

        transaction.residents().save(joined);
        transaction.towns().save(updated);
        transaction.outlaws().pardon(townId, who);
        transaction.publishAll(admitted.events(), correlation("join", townId));
        return updated;
    }

    /**
     * Tells the outlaw book what an admission just did, after the commit.
     *
     * <p>The book is a separate thing from the table and every reader consults it, so a pardon that
     * reached only the database would keep a member on the survivor's list until a restart.</p>
     */
    private CompletableFuture<ServiceResult<Town>> pardoning(
            final CompletableFuture<ServiceResult<Town>> pending, final ResidentId who) {
        return pending.thenApply(result -> {
            result.value().ifPresent(town -> outlaws.pardon(town.id(), who));
            return result;
        });
    }

    /**
     * A resident leaving of their own accord.
     *
     * <p>Needs no permission. A town that could stop someone leaving would be a prison, and the
     * invariants that do apply — the last resident, the mayor — are the town's, not an actor's.</p>
     */
    public CompletableFuture<ServiceResult<Town>> leave(
            final ResidentId who, final TownId townId) {
        Objects.requireNonNull(who, "who");
        Objects.requireNonNull(townId, "townId");
        return release(who, townId, true, null);
    }

    /**
     * Removing somebody else.
     *
     * <p>Requires {@link Permission#KICK_RESIDENT} <em>and</em> that the actor outranks the target.
     * Without the rank check any officer could remove any other, including one the leader had
     * placed above them.</p>
     */
    public CompletableFuture<ServiceResult<Town>> kick(
            final ResidentId actor, final ResidentId target, final TownId townId) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(townId, "townId");
        return release(target, townId, false, actor);
    }

    /**
     * Hands the mayoralty to another resident.
     *
     * <p>Only the sitting mayor may do this. It is deliberately not a permission a role can hold:
     * a role that could hand over the mayoralty could hand it to its own holder, which is a coup
     * with extra steps.</p>
     */
    public CompletableFuture<ServiceResult<Town>> transferMayoralty(
            final ResidentId actor, final TownId townId, final ResidentId candidate) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(townId, "townId");
        Objects.requireNonNull(candidate, "candidate");

        return refreshing(transaction(transaction -> {
            final Town town = town(transaction, townId);
            if (town.standingOf(actor) != SystemRole.LEADER) {
                throw new ChangeRefusedException(ChangeDenial.MISSING_PERMISSION);
            }
            final Outcome<Town> transferred = town.transferLeadership(candidate);
            final Town updated = require(transferred);
            transaction.towns().save(updated);
            transaction.publishAll(transferred.events(), correlation("leadership", townId));
            return updated;
        }), Town::id);
    }

    /**
     * Trusts an outsider. Requires {@link Permission#MANAGE_TRUST}.
     *
     * <p>Trust is the one grant a town can make to somebody who is not in it, and it is deliberately
     * narrow: it lifts the holder from {@code VISITOR} to {@code TRUSTED} on the relationship ladder
     * and nothing more. What that is worth is entirely the town's own flag settings — trust is not a
     * fixed set of powers, it is a rung a town decides the meaning of.</p>
     *
     * <p>The aggregate refuses trusting a resident, because a member already outranks a trusted
     * outsider and holding both would create a second, weaker path to rights they already have.</p>
     */
    public CompletableFuture<ServiceResult<Town>> trust(
            final ResidentId actor, final TownId townId, final ResidentId outsider) {
        return changeTrust(actor, townId, outsider, true);
    }

    /**
     * Revokes it.
     *
     * <p>Takes effect the moment the transaction commits and the cache is refreshed — there is no
     * grace period, because the point of revoking trust is usually that it is being abused.</p>
     */
    public CompletableFuture<ServiceResult<Town>> untrust(
            final ResidentId actor, final TownId townId, final ResidentId outsider) {
        return changeTrust(actor, townId, outsider, false);
    }

    /**
     * Both halves, since only one line differs.
     *
     * <p>Written once rather than twice for the reason the whole codebase keeps saying: the two are
     * a permission check away from being the same method, and two copies is how the check ends up on
     * one of them.</p>
     */
    private CompletableFuture<ServiceResult<Town>> changeTrust(
            final ResidentId actor,
            final TownId townId,
            final ResidentId outsider,
            final boolean granting
    ) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(townId, "townId");
        Objects.requireNonNull(outsider, "outsider");

        return refreshing(transaction(transaction -> {
            final Town town = town(transaction, townId);
            requirePermission(transaction, town, actor, Permission.MANAGE_TRUST);

            final Outcome<Town> changed =
                    granting ? town.trust(outsider) : town.untrust(outsider);
            final Town updated = require(changed);
            transaction.towns().save(updated);
            transaction.publishAll(changed.events(), correlation("trust", townId));
            return updated;
        }), Town::id);
    }

    /**
     * Changes what a town says about itself, and who it lets in.
     * Requires {@link Permission#MANAGE_SETTINGS}.
     *
     * <p>Takes a transform rather than a finished {@link net.riftbreaker.rifttowny.domain.org.TownProfile},
     * and applies it to the town as loaded <em>inside</em> the transaction. The obvious alternative —
     * the caller reads the town, edits its profile and hands the result back — is a lost update: two
     * co-mayors setting the board and the tag in the same second would each write a profile built
     * from the state before the other's change, and one of the two edits would vanish with nothing
     * to show it ever happened.</p>
     */
    public CompletableFuture<ServiceResult<Town>> setProfile(
            final ResidentId actor,
            final TownId townId,
            final java.util.function.UnaryOperator<net.riftbreaker.rifttowny.domain.org.TownProfile> change
    ) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(townId, "townId");
        Objects.requireNonNull(change, "change");

        return refreshing(transaction(transaction -> {
            final Town town = town(transaction, townId);
            requirePermission(transaction, town, actor, Permission.MANAGE_SETTINGS);

            final Outcome<Town> changed = town.withProfile(change.apply(town.profile()));
            final Town updated = require(changed);
            transaction.towns().save(updated);
            transaction.publishAll(changed.events(), correlation("profile", townId));
            return updated;
        }), Town::id);
    }

    /** Renames a town, keeping its id and civic account. Requires {@link Permission#RENAME_ORGANISATION}. */
    public CompletableFuture<ServiceResult<Town>> rename(
            final ResidentId actor, final TownId townId, final String rawName) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(townId, "townId");

        final NameCheck check = namePolicy.check(rawName);
        if (!(check instanceof NameCheck.Accepted accepted)) {
            return completed(ServiceResult.nameRejected(check.problems()));
        }
        final OrganisationName name = accepted.name();

        return refreshing(transaction(transaction -> {
            final Town town = town(transaction, townId);
            requirePermission(transaction, town, actor, Permission.RENAME_ORGANISATION);

            // A town may keep its own normalised name across a recapitalisation, so a match on a
            // different id is the only real conflict.
            final Optional<Town> holder = transaction.towns().findByName(name.normalised());
            if (holder.isPresent() && !holder.get().id().equals(townId)) {
                throw new ChangeRefusedException(ChangeDenial.NAME_TAKEN);
            }

            final Outcome<Town> renamed = town.renameTo(name);
            final Town updated = require(renamed);
            transaction.towns().save(updated);
            transaction.publishAll(renamed.events(), correlation("rename", townId));
            return updated;
        }), Town::id);
    }

    /**
     * Disbands a town. Requires {@link Permission#DISBAND}.
     *
     * <p>Residents are released, not deleted. Claims, areas and trust rows cascade with the town,
     * and the role book is removed explicitly since it is keyed on the organisation rather than
     * owned by the town row.</p>
     *
     * <p>Settling the civic account is {@code RT-MOD-BANK}'s job and is not done here, so a server
     * with banking enabled must not call this directly until that module lands.</p>
     */
    public CompletableFuture<ServiceResult<TownId>> disband(
            final ResidentId actor, final TownId townId) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(townId, "townId");
        return end(actor, townId);
    }

    /**
     * Ends a town that is not choosing to end.
     *
     * <p>For a tax run that has exhausted a town's grace, and for anything else that decides a town
     * has fallen. No actor and no permission check: nobody is doing this, it is happening to
     * them.</p>
     *
     * <p>Otherwise identical to a disband — the same ruin, the same sweeps, the same caches — so a
     * town that fell to bankruptcy leaves exactly what a town that was deleted leaves, and can be
     * rebuilt by somebody who can afford its upkeep.</p>
     */
    public CompletableFuture<ServiceResult<TownId>> collapse(final TownId townId) {
        Objects.requireNonNull(townId, "townId");
        return end(null, townId);
    }

    private CompletableFuture<ServiceResult<TownId>> end(
            final ResidentId actor, final TownId townId) {
        return transaction(transaction -> {
            final Town town = town(transaction, townId);
            if (actor != null) {
                requirePermission(transaction, town, actor, Permission.DISBAND);
            }

            final List<ResidentId> departing = new java.util.ArrayList<>();
            for (final Resident resident : transaction.residents().findByTown(townId)) {
                transaction.residents().save(require(resident.leaveTown()));
                departing.add(resident.id());
            }
            final int released = departing.size();

            // Everybody here stops being a citizen of the town's nation at the same moment, so any
            // nation role they held goes too. Nothing ties rt_role_member to citizenship, and a
            // nation officer whose town has just ceased to exist would otherwise keep the office.
            town.nation().ifPresent(nation -> transaction.publishAll(
                    CitizenRoles.revoke(transaction, nation, departing),
                    correlation("disband", townId)));

            // Flag overrides are swept before the claims go, because the claim rows are where the
            // chunk list comes from. They have no foreign key to sweep them - the target column
            // holds four kinds of identifier and so cannot reference one table - and an override
            // left behind would come back into force the moment another town claimed the chunk.
            final List<FlagTarget> flagTargets = new java.util.ArrayList<>();
            flagTargets.add(FlagTarget.organisation(townId));
            transaction.claims().of(townId).forEach(claim ->
                    flagTargets.add(FlagTarget.claim(claim.chunk())));
            flagTargets.forEach(target -> transaction.flags().clearAll(target));

            // The land does not become wilderness yet. A town's buildings are still standing when
            // its last resident goes, and reverting instantly means the first player to walk past
            // owns everything in them. The claims move to a ruin, which holds them until somebody
            // takes it on or the window closes. With ruins switched off this is empty and the
            // behaviour is the old one.
            final Optional<RuinService.Fallen> fallen = RuinService.recordFall(
                    transaction, town, transaction.claims().of(townId), clock.instant(),
                    ruinReclaimDelay, ruinLifetime);

            // Territory is released explicitly: rt_claim cascades from rt_town, but relying on the
            // cascade would make the claim count in the announcement below unknowable, and it would
            // hide the release from anything watching claims rather than towns.
            final int releasedChunks = transaction.claims().deleteAllOf(townId);
            // Offers go with the town that made them. One left behind could be accepted into a town
            // that no longer exists, which fails confusingly instead of simply not being there.
            transaction.invitations().deleteAllFor(townId);
            transaction.roles().delete(OrganisationScope.TOWN, townId.value());
            transaction.towns().delete(townId);
            if (releasedChunks > 0) {
                transaction.publish(
                        new DomainEvent.ChunkUnclaimed(townId, releasedChunks + " chunk(s)"),
                        correlation("disband", townId));
            }
            transaction.publish(
                    new DomainEvent.TownDisbanded(townId, town.name(), released),
                    correlation("disband", townId));
            return new Disbanded(townId, List.copyOf(flagTargets), fallen.orElse(null));
        }).thenApply(result -> {
            // After the commit, never inside it. A rolled-back disband whose claims had already
            // left the cache would leave the town's land unprotected until the next restart.
            result.value().ifPresent(disbanded -> {
                index.removeAllOf(disbanded.town());
                disbanded.flagTargets().forEach(overrides::clearAll);
                // The ruin takes the chunks the claims just gave up, in that order: a moment with
                // neither would read as wilderness, and a block broken during it would be gone.
                if (disbanded.fallen() != null) {
                    ruins.put(disbanded.fallen().ruin(), disbanded.fallen().chunks());
                }
            });
            return result;
        }).thenCompose(result -> {
            // The civic cache is told the same way every other change tells it: by re-reading. The
            // town is gone, so the read finds nothing and the refresh drops it - one path for
            // "changed" and "vanished" rather than two that could disagree.
            if (result.value().isEmpty()) {
                return CompletableFuture.completedFuture(map(result));
            }
            return civic.refresh(result.value().orElseThrow().town())
                    .thenApply(ignored -> map(result));
        });
    }

    /**
     * What a disband removed, carried out of the transaction so the caches can follow.
     *
     * <p>Internal: {@link #disband} still answers with the town id, because the targets are a
     * cache-maintenance detail and no caller has a use for them.</p>
     */
    private record Disbanded(
            TownId town, List<FlagTarget> flagTargets, RuinService.Fallen fallen) {
    }

    private static ServiceResult<TownId> map(final ServiceResult<Disbanded> result) {
        return result.value()
                .<ServiceResult<TownId>>map(disbanded -> ServiceResult.success(disbanded.town()))
                .orElseGet(() -> result.denial()
                        .<ServiceResult<TownId>>map(ServiceResult::refused)
                        .orElseGet(() -> ServiceResult.nameRejected(result.nameProblems())));
    }

    /** What a resident may do in a town, for a GUI or a public API query. */
    public CompletableFuture<java.util.Set<Permission>> permissionsOf(
            final ResidentId who, final TownId townId) {
        Objects.requireNonNull(who, "who");
        Objects.requireNonNull(townId, "townId");
        return store.inTransaction(transaction -> {
            final Town town = town(transaction, townId);
            return roleBook(transaction, townId).effectivePermissions(who, town.standingOf(who));
        });
    }


    /**
     * Removes residents who have not been seen since a cutoff. Requires
     * {@link Permission#KICK_RESIDENT}.
     *
     * <p><strong>A preview by default.</strong> {@code apply} false does every check and every
     * exclusion and then returns the list without writing anything, so the count a mayor is shown is
     * produced by the same pass that would do the work rather than by a second one that could
     * disagree with it. That is the rule the importer arrived at after previewing "0 towns" and then
     * importing forty.</p>
     *
     * <p>Two people are never removed, and both are skipped rather than refused, because a purge
     * that failed outright because of one protected resident would be useless on the towns that most
     * need it:</p>
     *
     * <ul>
     *   <li><strong>The mayor</strong>, whatever their last login. {@link Town#release} refuses them
     *       anyway, but hitting that inside the loop would roll back the whole purge — and a mayor
     *       purging their own town while inactive themselves is an ordinary way to use this.</li>
     *   <li><strong>Anybody the actor does not outrank</strong>, by the same rule a single kick
     *       applies. Without it an officer could clear out their co-officers by choosing a number
     *       that happens to catch them.</li>
     * </ul>
     *
     * @param inactiveFor how long since a resident was last seen makes them a candidate. Measured
     *        from this service's own clock, so "now" is decided in one place
     * @param apply false to look without touching anything
     */
    public CompletableFuture<ServiceResult<Purge>> purge(
            final ResidentId actor,
            final TownId townId,
            final Duration inactiveFor,
            final boolean apply
    ) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(townId, "townId");
        Objects.requireNonNull(inactiveFor, "inactiveFor");
        final Instant before = clock.instant().minus(inactiveFor);

        return refreshing(transaction(transaction -> {
            Town town = town(transaction, townId);
            requirePermission(transaction, town, actor, Permission.KICK_RESIDENT);

            final RoleBook book = roleBook(transaction, townId);
            final int actorRank = book.rankOf(actor, town.standingOf(actor));

            final List<ResidentId> removing = new java.util.ArrayList<>();
            int protectedByRank = 0;
            for (final Resident resident : transaction.residents().findByTown(townId)) {
                if (!resident.lastSeenAt().isBefore(before) || resident.id().equals(town.mayor())) {
                    continue;
                }
                if (actorRank <= book.rankOf(resident.id(), town.standingOf(resident.id()))) {
                    protectedByRank++;
                    continue;
                }
                removing.add(resident.id());
            }

            if (!apply || removing.isEmpty()) {
                return new Purge(townId, List.copyOf(removing), protectedByRank, false);
            }

            for (final ResidentId who : removing) {
                // Every one of the five things a departure touches, because a purge is a departure
                // repeated and nothing about it is special. Missing one here would be the same bug
                // as missing it in a kick, except forty times over and on people who are not
                // logged in to notice.
                final Resident resident = resident(transaction, who);
                final Outcome<Town> released = town.release(who, false);
                town = require(released);
                transaction.residents().save(require(resident.leaveTown()));
                transaction.publishAll(released.events(), correlation("purge", townId));

                transaction.roles().find(OrganisationScope.TOWN, townId.value()).ifPresent(roles -> {
                    final Outcome<RoleBook> stripped = roles.unassignAll(who);
                    stripped.value().ifPresent(transaction.roles()::save);
                    transaction.publishAll(stripped.events(), correlation("purge", townId));
                });
                PlotService.releaseHeldPlots(transaction, index, townId, who);
            }

            // The nation roles of everybody who left, in one call rather than one per person: they
            // all stop being citizens at the same moment and CitizenRoles takes the whole list.
            final Town finished = town;
            finished.nation().ifPresent(nation -> transaction.publishAll(
                    CitizenRoles.revoke(transaction, nation, removing),
                    correlation("purge", townId)));

            transaction.towns().save(town);
            return new Purge(townId, List.copyOf(removing), protectedByRank, true);
        }), Purge::town);
    }

    /**
     * What a purge did, or would do.
     *
     * @param removed who went, or who would go on a preview
     * @param protectedByRank how many were skipped because the actor does not outrank them
     * @param applied false when this was a look rather than a change
     */
    public record Purge(
            TownId town, List<ResidentId> removed, int protectedByRank, boolean applied) {

        public Purge {
            Objects.requireNonNull(town, "town");
            removed = List.copyOf(removed);
        }

        public int count() {
            return removed.size();
        }
    }
    private CompletableFuture<ServiceResult<Town>> release(
            final ResidentId who,
            final TownId townId,
            final boolean voluntary,
            final ResidentId actor
    ) {
        return refreshing(transaction(transaction -> {
            final Town town = town(transaction, townId);
            if (actor != null) {
                requirePermission(transaction, town, actor, Permission.KICK_RESIDENT);
                requireOutranks(transaction, town, actor, who);
            }

            final Resident resident = resident(transaction, who);
            final Outcome<Town> released = town.release(who, voluntary);
            final Town updated = require(released);
            final Resident departed = require(resident.leaveTown());

            transaction.residents().save(departed);
            transaction.towns().save(updated);
            transaction.publishAll(released.events(), correlation("leave", townId));

            // Roles do not expire with residency: nothing ties rt_role_member to rt_resident, so
            // without this a departed or kicked player keeps their officer permissions and their
            // officer rank, and every guard that consults the book keeps saying yes to somebody who
            // may since have joined a rival town.
            //
            // Optional rather than required: a town whose book is missing has no assignments to
            // revoke, and refusing a departure over it would trap the resident.
            transaction.roles().find(OrganisationScope.TOWN, townId.value()).ifPresent(book -> {
                final Outcome<RoleBook> stripped = book.unassignAll(who);
                stripped.value().ifPresent(transaction.roles()::save);
                transaction.publishAll(stripped.events(), correlation("leave", townId));
            });

            // And any plots they held here. A plot is authority over a square inside the town, and
            // somebody who is no longer a member should not keep it - the same rule as their roles,
            // for the same reason.
            PlotService.releaseHeldPlots(transaction, index, townId, who);

            // And their nation roles, for the same reason one step out: citizenship of a nation is
            // residency in one of its towns, so leaving the town ends it. Somebody who joins another
            // town in the same nation does not get the role back - it was granted to them, and
            // granting it again is the nation's decision to make.
            town.nation().ifPresent(nation -> transaction.publishAll(
                    CitizenRoles.revoke(transaction, nation, List.of(who)),
                    correlation("leave", townId)));
            return updated;
        }), Town::id);
    }

    private static void requirePermission(
            final CivicTransaction transaction,
            final Town town,
            final ResidentId actor,
            final Permission permission
    ) {
        final RoleBook book = roleBook(transaction, town.id());
        if (!book.allows(actor, permission, town.standingOf(actor))) {
            throw new ChangeRefusedException(ChangeDenial.MISSING_PERMISSION);
        }
    }

    private static void requireOutranks(
            final CivicTransaction transaction,
            final Town town,
            final ResidentId actor,
            final ResidentId target
    ) {
        final RoleBook book = roleBook(transaction, town.id());
        final int actorRank = book.rankOf(actor, town.standingOf(actor));
        final int targetRank = book.rankOf(target, town.standingOf(target));
        if (actorRank <= targetRank) {
            throw new ChangeRefusedException(ChangeDenial.INSUFFICIENT_ROLE_PRIORITY);
        }
    }


    /**
     * Offers to absorb another town. Only the surviving town's mayor may make the offer.
     *
     * <p>Changes nothing by itself. The offer is an ordinary {@link Invitation} — the survivor is
     * the inviter, the town to be absorbed is the invitee — which is expressible today because
     * {@code Invitation.Invitee.of(TownId)} exists and an inviter is an {@code OrganisationId}. No
     * migration, and it lapses in seven days like every other offer.</p>
     *
     * <p><strong>The inviter survives and the accepter ends</strong>, so the irreversible half is
     * typed by the mayor whose town ceases to exist. There is nowhere in {@code rt_invitation} to
     * record a direction other than the pairing itself, and inventing one would be a migration to
     * express something the pairing already says.</p>
     */
    public CompletableFuture<ServiceResult<TownId>> offerMerge(
            final ResidentId actor, final TownId survivorId, final TownId absorbedId) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(survivorId, "survivorId");
        Objects.requireNonNull(absorbedId, "absorbedId");

        return transaction(transaction -> {
            if (survivorId.equals(absorbedId)) {
                throw new ChangeRefusedException(ChangeDenial.CANNOT_MERGE_WITH_SELF);
            }
            requireMayor(town(transaction, survivorId), actor);
            town(transaction, absorbedId);
            transaction.invitations().save(Invitation.offer(
                    survivorId, Invitation.Invitee.of(absorbedId), actor, clock.instant()));
            return absorbedId;
        });
    }

    /** Withdraws an offer. The survivor's mayor, since it is their offer. */
    public CompletableFuture<ServiceResult<TownId>> withdrawMergeOffer(
            final ResidentId actor, final TownId survivorId, final TownId absorbedId) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(survivorId, "survivorId");
        Objects.requireNonNull(absorbedId, "absorbedId");

        return transaction(transaction -> {
            requireMayor(town(transaction, survivorId), actor);
            if (!transaction.invitations().delete(survivorId, Invitation.Invitee.of(absorbedId))) {
                throw new ChangeRefusedException(ChangeDenial.NO_INVITATION);
            }
            return absorbedId;
        });
    }

    /** Every merge offered to this town, lapsed ones excluded. */
    public CompletableFuture<List<Invitation>> mergeOffersTo(final TownId townId) {
        Objects.requireNonNull(townId, "townId");
        return store.inTransaction(transaction ->
                transaction.invitations().to(Invitation.Invitee.of(townId)).stream()
                        // Town inviters only. The same table carries nation offers addressed to this
                        // town, and a nation inviting it to join is not an offer to absorb it.
                        .filter(offer -> offer.inviter() instanceof TownId)
                        .filter(offer -> offer.expiresAt().isAfter(clock.instant()))
                        .toList());
    }

    /**
     * Accepts a merge, absorbing the accepting mayor's own town into the offering one.
     *
     * <p><strong>The order below is the safety argument rather than a style.</strong>
     * {@code rt_claim} cascades from {@code rt_town}, and {@code ConnectionTownStore.delete} nulls
     * {@code rt_resident.town_id} before dropping the row. So the land must be handed over and the
     * residents moved <em>before</em> the absorbed town is deleted. Written the other way round, a
     * five-hundred-chunk town's territory is destroyed by the cascade and its people are left
     * townless — which is the automatic irreversible deletion of claims the brief permanently
     * excludes, arrived at purely by putting the statements in the wrong order.</p>
     *
     * <p>One transaction, and the statement count is bounded by residents rather than by land: the
     * claims move in a single {@code UPDATE}, so a town with five hundred chunks is the same work as
     * one with a single chunk. A failure anywhere rolls the whole thing back rather than leaving
     * half a town.</p>
     */
    public CompletableFuture<ServiceResult<Merged>> acceptMerge(
            final ResidentId actor, final TownId absorbedId, final TownId survivorId) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(absorbedId, "absorbedId");
        Objects.requireNonNull(survivorId, "survivorId");

        return transaction(transaction -> {
            if (survivorId.equals(absorbedId)) {
                throw new ChangeRefusedException(ChangeDenial.CANNOT_MERGE_WITH_SELF);
            }
            Town survivor = town(transaction, survivorId);
            final Town absorbed = town(transaction, absorbedId);
            // The accepting mayor is the one losing a town, so they must be its mayor. The
            // survivor's consent is the standing offer.
            requireMayor(absorbed, actor);

            // Re-read rather than trusted from offer time: an offer may be a week old, and a town
            // that has joined a nation since is a different proposition entirely.
            if (!absorbed.nation().equals(survivor.nation())) {
                throw new ChangeRefusedException(ChangeDenial.MERGE_REQUIRES_THE_SAME_NATION);
            }

            final Invitation.Invitee invitee = Invitation.Invitee.of(absorbedId);
            final Invitation offer = transaction.invitations().to(invitee).stream()
                    .filter(one -> survivorId.equals(one.inviter()))
                    .findFirst()
                    .orElseThrow(() -> new ChangeRefusedException(ChangeDenial.NO_INVITATION));
            if (!offer.expiresAt().isAfter(clock.instant())) {
                throw new ChangeRefusedException(ChangeDenial.INVITATION_EXPIRED);
            }
            transaction.invitations().delete(survivorId, invitee);

            // 1. The people, moved one at a time because a resident row carries its own town, and
            // admitted through the aggregate so the survivor's own rules still apply to each.
            final List<ResidentId> moved = new java.util.ArrayList<>();
            for (final Resident resident : transaction.residents().findByTown(absorbedId)) {
                transaction.residents().save(
                        require(require(resident.leaveTown()).joinTown(survivorId)));
                survivor = require(survivor.admit(resident.id()));
                moved.add(resident.id());
                // Admitting somebody the survivor had outlawed would leave a member barred by their
                // own town, which OutlawService refuses outright. Lifted rather than refused, for
                // the same reason Town.admit clears trust: the later, more specific decision wins.
                transaction.outlaws().pardon(survivorId, resident.id());
            }

            // 2. The money, as a movement rather than a rewrite, so both ledgers show where it went.
            // BankStore.forget is deliberately not called: it deletes the ledger with the balance,
            // and a merge is exactly when somebody asks where a town's treasury ended up.
            moveTreasury(transaction, absorbed, survivor);

            // 3. The land, before the town row goes. One statement, whatever the size of the town.
            //
            // The absorbed homeblock is demoted first, because a town may hold exactly one and the
            // survivor already has its own. Two would be a state TownClaims.moveHomeblock's own
            // javadoc calls out: "a moment with two homeblocks, or none, would make unclaim refuse
            // or permit the wrong thing". Concretely, unclaim would then refuse BOTH with
            // HOMEBLOCK_MUST_BE_UNCLAIMED_LAST for as long as the town held more than one chunk, so
            // the absorbed town's old home chunk could never be released again, and homeblock()
            // - a findFirst - would answer with whichever the database happened to return.
            //
            // OUTPOST rather than ORDINARY, and that is not cosmetic: ClaimKind.anchorsConnectivity
            // is HOMEBLOCK or OUTPOST, so if the absorbed land does not touch the survivor's, an
            // ordinary demotion would leave that whole cluster unreachable from any anchor and the
            // next unclaim anywhere in the town would fail UNCLAIM_WOULD_DISCONNECT.
            final ChunkKey demoted = transaction.claims().of(absorbedId).stream()
                    .filter(claim -> claim.kind() == ClaimKind.HOMEBLOCK)
                    .map(net.riftbreaker.rifttowny.domain.territory.Claim::chunk)
                    .findFirst()
                    .orElse(null);
            final int chunksMoved = transaction.claims().reassignAllOf(absorbedId, survivorId);
            if (demoted != null) {
                transaction.claims().updateKind(demoted, ClaimKind.OUTPOST);
            }

            // 4. What the absorbed town alone owned. Its organisation-level flag overrides name a
            // town that is about to stop existing; its per-chunk overrides describe chunks that
            // survive and are deliberately left standing, since dropping them could throw open land
            // whose owner had locked it.
            // The organisation-level overrides name a town that is about to stop existing. The spawn
            // is NOT deleted here: rt_town_spawn cascades from rt_town, and a second delete beside
            // the cascade reads as though the cascade were absent - which is exactly the wrong
            // thing to imply three lines under a comment about working around one.
            transaction.flags().clearAll(FlagTarget.organisation(absorbedId));
            transaction.invitations().deleteAllFor(absorbedId);

            final List<String> rolesLost = new java.util.ArrayList<>();
            roleBook(transaction, absorbedId).ordered().stream()
                    .filter(role -> !role.isSystem())
                    .forEach(role -> rolesLost.add(role.name()));
            transaction.roles().delete(OrganisationScope.TOWN, absorbedId.value());

            // 5. The nation, if there is one. Both towns are in it - that was checked above - so it
            // keeps the survivor, and no citizen loses their citizenship or their nation role.
            absorbed.nation().ifPresent(nationId -> {
                final net.riftbreaker.rifttowny.domain.org.Nation nation = transaction.nations().find(nationId)
                        .orElseThrow(() -> new ChangeRefusedException(ChangeDenial.NATION_NOT_FOUND));
                transaction.nations().save(require(nation.release(absorbedId)));
            });

            transaction.towns().save(survivor);
            transaction.towns().delete(absorbedId);

            transaction.publish(
                    new DomainEvent.TownsMerged(survivorId, absorbedId, absorbed.name(),
                            moved.size(), chunksMoved, rolesLost),
                    correlation("merge", survivorId));
            return new Merged(survivorId, absorbedId, absorbed.name(), survivor.name(),
                    List.copyOf(moved), chunksMoved, List.copyOf(rolesLost), demoted);
        }).thenCompose(result -> {
            // After the commit, never inside it. Until the index is corrected it still points every
            // one of those chunks at a town that no longer exists, and protection would answer for
            // an owner that cannot be found.
            if (result.value().isEmpty()) {
                return CompletableFuture.completedFuture(result);
            }
            final Merged merged = result.value().orElseThrow();
            index.reassignAllOf(merged.absorbed(), merged.survivor());
            // The index keeps its own copy of the kind, so the demotion has to be repeated here or
            // protection and the map would go on seeing two homeblocks until the next restart.
            // The pardons the merge performed are in the database; the book is a separate thing and
            // is told here. Without this the survivor's listing would keep naming people who are
            // now its own members, and a mayor trying to clear that would be refused NOT_OUTLAWED
            // because the row it looks for is already gone - unclearable until a restart.
            merged.residentsMoved().forEach(who -> outlaws.pardon(merged.survivor(), who));
            if (merged.demotedHomeblock() != null) {
                index.at(merged.demotedHomeblock()).ifPresent(claim -> index.put(
                        new net.riftbreaker.rifttowny.domain.territory.Claim(
                                claim.id(), claim.chunk(), claim.town(), ClaimKind.OUTPOST,
                                claim.type(), claim.owner(), claim.claimedAt())));
            }
            overrides.clearAll(FlagTarget.organisation(merged.absorbed()));
            return civic.refresh(merged.survivor())
                    .thenCompose(ignored -> civic.refresh(merged.absorbed()))
                    .thenApply(ignored -> result);
        });
    }

    /**
     * Moves everything one treasury holds into another.
     *
     * <p>A debit and a credit per currency rather than a silent rewrite, because the ledger is what
     * an operator reads when somebody asks where a town's money went — and a merge is exactly when
     * they ask.</p>
     *
     * <p><strong>Every currency, not the one the account last used.</strong>
     * {@code rt_organisation_balance} is keyed on {@code (account_id, currency)} and RiftEco is
     * multi-currency, so a town that has held two of them holds two rows — which happens to any
     * server that changes its configured currency, since the old balances stay under the old name.
     * Moving only one and then deleting the town row leaves the rest on an account no command can
     * reach again, because a balance is looked up through the town that owns it.</p>
     */
    private void moveTreasury(
            final CivicTransaction transaction, final Town absorbed, final Town survivor) {
        for (final var moving : transaction.bank().balancesOf(absorbed.bankAccountId())) {
            if (moving.isZero()) {
                continue;
            }
            final String currency = moving.currency();
            final var before = transaction.bank().balance(survivor.bankAccountId(), currency)
                    .orElseGet(() -> net.riftbreaker.rifttowny.domain.bank.Money.zero(currency));
            transaction.bank().record(
                    net.riftbreaker.rifttowny.domain.bank.LedgerEntry.of(
                            absorbed.bankAccountId(), moving,
                            net.riftbreaker.rifttowny.domain.bank.Money.zero(currency),
                            net.riftbreaker.rifttowny.domain.bank.LedgerEntry.Reason.ADMIN,
                            null, "merged into " + survivor.name().display(), clock.instant()),
                    clock.instant());
            transaction.bank().record(
                    net.riftbreaker.rifttowny.domain.bank.LedgerEntry.of(
                            survivor.bankAccountId(), moving, before.plus(moving),
                            net.riftbreaker.rifttowny.domain.bank.LedgerEntry.Reason.ADMIN,
                            null, "merged from " + absorbed.name().display(), clock.instant()),
                    clock.instant());
        }
    }

    /**
     * Mayor only, like handing over the mayoralty and for a stronger version of its reason.
     *
     * <p>A merge is worse than a disband: a disbanded town leaves a ruin anybody may reclaim, while
     * a merged town's land goes to one named town chosen by whoever typed the command. Gating it on
     * a role permission would let an officer hand the whole place to a rival.</p>
     */
    private static void requireMayor(final Town town, final ResidentId actor) {
        if (town.standingOf(actor) != SystemRole.LEADER) {
            throw new ChangeRefusedException(ChangeDenial.MISSING_PERMISSION);
        }
    }

    /**
     * What a merge did, for the message and for the caches.
     *
     * @param demotedHomeblock the absorbed town's old home chunk, now an outpost of the survivor,
     *        or null if it had none. Carried so the in-memory index can be corrected after the
     *        commit - it holds its own copy of the kind
     */
    public record Merged(
            TownId survivor,
            TownId absorbed,
            OrganisationName absorbedName,
            OrganisationName survivorName,
            List<ResidentId> residentsMoved,
            int chunksMoved,
            List<String> rolesLost,
            ChunkKey demotedHomeblock
    ) {

        /** How many people moved. Derived, so it cannot disagree with the list. */
        public int movedCount() {
            return residentsMoved.size();
        }
    }

    /**
     * The town's roles.
     *
     * <p>A town founded by this service always has a book. One without is a repair case, not a
     * permission question, so it refuses rather than silently defaulting to "allowed" or to a fresh
     * book that would hand the actor a leader role they never had.</p>
     */
    private static RoleBook roleBook(final CivicTransaction transaction, final TownId townId) {
        return transaction.roles().find(OrganisationScope.TOWN, townId.value())
                .orElseThrow(() -> new ChangeRefusedException(ChangeDenial.ROLE_NOT_FOUND));
    }

    private static Town town(final CivicTransaction transaction, final TownId id) {
        return transaction.towns().find(id)
                .orElseThrow(() -> new ChangeRefusedException(ChangeDenial.TOWN_NOT_FOUND));
    }

    private static Resident resident(final CivicTransaction transaction, final ResidentId id) {
        return transaction.residents().find(id)
                .orElseThrow(() -> new ChangeRefusedException(ChangeDenial.RESIDENT_NOT_FOUND));
    }

    /**
     * Unwraps an outcome, turning a denial into the throw that aborts the transaction.
     *
     * <p>This is the only correct way to refuse inside a transaction: returning a denial would let
     * the store commit whatever had already been written.</p>
     */
    private static <T> T require(final Outcome<T> outcome) {
        return outcome.value().orElseThrow(() ->
                new ChangeRefusedException(outcome.denial().orElseThrow()));
    }

    private static String correlation(final String action, final TownId town) {
        return action + ':' + town.value();
    }

    private static <T> CompletableFuture<ServiceResult<T>> completed(final ServiceResult<T> result) {
        return CompletableFuture.completedFuture(result);
    }

    /**
     * Tells the civic cache which town a successful change touched.
     *
     * <p>Applied per method rather than inside {@link #transaction}, because unlike the role service
     * the town this call changed is not always the one it was given — founding mints its id inside
     * the transaction, and disbanding returns nothing else.</p>
     *
     * @param which where to find the town id in a successful result
     */
    private <T> CompletableFuture<ServiceResult<T>> refreshing(
            final CompletableFuture<ServiceResult<T>> pending,
            final java.util.function.Function<T, TownId> which
    ) {
        return pending.thenCompose(result -> {
            final Optional<TownId> town = result.value().map(which);
            if (town.isEmpty()) {
                return CompletableFuture.completedFuture(result);
            }
            return civic.refresh(town.get()).thenApply(ignored -> result);
        });
    }

    /**
     * Runs work in a transaction and turns a refusal back into a result.
     *
     * <p>The exception exists to roll the transaction back; it is not how a caller should learn that
     * a player is already in a town. Catching it here keeps the command layer free of try/catch.</p>
     */
    private <T> CompletableFuture<ServiceResult<T>> transaction(final Work<T> work) {
        return store.<ServiceResult<T>>inTransaction(transaction ->
                        ServiceResult.success(work.perform(transaction)))
                .exceptionally(failure -> {
                    final Throwable cause =
                            failure instanceof CompletionException ? failure.getCause() : failure;
                    if (cause instanceof ChangeRefusedException refused) {
                        return ServiceResult.refused(refused.denial());
                    }
                    throw failure instanceof CompletionException completion
                            ? completion
                            : new CompletionException(failure);
                });
    }

    @FunctionalInterface
    private interface Work<T> {
        T perform(CivicTransaction transaction);
    }
}
