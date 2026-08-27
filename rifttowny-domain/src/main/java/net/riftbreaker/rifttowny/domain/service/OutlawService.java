package net.riftbreaker.rifttowny.domain.service;

import net.riftbreaker.rifttowny.domain.event.DomainEvent;
import net.riftbreaker.rifttowny.domain.justice.Outlaws;
import net.riftbreaker.rifttowny.domain.org.ChangeDenial;
import net.riftbreaker.rifttowny.domain.org.ResidentId;
import net.riftbreaker.rifttowny.domain.org.Town;
import net.riftbreaker.rifttowny.domain.org.TownId;
import net.riftbreaker.rifttowny.domain.role.Permission;
import net.riftbreaker.rifttowny.domain.store.ChangeRefusedException;
import net.riftbreaker.rifttowny.domain.store.CivicStore;
import net.riftbreaker.rifttowny.domain.store.CivicTransaction;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Declaring somebody unwelcome, and taking it back.
 *
 * <p>Thin, like its neighbours: the rule that matters — that outlawry sits below every outsider rung
 * and above none of the member ones — lives in {@code RelationshipResolver}, and this is what
 * persists a declaration and keeps {@link Outlaws} current.</p>
 *
 * <p><strong>Gated by {@link Permission#MANAGE_TRUST}</strong> rather than a permission of its own.
 * The trust list and the outlaw list are the same decision from opposite ends — who from outside
 * this town is treated differently from other outsiders — and somebody already able to hand a
 * stranger the run of the place is not made more dangerous by also being able to bar one. A separate
 * node would also have arrived ungranted on every existing role, so no town could use this until
 * somebody edited their roles.</p>
 */
public final class OutlawService {

    private final CivicStore store;
    private final Clock clock;
    private final Outlaws book;

    public OutlawService(final CivicStore store, final Clock clock, final Outlaws book) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.book = Objects.requireNonNull(book, "book");
    }

    /** The cache, for protection and {@code /rifttowny status}. */
    public Outlaws book() {
        return book;
    }

    /** Fills the cache from storage. Called once at enable, beside the other loads. */
    public CompletableFuture<Integer> loadAll() {
        return store.inTransaction(transaction -> {
            final List<Outlaws.Declaration> loaded = transaction.outlaws().all();
            book.replaceAll(loaded);
            return loaded.size();
        });
    }

    /**
     * Declares a player unwelcome.
     *
     * <p>Refused for a resident of the declaring town, and that refusal is what makes the ladder's
     * ordering safe to reason about rather than merely safe: membership already supersedes outlawry
     * in the resolver, so an outlawed member would have been harmless — but a mayor who typed it and
     * saw nothing happen would reasonably conclude the feature was broken.</p>
     */
    public CompletableFuture<ServiceResult<ResidentId>> declare(
            final ResidentId actor, final TownId townId, final ResidentId target) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(townId, "townId");
        Objects.requireNonNull(target, "target");

        // One reading, used for the row and for the cache, so the two cannot disagree about when a
        // sanction was imposed - which is the thing an appeal turns on.
        final java.time.Instant when = clock.instant();

        return transaction(transaction -> {
            final Town town = CivicPermissions.town(transaction, townId);
            CivicPermissions.requireTown(transaction, town, actor, Permission.MANAGE_TRUST);

            if (town.hasResident(target)) {
                throw new ChangeRefusedException(ChangeDenial.CANNOT_OUTLAW_A_RESIDENT);
            }
            if (transaction.outlaws().holds(townId, target)) {
                throw new ChangeRefusedException(ChangeDenial.ALREADY_OUTLAWED);
            }
            transaction.outlaws().declare(townId, target, actor, when);
            transaction.publish(
                    new DomainEvent.OutlawDeclared(townId, target, actor),
                    "outlaw:" + townId.value());
            return target;
        }).thenApply(result -> {
            // After the commit, never inside it: a rolled-back declaration that had already reached
            // the cache would leave a player barred by a rule the database never took.
            if (result.succeeded()) {
                book.declare(townId, target, actor, when);
            }
            return result;
        });
    }

    /** Lifts one. */
    public CompletableFuture<ServiceResult<ResidentId>> pardon(
            final ResidentId actor, final TownId townId, final ResidentId target) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(townId, "townId");
        Objects.requireNonNull(target, "target");

        return transaction(transaction -> {
            final Town town = CivicPermissions.town(transaction, townId);
            CivicPermissions.requireTown(transaction, town, actor, Permission.MANAGE_TRUST);

            if (!transaction.outlaws().pardon(townId, target)) {
                throw new ChangeRefusedException(ChangeDenial.NOT_OUTLAWED);
            }
            transaction.publish(
                    new DomainEvent.OutlawPardoned(townId, target, actor),
                    "outlaw:" + townId.value());
            return target;
        }).thenApply(result -> {
            if (result.succeeded()) {
                book.pardon(townId, target);
            }
            return result;
        });
    }

    /** One town's list, from the cache. */
    public Set<ResidentId> of(final TownId townId) {
        return book.of(townId);
    }

    /** One town's list with the officer and date behind each, from the cache. */
    public java.util.Collection<net.riftbreaker.rifttowny.domain.justice.Outlaws.Declaration>
            declarationsOf(final TownId townId) {
        return book.declarationsOf(townId);
    }

    /** Where this player is unwelcome, from the cache. */
    public Set<TownId> townsOutlawing(final ResidentId who) {
        return book.townsOutlawing(who);
    }

    /**
     * Forgets a disbanded town's list.
     *
     * <p>Cache only. The rows cascade with the town, and doing both here would be a delete against a
     * table the town's own disband has already emptied.</p>
     */
    public void forget(final TownId townId) {
        book.forget(townId);
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
