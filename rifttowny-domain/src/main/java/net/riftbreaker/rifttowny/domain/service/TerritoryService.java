package net.riftbreaker.rifttowny.domain.service;

import net.riftbreaker.rifttowny.api.ChunkKey;
import net.riftbreaker.rifttowny.domain.org.ChangeDenial;
import net.riftbreaker.rifttowny.domain.org.OrganisationScope;
import net.riftbreaker.rifttowny.domain.org.Outcome;
import net.riftbreaker.rifttowny.domain.org.ResidentId;
import net.riftbreaker.rifttowny.domain.org.Town;
import net.riftbreaker.rifttowny.domain.org.TownId;
import net.riftbreaker.rifttowny.domain.role.Permission;
import net.riftbreaker.rifttowny.domain.role.RoleBook;
import net.riftbreaker.rifttowny.domain.store.ChangeRefusedException;
import net.riftbreaker.rifttowny.domain.store.CivicStore;
import net.riftbreaker.rifttowny.domain.store.CivicTransaction;
import net.riftbreaker.rifttowny.domain.territory.Claim;
import net.riftbreaker.rifttowny.domain.territory.ClaimKind;
import net.riftbreaker.rifttowny.domain.territory.ClaimPreview;
import net.riftbreaker.rifttowny.domain.territory.TownClaims;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Claiming and releasing territory.
 *
 * <p>The shape rules — contiguity, the homeblock, outposts — belong to {@link TownClaims}. What
 * this adds is the part that needs the database: whether somebody else already owns the chunk, and
 * whether the actor is allowed to do this at all.</p>
 *
 * <p>Ownership across towns is checked <em>inside</em> the transaction. The unique constraint on
 * {@code (world_id, chunk_x, chunk_z)} is the real guard against two towns claiming one chunk in
 * the same instant; the check here exists to turn that into a sentence rather than a constraint
 * violation, and would be a lie if it ran before the transaction opened.</p>
 */
public final class TerritoryService {

    private final CivicStore store;
    private final Clock clock;
    private final net.riftbreaker.rifttowny.domain.territory.TerritoryIndex index;

    /**
     * @param index kept current by this service, and the only thing a protection listener is
     *        allowed to consult. Updated <em>after</em> the transaction commits, never inside it: a
     *        rolled-back claim that had already reached the cache would leave the server believing
     *        in territory the database does not have
     */
    public TerritoryService(
            final CivicStore store,
            final Clock clock,
            final net.riftbreaker.rifttowny.domain.territory.TerritoryIndex index
    ) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.index = Objects.requireNonNull(index, "index");
    }

    /**
     * Fills the in-memory index from storage.
     *
     * <p>Called once at enable, before the server accepts players. Everything after that is kept
     * current incrementally by the methods below.</p>
     */
    public CompletableFuture<Integer> loadIndex() {
        return store.inTransaction(transaction -> {
            final java.util.List<Claim> loaded = transaction.claims().all();
            index.replaceAll(loaded);
            return loaded.size();
        });
    }

    /** The index, for listeners and for {@code /rifttowny status}. */
    public net.riftbreaker.rifttowny.domain.territory.TerritoryIndex index() {
        return index;
    }

    /** Claims a chunk for the actor's town. Requires {@link Permission#CLAIM_LAND}. */
    public CompletableFuture<ServiceResult<Claim>> claim(
            final ResidentId actor, final TownId townId, final ChunkKey chunk, final ClaimKind kind) {
        Objects.requireNonNull(chunk, "chunk");
        Objects.requireNonNull(kind, "kind");

        return transaction(townId, actor, Permission.CLAIM_LAND, (transaction, town) -> {
            final Optional<Claim> owner = transaction.claims().at(chunk);
            if (owner.isPresent()) {
                throw new ChangeRefusedException(owner.get().town().equals(townId)
                        ? ChangeDenial.CHUNK_ALREADY_CLAIMED
                        : ChangeDenial.CHUNK_OWNED_BY_ANOTHER_TOWN);
            }

            final TownClaims claims = load(transaction, townId);
            final Outcome<TownClaims> outcome = claims.claim(chunk, kind, clock.instant());
            final TownClaims updated = require(outcome);

            // Only the new chunk is written. A town's territory can be thousands of rows, and
            // rewriting all of them to add one would turn a routine command into a table scan.
            final Claim created = updated.at(chunk).orElseThrow();
            transaction.claims().insert(created);
            transaction.publishAll(outcome.events(), correlation("claim", townId));
            return created;
        }).thenApply(result -> {
            result.value().ifPresent(index::put);
            return result;
        });
    }

    /** Releases a chunk. Requires {@link Permission#UNCLAIM_LAND}. */
    public CompletableFuture<ServiceResult<ChunkKey>> unclaim(
            final ResidentId actor, final TownId townId, final ChunkKey chunk) {
        Objects.requireNonNull(chunk, "chunk");

        return transaction(townId, actor, Permission.UNCLAIM_LAND, (transaction, town) -> {
            final TownClaims claims = load(transaction, townId);
            final Outcome<TownClaims> outcome = claims.unclaim(chunk);
            require(outcome);

            transaction.claims().delete(chunk);
            transaction.publishAll(outcome.events(), correlation("unclaim", townId));
            return chunk;
        }).thenApply(result -> {
            result.value().ifPresent(index::remove);
            return result;
        });
    }

    /**
     * Moves the homeblock to another chunk the town owns. Requires {@link Permission#SET_HOMEBLOCK}.
     *
     * <p>Returns both chunks rather than just the new one, because both changed and the caller — the
     * command, the index, a map renderer — needs to know which chunk stopped being the homeblock.</p>
     */
    public CompletableFuture<ServiceResult<HomeblockMove>> moveHomeblock(
            final ResidentId actor, final TownId townId, final ChunkKey chunk) {
        Objects.requireNonNull(chunk, "chunk");

        return transaction(townId, actor, Permission.SET_HOMEBLOCK, (transaction, town) -> {
            final TownClaims claims = load(transaction, townId);
            final Optional<Claim> previous = claims.homeblock();
            final Outcome<TownClaims> outcome = claims.moveHomeblock(chunk);
            final TownClaims updated = require(outcome);

            // Both kinds change together. A moment with two homeblocks, or none, would make the
            // next unclaim refuse or permit the wrong thing.
            previous.ifPresent(old ->
                    transaction.claims().updateKind(old.chunk(), ClaimKind.ORDINARY));
            transaction.claims().updateKind(chunk, ClaimKind.HOMEBLOCK);
            transaction.publishAll(outcome.events(), correlation("homeblock", townId));

            return new HomeblockMove(
                    previous.map(Claim::chunk).orElse(null),
                    chunk,
                    previous.map(old -> updated.at(old.chunk()).orElseThrow()).orElse(null),
                    updated.at(chunk).orElseThrow());
        }).thenApply(result -> {
            // Both entries refreshed. Ownership did not move, but a stale kind would make the old
            // homeblock still read as one, and the rule that it is unclaimed last would then guard
            // the wrong chunk.
            result.value().ifPresent(move -> {
                if (move.demoted() != null) {
                    index.put(move.demoted());
                }
                index.put(move.promoted());
            });
            return result;
        });
    }

    /**
     * The two chunks a homeblock move touched.
     *
     * @param previousChunk the chunk that stopped being the homeblock, or null if the town had none
     * @param newChunk the chunk that became it
     * @param demoted the previous claim in its new ordinary form, or null
     * @param promoted the new homeblock claim
     */
    public record HomeblockMove(
            ChunkKey previousChunk, ChunkKey newChunk, Claim demoted, Claim promoted) {
    }

    /**
     * What claiming would do, without doing it.
     *
     * <p>Runs the same rules the real call does, including the cross-town ownership check, so the
     * answer a player is shown is the answer they will get.</p>
     */
    public CompletableFuture<ClaimPreview> previewClaim(
            final TownId townId, final ChunkKey chunk, final ClaimKind kind) {
        Objects.requireNonNull(townId, "townId");
        Objects.requireNonNull(chunk, "chunk");

        return store.inTransaction(transaction -> {
            final TownClaims claims = load(transaction, townId);
            final Optional<Claim> owner = transaction.claims().at(chunk);
            if (owner.isPresent() && !owner.get().town().equals(townId)) {
                return new ClaimPreview(chunk, kind, false,
                        ChangeDenial.CHUNK_OWNED_BY_ANOTHER_TOWN,
                        claims.size(), claims.size(), claims.touchesTown(chunk));
            }
            return claims.previewClaim(chunk, kind);
        });
    }

    /** What unclaiming would do, without doing it. */
    public CompletableFuture<ClaimPreview> previewUnclaim(
            final TownId townId, final ChunkKey chunk) {
        Objects.requireNonNull(townId, "townId");
        Objects.requireNonNull(chunk, "chunk");
        return store.inTransaction(transaction -> load(transaction, townId).previewUnclaim(chunk));
    }

    /** Who owns a chunk, if anybody. The lookup every protection check will eventually cache. */
    public CompletableFuture<Optional<Claim>> ownerOf(final ChunkKey chunk) {
        Objects.requireNonNull(chunk, "chunk");
        return store.inTransaction(transaction -> transaction.claims().at(chunk));
    }

    /** A town's whole territory. */
    public CompletableFuture<TownClaims> territoryOf(final TownId townId) {
        Objects.requireNonNull(townId, "townId");
        return store.inTransaction(transaction -> load(transaction, townId));
    }

    private static TownClaims load(final CivicTransaction transaction, final TownId townId) {
        return TownClaims.restore(townId, transaction.claims().of(townId));
    }

    private static <T> T require(final Outcome<T> outcome) {
        return outcome.value().orElseThrow(() ->
                new ChangeRefusedException(outcome.denial().orElseThrow()));
    }

    private static String correlation(final String action, final TownId town) {
        return action + ':' + town.value();
    }

    private <T> CompletableFuture<ServiceResult<T>> transaction(
            final TownId townId,
            final ResidentId actor,
            final Permission required,
            final Work<T> work
    ) {
        Objects.requireNonNull(townId, "townId");
        Objects.requireNonNull(actor, "actor");

        return store.<ServiceResult<T>>inTransaction(transaction -> {
            final Town town = transaction.towns().find(townId)
                    .orElseThrow(() -> new ChangeRefusedException(ChangeDenial.TOWN_NOT_FOUND));
            final RoleBook book = transaction.roles()
                    .find(OrganisationScope.TOWN, townId.value())
                    .orElseThrow(() -> new ChangeRefusedException(ChangeDenial.ROLE_NOT_FOUND));
            if (!book.allows(actor, required, town.standingOf(actor))) {
                throw new ChangeRefusedException(ChangeDenial.MISSING_PERMISSION);
            }
            return ServiceResult.success(work.perform(transaction, town));
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
        T perform(CivicTransaction transaction, Town town);
    }
}
