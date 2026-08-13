package net.riftbreaker.rifttowny.domain.service;

import net.riftbreaker.rifttowny.domain.event.DomainEvent;
import net.riftbreaker.rifttowny.domain.flag.FlagOverrides;
import net.riftbreaker.rifttowny.domain.flag.FlagTarget;
import net.riftbreaker.rifttowny.domain.naming.NameCheck;
import net.riftbreaker.rifttowny.domain.naming.NamePolicy;
import net.riftbreaker.rifttowny.domain.naming.OrganisationName;
import net.riftbreaker.rifttowny.domain.org.ChangeDenial;
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
import java.time.Duration;
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
    private final Duration ruinLifetime;

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
                net.riftbreaker.rifttowny.domain.territory.RuinIndex.empty(), Duration.ZERO);
    }

    /**
     * @param ruins the in-memory ruin index, given the chunks a disbanded town gives up
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
            final Duration ruinLifetime
    ) {
        this.store = Objects.requireNonNull(store, "store");
        this.namePolicy = Objects.requireNonNull(namePolicy, "namePolicy");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.index = Objects.requireNonNull(index, "index");
        this.civic = Objects.requireNonNull(civic, "civic");
        this.overrides = Objects.requireNonNull(overrides, "overrides");
        this.ruins = Objects.requireNonNull(ruins, "ruins");
        this.ruinLifetime = Objects.requireNonNull(ruinLifetime, "ruinLifetime");
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
     * Adds a resident to a town.
     *
     * <p>Requires {@link Permission#INVITE_RESIDENT}. Self-service joining through an invitation the
     * player accepts is a separate flow and does not exist yet; until it does, somebody with the
     * permission has to do the adding.</p>
     */
    public CompletableFuture<ServiceResult<Town>> join(
            final ResidentId actor, final ResidentId who, final TownId townId) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(who, "who");
        Objects.requireNonNull(townId, "townId");

        return refreshing(transaction(transaction -> {
            final Town town = town(transaction, townId);
            requirePermission(transaction, town, actor, Permission.INVITE_RESIDENT);

            final Resident resident = resident(transaction, who);
            final Resident joined = require(resident.joinTown(townId));
            final Outcome<Town> admitted = town.admit(who);
            final Town updated = require(admitted);

            transaction.residents().save(joined);
            transaction.towns().save(updated);
            transaction.publishAll(admitted.events(), correlation("join", townId));
            return updated;
        }), Town::id);
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

        return transaction(transaction -> {
            final Town town = town(transaction, townId);
            requirePermission(transaction, town, actor, Permission.DISBAND);

            int released = 0;
            for (final Resident resident : transaction.residents().findByTown(townId)) {
                transaction.residents().save(require(resident.leaveTown()));
                released++;
            }

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
                    ruinLifetime);

            // Territory is released explicitly: rt_claim cascades from rt_town, but relying on the
            // cascade would make the claim count in the announcement below unknowable, and it would
            // hide the release from anything watching claims rather than towns.
            final int releasedChunks = transaction.claims().deleteAllOf(townId);
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
