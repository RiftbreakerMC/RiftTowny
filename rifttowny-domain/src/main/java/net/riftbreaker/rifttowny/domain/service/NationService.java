package net.riftbreaker.rifttowny.domain.service;

import net.riftbreaker.rifttowny.domain.event.DomainEvent;
import net.riftbreaker.rifttowny.domain.naming.NameCheck;
import net.riftbreaker.rifttowny.domain.naming.NamePolicy;
import net.riftbreaker.rifttowny.domain.naming.OrganisationName;
import net.riftbreaker.rifttowny.domain.org.ChangeDenial;
import net.riftbreaker.rifttowny.domain.org.Invitation;
import net.riftbreaker.rifttowny.domain.org.Nation;
import net.riftbreaker.rifttowny.domain.org.NationId;
import net.riftbreaker.rifttowny.domain.org.OrganisationScope;
import net.riftbreaker.rifttowny.domain.org.Outcome;
import net.riftbreaker.rifttowny.domain.org.Resident;
import net.riftbreaker.rifttowny.domain.org.ResidentId;
import net.riftbreaker.rifttowny.domain.org.Town;
import net.riftbreaker.rifttowny.domain.org.TownId;
import net.riftbreaker.rifttowny.domain.role.Permission;
import net.riftbreaker.rifttowny.domain.role.RoleBook;
import net.riftbreaker.rifttowny.domain.role.SystemRole;
import net.riftbreaker.rifttowny.domain.store.ChangeRefusedException;
import net.riftbreaker.rifttowny.domain.store.CivicStore;
import net.riftbreaker.rifttowny.domain.store.CivicTransaction;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Nation lifecycle.
 *
 * <p>Two things make this different from {@link TownService}, and both come from the same fact: a
 * nation has no residents of its own.</p>
 *
 * <ul>
 *   <li><strong>Standing is indirect.</strong> Citizenship is residency in a member town, so every
 *       permission check has to find the actor's town first. {@link Nation#standingOf} takes that
 *       town rather than looking it up, so the dependency is visible at the call site.</li>
 *   <li><strong>Joining takes two consents.</strong> Neither one-sided rule is safe: a nation that
 *       could admit a town unilaterally would move that town's protection relationship without
 *       asking, and a town that could attach itself to any nation would walk into every member
 *       town's territory as a citizen. So the nation offers an {@link Invitation} and the town
 *       accepts it, and the accept consumes the offer in the same transaction as the join.</li>
 * </ul>
 *
 * <p>Nation <em>roles</em> can be created but not yet edited: there is no {@code NationRoleService}.
 * A nation's leader holds every permission and its citizens hold the member defaults, which is
 * enough for a nation to function and is stated here rather than left to be discovered.</p>
 */
public final class NationService {

    private final CivicStore store;
    private final NamePolicy namePolicy;
    private final Clock clock;
    private final CivicCacheRefresher civic;

    public NationService(
            final CivicStore store,
            final NamePolicy namePolicy,
            final Clock clock,
            final CivicCacheRefresher civic
    ) {
        this.store = Objects.requireNonNull(store, "store");
        this.namePolicy = Objects.requireNonNull(namePolicy, "namePolicy");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.civic = Objects.requireNonNull(civic, "civic");
    }

    /**
     * Founds a nation around a town.
     *
     * <p>Requires {@link Permission#MANAGE_ALLEGIANCE} in that town: founding commits the town to
     * the nation, so it is the same decision as joining one and is gated identically.</p>
     */
    public CompletableFuture<ServiceResult<Nation>> found(
            final ResidentId founder, final TownId capital, final String rawName) {
        Objects.requireNonNull(founder, "founder");
        Objects.requireNonNull(capital, "capital");

        final NameCheck check = namePolicy.check(rawName);
        if (!(check instanceof NameCheck.Accepted accepted)) {
            return completed(ServiceResult.nameRejected(check.problems()));
        }
        final OrganisationName name = accepted.name();

        return refreshingTown(transaction(transaction -> {
            final Town town = town(transaction, capital);
            requireTownPermission(transaction, town, founder, Permission.MANAGE_ALLEGIANCE);
            if (town.nation().isPresent()) {
                throw new ChangeRefusedException(ChangeDenial.TOWN_ALREADY_IN_ANOTHER_NATION);
            }
            if (transaction.nations().findByName(name.normalised()).isPresent()) {
                throw new ChangeRefusedException(ChangeDenial.NAME_TAKEN);
            }

            final NationId id = NationId.random();
            final Nation nation = Nation.found(
                    id, name, founder, capital, UUID.randomUUID(), clock.instant());
            final Outcome<Town> joined = town.joinNation(id);
            final Town updatedTown = require(joined);

            // The nation row goes first: rt_town.nation_id references it, and saving the town
            // against an id that does not exist yet is a constraint violation rather than a
            // sentence.
            transaction.nations().save(nation);
            transaction.towns().save(updatedTown);
            transaction.roles().save(
                    RoleBook.defaultsFor(OrganisationScope.NATION, id.value(), clock.instant()));
            transaction.publish(
                    new DomainEvent.NationCreated(id, name, founder, capital),
                    correlation("found", id));
            transaction.publishAll(joined.events(), correlation("found", id));
            return new TownAffected<>(nation, capital);
        }));
    }

    /**
     * Offers membership to a town.
     *
     * <p>Requires {@link Permission#MANAGE_ALLEGIANCE} in the nation. The offer alone changes
     * nothing — the town's own leadership has to accept it — which is the whole point.</p>
     */
    public CompletableFuture<ServiceResult<Invitation>> invite(
            final ResidentId actor, final NationId nationId, final TownId townId) {
        Objects.requireNonNull(townId, "townId");

        return transaction(transaction -> {
            final Nation nation = requireNationPermission(
                    transaction, nationId, actor, Permission.MANAGE_ALLEGIANCE);
            final Town town = town(transaction, townId);
            if (nation.hasTown(townId)) {
                throw new ChangeRefusedException(ChangeDenial.TOWN_ALREADY_IN_THIS_NATION);
            }
            if (town.nation().isPresent()) {
                throw new ChangeRefusedException(ChangeDenial.TOWN_ALREADY_IN_ANOTHER_NATION);
            }

            final Invitation invitation = Invitation.offer(
                    nationId, Invitation.Invitee.of(townId), actor, clock.instant());
            transaction.invitations().save(invitation);
            return invitation;
        });
    }

    /** Withdraws an offer. Requires {@link Permission#MANAGE_ALLEGIANCE} in the nation. */
    public CompletableFuture<ServiceResult<TownId>> withdraw(
            final ResidentId actor, final NationId nationId, final TownId townId) {
        Objects.requireNonNull(townId, "townId");

        return transaction(transaction -> {
            requireNationPermission(transaction, nationId, actor, Permission.MANAGE_ALLEGIANCE);
            if (!transaction.invitations().delete(nationId, Invitation.Invitee.of(townId))) {
                throw new ChangeRefusedException(ChangeDenial.NO_INVITATION);
            }
            return townId;
        });
    }

    /**
     * A town accepting an offer.
     *
     * <p>Requires {@link Permission#MANAGE_ALLEGIANCE} in the <em>town</em>, and an outstanding
     * invitation from the nation. The offer is consumed in the same transaction as the join, so one
     * invitation cannot be accepted twice.</p>
     */
    public CompletableFuture<ServiceResult<Nation>> accept(
            final ResidentId actor, final TownId townId, final NationId nationId) {
        Objects.requireNonNull(townId, "townId");
        Objects.requireNonNull(nationId, "nationId");

        return refreshingTown(transaction(transaction -> {
            final Town town = town(transaction, townId);
            requireTownPermission(transaction, town, actor, Permission.MANAGE_ALLEGIANCE);

            final Optional<Invitation> invitation =
                    transaction.invitations().find(nationId, Invitation.Invitee.of(townId));
            if (invitation.isEmpty()) {
                throw new ChangeRefusedException(ChangeDenial.NO_INVITATION);
            }
            if (invitation.get().hasExpired(clock.instant())) {
                // Refused, and deliberately not deleted here. The refusal rolls the transaction
                // back, so a delete alongside it would be undone anyway - the tidy-up belongs to
                // pruneExpiredInvitations, and the listings below already hide lapsed offers.
                throw new ChangeRefusedException(ChangeDenial.INVITATION_EXPIRED);
            }

            final Nation nation = nation(transaction, nationId);
            final Outcome<Nation> admitted = nation.admit(townId);
            final Nation updatedNation = require(admitted);
            final Outcome<Town> joined = town.joinNation(nationId);
            final Town updatedTown = require(joined);

            transaction.nations().save(updatedNation);
            transaction.towns().save(updatedTown);
            transaction.invitations().delete(nationId, Invitation.Invitee.of(townId));
            transaction.publishAll(admitted.events(), correlation("join", nationId));
            return new TownAffected<>(updatedNation, townId);
        }));
    }

    /**
     * A town leaving of its own accord.
     *
     * <p>Requires {@link Permission#MANAGE_ALLEGIANCE} in the town. A nation that could stop a town
     * leaving would be a cage, and the invariants that do apply — the capital, the last town — are
     * the nation's own.</p>
     */
    public CompletableFuture<ServiceResult<Nation>> leave(
            final ResidentId actor, final TownId townId) {
        Objects.requireNonNull(townId, "townId");
        return release(actor, townId, null);
    }

    /**
     * Removing a member town.
     *
     * <p>Requires {@link Permission#MANAGE_ALLEGIANCE} in the nation. The capital cannot be expelled
     * while other towns remain — the nation would be left without one.</p>
     */
    public CompletableFuture<ServiceResult<Nation>> expel(
            final ResidentId actor, final NationId nationId, final TownId townId) {
        Objects.requireNonNull(nationId, "nationId");
        Objects.requireNonNull(townId, "townId");
        return release(actor, townId, nationId);
    }

    /** Moves the capital to another member town. Requires nation {@link Permission#MANAGE_SETTINGS}. */
    public CompletableFuture<ServiceResult<Nation>> moveCapital(
            final ResidentId actor, final NationId nationId, final TownId townId) {
        Objects.requireNonNull(townId, "townId");

        return refreshingNation(transaction(transaction -> {
            final Nation nation = requireNationPermission(
                    transaction, nationId, actor, Permission.MANAGE_SETTINGS);
            final Outcome<Nation> moved = nation.moveCapital(townId);
            final Nation updated = require(moved);
            transaction.nations().save(updated);
            transaction.publishAll(moved.events(), correlation("capital", nationId));
            return updated;
        }));
    }

    /**
     * Hands the crown to another citizen.
     *
     * <p>Only the sitting leader may do this, and deliberately not through a permission: a role that
     * could hand over leadership could hand it to its own holder.</p>
     */
    public CompletableFuture<ServiceResult<Nation>> transferLeadership(
            final ResidentId actor, final NationId nationId, final ResidentId candidate) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(candidate, "candidate");

        return refreshingNation(transaction(transaction -> {
            final Nation nation = nation(transaction, nationId);
            if (!nation.leader().equals(actor)) {
                throw new ChangeRefusedException(ChangeDenial.MISSING_PERMISSION);
            }
            final boolean candidateIsCitizen = townOf(transaction, candidate)
                    .map(nation::hasTown)
                    .orElse(false);
            final Outcome<Nation> transferred =
                    nation.transferLeadership(candidate, candidateIsCitizen);
            final Nation updated = require(transferred);
            transaction.nations().save(updated);
            transaction.publishAll(transferred.events(), correlation("crown", nationId));
            return updated;
        }));
    }

    /**
     * Changes what a nation says about itself. Requires {@link Permission#MANAGE_SETTINGS} in it.
     *
     * <p>Takes a transform and applies it inside the transaction, for the same reason as
     * {@link TownService#setProfile}: read-edit-write from outside is a lost update whenever two
     * people in the same leadership change two different settings at once.</p>
     */
    public CompletableFuture<ServiceResult<Nation>> setProfile(
            final ResidentId actor,
            final NationId nationId,
            final java.util.function.UnaryOperator<net.riftbreaker.rifttowny.domain.org.NationProfile> change
    ) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(nationId, "nationId");
        Objects.requireNonNull(change, "change");

        return refreshingNation(transaction(transaction -> {
            final Nation nation = requireNationPermission(
                    transaction, nationId, actor, Permission.MANAGE_SETTINGS);
            final Outcome<Nation> changed = nation.withProfile(change.apply(nation.profile()));
            final Nation updated = require(changed);
            transaction.nations().save(updated);
            transaction.publishAll(changed.events(), correlation("profile", nationId));
            return updated;
        }));
    }

    /** Renames a nation. Requires {@link Permission#RENAME_ORGANISATION} in the nation. */
    public CompletableFuture<ServiceResult<Nation>> rename(
            final ResidentId actor, final NationId nationId, final String rawName) {
        final NameCheck check = namePolicy.check(rawName);
        if (!(check instanceof NameCheck.Accepted accepted)) {
            return completed(ServiceResult.nameRejected(check.problems()));
        }
        final OrganisationName name = accepted.name();

        return refreshingNation(transaction(transaction -> {
            final Nation nation = requireNationPermission(
                    transaction, nationId, actor, Permission.RENAME_ORGANISATION);
            final Optional<Nation> holder = transaction.nations().findByName(name.normalised());
            if (holder.isPresent() && !holder.get().id().equals(nationId)) {
                throw new ChangeRefusedException(ChangeDenial.NAME_TAKEN);
            }
            final Outcome<Nation> renamed = nation.renameTo(name);
            final Nation updated = require(renamed);
            transaction.nations().save(updated);
            transaction.publishAll(renamed.events(), correlation("rename", nationId));
            return updated;
        }));
    }

    /**
     * Disbands a nation. Requires {@link Permission#DISBAND} in it.
     *
     * <p>Member towns are released, not deleted. Their {@code nation_id} is cleared explicitly
     * rather than left to a cascade, because a town pointing at a nation that no longer exists would
     * read as a citizen of nowhere and would take its residents' nation relationship with it.</p>
     */
    public CompletableFuture<ServiceResult<NationId>> disband(
            final ResidentId actor, final NationId nationId) {
        return transaction(transaction -> {
            final Nation nation = requireNationPermission(
                    transaction, nationId, actor, Permission.DISBAND);

            final List<TownId> members = List.copyOf(nation.towns());
            for (final TownId townId : members) {
                final Town town = town(transaction, townId);
                town.leaveNation(false).value().ifPresent(transaction.towns()::save);
            }
            transaction.invitations().deleteAllFor(nationId);
            transaction.roles().delete(OrganisationScope.NATION, nationId.value());
            transaction.nations().delete(nationId);
            transaction.publish(
                    new DomainEvent.NationDisbanded(nationId, nation.name(), members.size()),
                    correlation("disband", nationId));
            return new TownsAffected<>(nationId, members);
        }).thenCompose(this::refreshAll);
    }

    /**
     * Everything a town has been offered.
     *
     * <p>Lapsed offers are filtered rather than shown greyed out. A player told about an invitation
     * they cannot accept would try, and be refused, and reasonably call that a bug.</p>
     */
    public CompletableFuture<List<Invitation>> invitationsFor(final TownId townId) {
        Objects.requireNonNull(townId, "townId");
        return store.inTransaction(transaction ->
                standing(transaction.invitations().to(Invitation.Invitee.of(townId))));
    }

    /** Everything a nation has outstanding, lapsed offers excluded. */
    public CompletableFuture<List<Invitation>> invitationsFrom(final NationId nationId) {
        Objects.requireNonNull(nationId, "nationId");
        return store.inTransaction(transaction ->
                standing(transaction.invitations().from(nationId)));
    }

    /**
     * Sweeps lapsed offers.
     *
     * <p>The only thing that deletes them. An expired invitation is already inert — it is filtered
     * from every listing and refused on accept — so nothing depends on this having run, and it can
     * be scheduled as rarely as an operator likes.</p>
     */
    public CompletableFuture<Integer> pruneExpiredInvitations() {
        return store.inTransaction(transaction ->
                transaction.invitations().deleteExpired(clock.instant()));
    }

    private List<Invitation> standing(final List<Invitation> found) {
        final Instant now = clock.instant();
        return found.stream().filter(invitation -> !invitation.hasExpired(now)).toList();
    }

    // --- internals -----------------------------------------------------------------------------

    private CompletableFuture<ServiceResult<Nation>> release(
            final ResidentId actor, final TownId townId, final NationId expelledBy) {
        return refreshingTown(transaction(transaction -> {
            final Town town = town(transaction, townId);
            final NationId nationId = town.nation()
                    .orElseThrow(() -> new ChangeRefusedException(ChangeDenial.TOWN_NOT_IN_A_NATION));
            if (expelledBy != null) {
                if (!expelledBy.equals(nationId)) {
                    throw new ChangeRefusedException(ChangeDenial.TOWN_NOT_IN_THIS_NATION);
                }
                requireNationPermission(
                        transaction, nationId, actor, Permission.MANAGE_ALLEGIANCE);
            } else {
                requireTownPermission(transaction, town, actor, Permission.MANAGE_ALLEGIANCE);
            }

            final Nation nation = nation(transaction, nationId);
            final Outcome<Nation> released = nation.release(townId);
            final Nation updated = require(released);
            final boolean dissolves = nation.wouldDissolveOnLeaving(townId);
            final Outcome<Town> left = town.leaveNation(dissolves);
            final Town updatedTown = require(left);

            // The town's residents stop being citizens the moment it leaves, so their nation roles
            // go with it. Skipped when the nation dissolves, because its whole role book is about
            // to be deleted and stripping assignments out of a book nobody will read again is work
            // for its own sake.
            if (!dissolves) {
                transaction.publishAll(
                        CitizenRoles.revoke(transaction, nationId, List.copyOf(town.residents())),
                        correlation("leave", nationId));
            }

            transaction.towns().save(updatedTown);
            if (dissolves) {
                transaction.invitations().deleteAllFor(nationId);
                transaction.roles().delete(OrganisationScope.NATION, nationId.value());
                transaction.nations().delete(nationId);
                transaction.publish(
                        new DomainEvent.NationDisbanded(nationId, nation.name(), 1),
                        correlation("leave", nationId));
            } else {
                transaction.nations().save(updated);
            }
            transaction.publishAll(left.events(), correlation("leave", nationId));
            return new TownAffected<>(updated, townId);
        }));
    }

    /**
     * The nation, having checked the actor's standing in it.
     *
     * <p>Standing needs the actor's town, which the nation cannot see. Loaded here, once, rather
     * than in each caller — a check that silently skipped the lookup would treat every officer as a
     * visitor and refuse everything, or worse, be written to skip the check instead.</p>
     */
    private static Nation requireNationPermission(
            final CivicTransaction transaction,
            final NationId nationId,
            final ResidentId actor,
            final Permission permission
    ) {
        Objects.requireNonNull(nationId, "nationId");
        Objects.requireNonNull(actor, "actor");
        final Nation nation = nation(transaction, nationId);
        final RoleBook book = transaction.roles()
                .find(OrganisationScope.NATION, nationId.value())
                .orElseThrow(() -> new ChangeRefusedException(ChangeDenial.ROLE_NOT_FOUND));
        final SystemRole standing =
                nation.standingOf(actor, townOf(transaction, actor).orElse(null));
        if (!book.allows(actor, permission, standing)) {
            throw new ChangeRefusedException(ChangeDenial.MISSING_PERMISSION);
        }
        return nation;
    }

    private static void requireTownPermission(
            final CivicTransaction transaction,
            final Town town,
            final ResidentId actor,
            final Permission permission
    ) {
        Objects.requireNonNull(actor, "actor");
        final RoleBook book = transaction.roles()
                .find(OrganisationScope.TOWN, town.id().value())
                .orElseThrow(() -> new ChangeRefusedException(ChangeDenial.ROLE_NOT_FOUND));
        if (!book.allows(actor, permission, town.standingOf(actor))) {
            throw new ChangeRefusedException(ChangeDenial.MISSING_PERMISSION);
        }
    }

    private static Optional<TownId> townOf(
            final CivicTransaction transaction, final ResidentId who) {
        return transaction.residents().find(who).flatMap(Resident::town);
    }

    private static Nation nation(final CivicTransaction transaction, final NationId id) {
        return transaction.nations().find(id)
                .orElseThrow(() -> new ChangeRefusedException(ChangeDenial.NATION_NOT_FOUND));
    }

    private static Town town(final CivicTransaction transaction, final TownId id) {
        return transaction.towns().find(id)
                .orElseThrow(() -> new ChangeRefusedException(ChangeDenial.TOWN_NOT_FOUND));
    }

    private static <T> T require(final Outcome<T> outcome) {
        return outcome.value().orElseThrow(() ->
                new ChangeRefusedException(outcome.denial().orElseThrow()));
    }

    private static String correlation(final String action, final NationId nation) {
        return action + ':' + nation.value();
    }

    private static <T> CompletableFuture<ServiceResult<T>> completed(final ServiceResult<T> result) {
        return CompletableFuture.completedFuture(result);
    }

    /**
     * Refreshes the one town a change touched, then unwraps it.
     *
     * <p>The civic cache holds each town's nation, so anything that moves a town between nations has
     * to reach it. A town whose cached copy still named its old nation would keep granting citizens
     * of that nation the run of its territory.</p>
     */
    private <T> CompletableFuture<ServiceResult<T>> refreshingTown(
            final CompletableFuture<ServiceResult<TownAffected<T>>> pending) {
        return pending.thenCompose(result -> {
            final Optional<TownAffected<T>> affected = result.value();
            if (affected.isEmpty()) {
                return CompletableFuture.completedFuture(unwrap(result));
            }
            return civic.refresh(affected.get().town())
                    .thenCompose(ignored -> alsoRefreshNation(unwrap(result)));
        });
    }

    private CompletableFuture<ServiceResult<NationId>> refreshAll(
            final ServiceResult<TownsAffected<NationId>> result) {
        final Optional<TownsAffected<NationId>> affected = result.value();
        if (affected.isEmpty()) {
            return CompletableFuture.completedFuture(unwrapAll(result));
        }
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (final TownId town : affected.get().towns()) {
            chain = chain.thenCompose(ignored -> civic.refresh(town));
        }
        return chain.thenCompose(ignored -> alsoRefreshNation(unwrapAll(result)));
    }

    /** For the changes that touch the nation row and no town: rename, capital, crown, settings. */
    private <T> CompletableFuture<ServiceResult<T>> refreshingNation(
            final CompletableFuture<ServiceResult<T>> pending) {
        return pending.thenCompose(this::alsoRefreshNation);
    }

    /**
     * Updates the nation cache from whatever the result names.
     *
     * <p>Driven off the returned value rather than passed a nation id at each of the nine call
     * sites, because the ninth is the one that would be forgotten. Every mutating method here
     * returns either the changed {@link Nation} or, for a disband, its {@link NationId} — and a
     * disband is exactly the case that must reach the cache, since a dissolved nation left in it
     * would keep answering placeholders with a name nobody holds.</p>
     */
    private <T> CompletableFuture<ServiceResult<T>> alsoRefreshNation(final ServiceResult<T> result) {
        final NationId id = result.value().map(NationService::nationIdOf).orElse(null);
        return id == null
                ? CompletableFuture.completedFuture(result)
                : civic.refreshNation(id).thenApply(ignored -> result);
    }

    private static NationId nationIdOf(final Object value) {
        return switch (value) {
            case Nation nation -> nation.id();
            case NationId id -> id;
            // An Invitation or a TownId: the nation row did not change, so there is nothing to
            // re-read. Returning null here is the "nothing to do" answer, not a missed case.
            default -> null;
        };
    }

    private static <T> ServiceResult<T> unwrap(final ServiceResult<TownAffected<T>> result) {
        return result.value()
                .<ServiceResult<T>>map(affected -> ServiceResult.success(affected.value()))
                .orElseGet(() -> carryRefusal(result));
    }

    private static <T> ServiceResult<T> unwrapAll(final ServiceResult<TownsAffected<T>> result) {
        return result.value()
                .<ServiceResult<T>>map(affected -> ServiceResult.success(affected.value()))
                .orElseGet(() -> carryRefusal(result));
    }

    private static <T, R> ServiceResult<R> carryRefusal(final ServiceResult<T> result) {
        return result.denial()
                .<ServiceResult<R>>map(ServiceResult::refused)
                .orElseGet(() -> ServiceResult.nameRejected(result.nameProblems()));
    }

    /** A result plus the town whose cached copy has to be refreshed. */
    private record TownAffected<T>(T value, TownId town) {
    }

    /** The same, for a change that touched several. */
    private record TownsAffected<T>(T value, List<TownId> towns) {
    }

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
