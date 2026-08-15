package net.riftbreaker.rifttowny.domain.service;

import net.riftbreaker.rifttowny.domain.diplomacy.DiplomacyBook;
import net.riftbreaker.rifttowny.domain.diplomacy.Relation;
import net.riftbreaker.rifttowny.domain.event.DomainEvent;
import net.riftbreaker.rifttowny.domain.org.ChangeDenial;
import net.riftbreaker.rifttowny.domain.org.Nation;
import net.riftbreaker.rifttowny.domain.org.NationId;
import net.riftbreaker.rifttowny.domain.org.ResidentId;
import net.riftbreaker.rifttowny.domain.role.Permission;
import net.riftbreaker.rifttowny.domain.store.ChangeRefusedException;
import net.riftbreaker.rifttowny.domain.store.CivicStore;
import net.riftbreaker.rifttowny.domain.store.CivicTransaction;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Declaring allies and enemies.
 *
 * <p>Thin on purpose: the rule that matters — an alliance takes two, an enmity takes one — lives in
 * {@link DiplomacyBook}, and this is what persists a declaration and keeps the cache current.</p>
 *
 * <p>One declaration is one transaction. Accepting an alliance does <em>not</em> write the other
 * nation's row for them: it writes only the accepting nation's own, and the pair becomes allied
 * because both rows now exist. Writing both would be one nation signing on another's behalf, which
 * is precisely what the two-row design exists to prevent.</p>
 */
public final class DiplomacyService {

    private final CivicStore store;
    private final Clock clock;
    private final DiplomacyBook book;

    public DiplomacyService(final CivicStore store, final Clock clock, final DiplomacyBook book) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.book = Objects.requireNonNull(book, "book");
    }

    /** The cache, for protection, placeholders and {@code /rifttowny status}. */
    public DiplomacyBook book() {
        return book;
    }

    /** Fills the cache from storage. Called once at enable, beside the other loads. */
    public CompletableFuture<Integer> loadAll() {
        return store.inTransaction(transaction -> {
            final List<DiplomacyBook.Declaration> loaded = transaction.relations().all();
            book.replaceAll(loaded);
            return loaded.size();
        });
    }

    /**
     * Declares a relation. Requires {@link Permission#MANAGE_DIPLOMACY} in the declaring nation.
     *
     * <p>A nation may not hold both kinds against the same target, so declaring an enemy withdraws
     * any alliance offer and the reverse. Leaving both would make "are we allied" and "are we at
     * war" both true, and every reader would have to decide which wins.</p>
     */
    public CompletableFuture<ServiceResult<DiplomacyBook.Declaration>> declare(
            final ResidentId actor,
            final NationId declarer,
            final Relation relation,
            final NationId target
    ) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(relation, "relation");

        return transaction(transaction -> {
            requireDiplomat(transaction, declarer, actor);
            requireExists(transaction, target);
            if (declarer.equals(target)) {
                throw new ChangeRefusedException(ChangeDenial.CANNOT_DECLARE_ON_SELF);
            }

            final DiplomacyBook.Declaration wanted =
                    new DiplomacyBook.Declaration(declarer, relation, target);
            if (transaction.relations().holds(wanted)) {
                throw new ChangeRefusedException(ChangeDenial.NOTHING_TO_CHANGE);
            }

            // The opposite kind, withdrawn in the same transaction so the two can never both stand.
            final DiplomacyBook.Declaration opposite = new DiplomacyBook.Declaration(
                    declarer,
                    relation == Relation.ALLY ? Relation.ENEMY : Relation.ALLY,
                    target);
            final boolean replaced = transaction.relations().withdraw(opposite);

            transaction.relations().declare(wanted, clock.instant());
            transaction.publish(
                    new DomainEvent.RelationDeclared(declarer, target, relation.name()),
                    "diplomacy:" + declarer.value());
            return new Applied(wanted, opposite, replaced);
        }).thenApply(result -> {
            // The cache is updated after the transaction commits, never inside it: a rolled-back
            // declaration that had already reached memory would leave protection granting access
            // the database never recorded.
            final java.util.Optional<Applied> applied = result.value();
            if (applied.isEmpty()) {
                return carryRefusal(result);
            }
            if (applied.get().replaced()) {
                book.withdraw(applied.get().opposite());
            }
            book.declare(applied.get().declaration());
            return ServiceResult.success(applied.get().declaration());
        });
    }

    /** Carries a refusal across a change of result type. */
    private static <T, R> ServiceResult<R> carryRefusal(final ServiceResult<T> result) {
        return result.denial()
                .<ServiceResult<R>>map(ServiceResult::refused)
                .orElseGet(() -> ServiceResult.nameRejected(result.nameProblems()));
    }

    /**
     * Runs work in a transaction, turning a refusal into a result rather than an exception.
     *
     * <p>The same shape as every other service here: a {@link ChangeRefusedException} thrown inside
     * rolls the transaction back <em>and</em> becomes the refusal the caller sees, so a refused
     * change cannot half-happen.</p>
     */
    private <T> CompletableFuture<ServiceResult<T>> transaction(final Work<T> work) {
        return store.<ServiceResult<T>>inTransaction(transaction ->
                        ServiceResult.success(work.perform(transaction)))
                .exceptionally(failure -> {
                    final Throwable cause =
                            failure instanceof java.util.concurrent.CompletionException
                                    ? failure.getCause()
                                    : failure;
                    if (cause instanceof ChangeRefusedException refused) {
                        return ServiceResult.refused(refused.denial());
                    }
                    throw failure instanceof java.util.concurrent.CompletionException completion
                            ? completion
                            : new java.util.concurrent.CompletionException(failure);
                });
    }

    @FunctionalInterface
    private interface Work<T> {
        T perform(CivicTransaction transaction);
    }

    /** Withdraws a declaration. Requires {@link Permission#MANAGE_DIPLOMACY}. */
    public CompletableFuture<ServiceResult<DiplomacyBook.Declaration>> withdraw(
            final ResidentId actor,
            final NationId declarer,
            final Relation relation,
            final NationId target
    ) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(relation, "relation");

        return transaction(transaction -> {
            requireDiplomat(transaction, declarer, actor);
            final DiplomacyBook.Declaration wanted =
                    new DiplomacyBook.Declaration(declarer, relation, target);
            if (!transaction.relations().withdraw(wanted)) {
                throw new ChangeRefusedException(ChangeDenial.NO_SUCH_DECLARATION);
            }
            transaction.publish(
                    new DomainEvent.RelationWithdrawn(declarer, target, relation.name()),
                    "diplomacy:" + declarer.value());
            return wanted;
        }).thenApply(result -> {
            result.value().ifPresent(book::withdraw);
            return result;
        });
    }

    /** Everything a nation has declared and everything declared about it. */
    public CompletableFuture<List<DiplomacyBook.Declaration>> involving(final NationId nation) {
        Objects.requireNonNull(nation, "nation");
        return store.inTransaction(transaction -> transaction.relations().involving(nation));
    }

    /** Drops a dissolved nation's declarations from the cache. The rows cascade in the database. */
    public void forget(final NationId nation) {
        book.forget(nation);
    }

    private static void requireDiplomat(
            final CivicTransaction transaction, final NationId nation, final ResidentId actor) {
        final Nation found = transaction.nations().find(Objects.requireNonNull(nation, "nation"))
                .orElseThrow(() -> new ChangeRefusedException(ChangeDenial.NATION_NOT_FOUND));
        final var roles = transaction.roles().find(
                net.riftbreaker.rifttowny.domain.org.OrganisationScope.NATION, found.id().value())
                .orElseThrow(() -> new ChangeRefusedException(ChangeDenial.MISSING_PERMISSION));

        final var theirTown = transaction.residents().find(actor)
                .flatMap(net.riftbreaker.rifttowny.domain.org.Resident::town)
                .orElse(null);
        if (!roles.allows(actor, Permission.MANAGE_DIPLOMACY, found.standingOf(actor, theirTown))) {
            throw new ChangeRefusedException(ChangeDenial.MISSING_PERMISSION);
        }
    }

    private static void requireExists(final CivicTransaction transaction, final NationId nation) {
        if (transaction.nations().find(Objects.requireNonNull(nation, "nation")).isEmpty()) {
            throw new ChangeRefusedException(ChangeDenial.NATION_NOT_FOUND);
        }
    }

    /** What the transaction produced, so the cache can be updated in the same two steps. */
    private record Applied(
            DiplomacyBook.Declaration declaration,
            DiplomacyBook.Declaration opposite,
            boolean replaced) {
    }
}
