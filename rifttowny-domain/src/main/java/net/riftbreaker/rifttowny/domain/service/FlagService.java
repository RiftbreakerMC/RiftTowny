package net.riftbreaker.rifttowny.domain.service;

import net.riftbreaker.rifttowny.api.ChunkKey;
import net.riftbreaker.rifttowny.domain.event.DomainEvent;
import net.riftbreaker.rifttowny.domain.flag.FlagOverride;
import net.riftbreaker.rifttowny.domain.flag.FlagOverrides;
import net.riftbreaker.rifttowny.domain.flag.FlagTarget;
import net.riftbreaker.rifttowny.domain.flag.ProtectionFlag;
import net.riftbreaker.rifttowny.domain.flag.Relationship;
import net.riftbreaker.rifttowny.domain.org.ChangeDenial;
import net.riftbreaker.rifttowny.domain.org.OrganisationScope;
import net.riftbreaker.rifttowny.domain.org.ResidentId;
import net.riftbreaker.rifttowny.domain.org.Town;
import net.riftbreaker.rifttowny.domain.org.TownId;
import net.riftbreaker.rifttowny.domain.role.Permission;
import net.riftbreaker.rifttowny.domain.role.RoleBook;
import net.riftbreaker.rifttowny.domain.store.ChangeRefusedException;
import net.riftbreaker.rifttowny.domain.store.CivicStore;
import net.riftbreaker.rifttowny.domain.store.CivicTransaction;
import net.riftbreaker.rifttowny.domain.territory.Claim;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Changing what a town, a claim or a world allows.
 *
 * <p>Two rules do the work here, and both are about reach rather than about flags:</p>
 *
 * <ol>
 *   <li><strong>You need {@link Permission#MANAGE_FLAGS} in the town.</strong> Opening a town's
 *       chests to visitors is the same act as handing out its contents, so it is gated like one and
 *       is sensitive by default.</li>
 *   <li><strong>You may only set a flag on land your town owns.</strong> Without this, a mayor could
 *       set a claim override on somebody else's chunk — the resolver would honour it, because the
 *       resolver asks what the chunk says, not who wrote it.</li>
 * </ol>
 *
 * <p>World and administrative overrides do not go through here. They belong to whoever runs the
 * server, are gated by a Bukkit permission at the command, and have no town to check against.</p>
 */
public final class FlagService {

    private final CivicStore store;
    private final Clock clock;
    private final FlagOverrides overrides;

    /**
     * @param overrides updated after the transaction commits, never inside it. A rolled-back change
     *        that had already reached the cache would leave the server enforcing a rule the database
     *        never took
     */
    public FlagService(
            final CivicStore store, final Clock clock, final FlagOverrides overrides) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.overrides = Objects.requireNonNull(overrides, "overrides");
    }

    /**
     * Fills the in-memory set from storage.
     *
     * <p>Called once at enable and waited on. A protection check that ran before this finished would
     * silently answer from built-in defaults, which is not a smaller mistake than answering wrongly:
     * a town that had opened its doors would appear to have closed them.</p>
     */
    public CompletableFuture<Integer> loadAll() {
        return store.inTransaction(transaction -> {
            final List<FlagOverride> loaded = transaction.flags().all();
            overrides.replaceAll(loaded);
            return loaded.size();
        });
    }

    // --- town-scoped changes -------------------------------------------------------------------

    /** Sets a flag for the whole town. Requires {@link Permission#MANAGE_FLAGS}. */
    public CompletableFuture<ServiceResult<FlagOverride>> setForTown(
            final ResidentId actor,
            final TownId townId,
            final ProtectionFlag flag,
            final Relationship relationship,
            final boolean allowed
    ) {
        return set(actor, townId, FlagTarget.organisation(townId), flag, relationship, allowed);
    }

    /** Sets a flag on one chunk the town owns. Requires {@link Permission#MANAGE_FLAGS}. */
    public CompletableFuture<ServiceResult<FlagOverride>> setForClaim(
            final ResidentId actor,
            final TownId townId,
            final ChunkKey chunk,
            final ProtectionFlag flag,
            final Relationship relationship,
            final boolean allowed
    ) {
        Objects.requireNonNull(chunk, "chunk");
        return set(actor, townId, FlagTarget.claim(chunk), flag, relationship, allowed);
    }

    /** Removes the town's own opinion, letting the layer below answer again. */
    public CompletableFuture<ServiceResult<FlagTarget>> clearForTown(
            final ResidentId actor,
            final TownId townId,
            final ProtectionFlag flag,
            final Relationship relationship
    ) {
        return clear(actor, townId, FlagTarget.organisation(townId), flag, relationship);
    }

    /** Removes one chunk's opinion. */
    public CompletableFuture<ServiceResult<FlagTarget>> clearForClaim(
            final ResidentId actor,
            final TownId townId,
            final ChunkKey chunk,
            final ProtectionFlag flag,
            final Relationship relationship
    ) {
        Objects.requireNonNull(chunk, "chunk");
        return clear(actor, townId, FlagTarget.claim(chunk), flag, relationship);
    }


    /**
     * The first flag whose ladder is inverted at this target, if any.
     *
     * <p>A question rather than a handle on the cache. {@code FlagSettings.firstNonMonotonic} has
     * existed since the flag system was written, and {@code Relationship}'s own javadoc cites it as
     * the thing that catches "a configuration that lets a visitor do something a trusted outsider
     * cannot" — but nothing called it, so nothing ever caught anything. This is the caller.</p>
     *
     * <p>It reads the target's own settings rather than the resolved stack, which is the right
     * scope: a town can only be told about the rungs it set itself, and an inversion produced by an
     * administrator's layer sitting above it is not the town's to fix.</p>
     */
    public java.util.Optional<ProtectionFlag> firstInconsistent(final FlagTarget target) {
        Objects.requireNonNull(target, "target");
        return overrides.settingsFor(target).firstNonMonotonic();
    }
    /** Everything a target has been told, for a listing. */
    public CompletableFuture<List<FlagOverride>> of(final FlagTarget target) {
        Objects.requireNonNull(target, "target");
        return store.inTransaction(transaction -> transaction.flags().of(target));
    }

    // --- server-scoped changes -----------------------------------------------------------------

    /**
     * Sets a world or administrative override.
     *
     * <p>No permission check and no ownership check, because there is no town to check against.
     * The caller is responsible for gating this — it is reachable only from an operator command.</p>
     */
    public CompletableFuture<FlagOverride> setAdministrative(
            final FlagTarget target,
            final ProtectionFlag flag,
            final Relationship relationship,
            final boolean allowed,
            final ResidentId setBy
    ) {
        Objects.requireNonNull(target, "target");
        if (target.source() == net.riftbreaker.rifttowny.domain.flag.FlagSource.ORGANISATION
                || target.source() == net.riftbreaker.rifttowny.domain.flag.FlagSource.CLAIM) {
            throw new IllegalArgumentException(
                    "A town-scoped target must go through setForTown or setForClaim, so its "
                            + "permission and ownership are checked");
        }
        final FlagOverride override = FlagOverride.of(
                target, flag, relationship, allowed, setBy, clock.instant());
        return store.inTransaction(transaction -> {
            transaction.flags().set(override);
            transaction.publish(
                    new DomainEvent.FlagChanged(
                            target.source().name(), target.key(), flag.name(),
                            relationship.name(), allowed),
                    "flag:" + target.key());
            return override;
        }).thenApply(stored -> {
            overrides.apply(stored);
            return stored;
        });
    }

    /** Removes a world or administrative override. */
    public CompletableFuture<Boolean> clearAdministrative(
            final FlagTarget target,
            final ProtectionFlag flag,
            final Relationship relationship
    ) {
        Objects.requireNonNull(target, "target");
        return store.inTransaction(transaction ->
                        transaction.flags().clear(target, flag, relationship))
                .thenApply(removed -> {
                    if (removed) {
                        overrides.clear(target, flag, relationship);
                    }
                    return removed;
                });
    }


    // --- internals -----------------------------------------------------------------------------

    private CompletableFuture<ServiceResult<FlagOverride>> set(
            final ResidentId actor,
            final TownId townId,
            final FlagTarget target,
            final ProtectionFlag flag,
            final Relationship relationship,
            final boolean allowed
    ) {
        Objects.requireNonNull(flag, "flag");
        Objects.requireNonNull(relationship, "relationship");

        // Guarded here rather than only in the command, because the service is the boundary: the
        // API and any future menu reach this and not the parser.
        if (!flag.configurable()) {
            return CompletableFuture.completedFuture(
                    ServiceResult.refused(ChangeDenial.FLAG_NOT_SETTABLE));
        }
        return transaction(townId, actor, transaction -> {
            requireReach(transaction, townId, target);
            final FlagOverride override = FlagOverride.of(
                    target, flag, relationship, allowed, actor, clock.instant());
            transaction.flags().set(override);
            transaction.publish(
                    new DomainEvent.FlagChanged(
                            target.source().name(), target.key(), flag.name(),
                            relationship.name(), allowed),
                    "flag:" + townId.value());
            return override;
        }).thenApply(result -> {
            result.value().ifPresent(overrides::apply);
            return result;
        });
    }

    private CompletableFuture<ServiceResult<FlagTarget>> clear(
            final ResidentId actor,
            final TownId townId,
            final FlagTarget target,
            final ProtectionFlag flag,
            final Relationship relationship
    ) {
        Objects.requireNonNull(flag, "flag");
        Objects.requireNonNull(relationship, "relationship");

        return transaction(townId, actor, transaction -> {
            requireReach(transaction, townId, target);
            if (!transaction.flags().clear(target, flag, relationship)) {
                throw new ChangeRefusedException(ChangeDenial.FLAG_NOT_SET);
            }
            transaction.publish(
                    new DomainEvent.FlagCleared(
                            target.source().name(), target.key(), flag.name(),
                            relationship.name()),
                    "flag:" + townId.value());
            return target;
        }).thenApply(result -> {
            result.value().ifPresent(cleared -> overrides.clear(cleared, flag, relationship));
            return result;
        });
    }

    /**
     * Refuses a target the town does not own.
     *
     * <p>The organisation target is checked by identity; a claim target by asking who owns the
     * chunk. Both matter: the resolver reads a claim override without asking who wrote it, so an
     * unchecked write here would let one town configure another's land.</p>
     */
    private static void requireReach(
            final CivicTransaction transaction, final TownId townId, final FlagTarget target) {
        switch (target.source()) {
            case ORGANISATION -> {
                if (!target.key().equals(townId.value().toString())) {
                    throw new ChangeRefusedException(ChangeDenial.FLAG_TARGET_NOT_YOURS);
                }
            }
            case CLAIM -> {
                final Optional<Claim> claim = claimFor(transaction, target);
                if (claim.isEmpty()) {
                    throw new ChangeRefusedException(ChangeDenial.CHUNK_NOT_CLAIMED);
                }
                if (!claim.get().town().equals(townId)) {
                    throw new ChangeRefusedException(ChangeDenial.FLAG_TARGET_NOT_YOURS);
                }
            }
            default -> throw new ChangeRefusedException(ChangeDenial.FLAG_TARGET_NOT_YOURS);
        }
    }

    /** The claim a {@code CLAIM} target names, if it is still claimed. */
    private static Optional<Claim> claimFor(
            final CivicTransaction transaction, final FlagTarget target) {
        final String[] parts = target.key().split(":");
        if (parts.length != 3) {
            return Optional.empty();
        }
        try {
            return transaction.claims().at(new ChunkKey(
                    UUID.fromString(parts[0]),
                    Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2])));
        } catch (final IllegalArgumentException unparseable) {
            return Optional.empty();
        }
    }

    private <T> CompletableFuture<ServiceResult<T>> transaction(
            final TownId townId, final ResidentId actor, final Work<T> work) {
        Objects.requireNonNull(townId, "townId");
        Objects.requireNonNull(actor, "actor");

        return store.<ServiceResult<T>>inTransaction(transaction -> {
            final Town town = transaction.towns().find(townId)
                    .orElseThrow(() -> new ChangeRefusedException(ChangeDenial.TOWN_NOT_FOUND));
            final RoleBook book = transaction.roles()
                    .find(OrganisationScope.TOWN, townId.value())
                    .orElseThrow(() -> new ChangeRefusedException(ChangeDenial.ROLE_NOT_FOUND));
            if (!book.allows(actor, Permission.MANAGE_FLAGS, town.standingOf(actor))) {
                throw new ChangeRefusedException(ChangeDenial.MISSING_PERMISSION);
            }
            return ServiceResult.success(work.perform(transaction));
        }).exceptionally(failure -> {
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
