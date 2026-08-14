package net.riftbreaker.rifttowny.domain.service;

import net.riftbreaker.rifttowny.api.ChunkKey;
import net.riftbreaker.rifttowny.domain.event.DomainEvent;
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
import net.riftbreaker.rifttowny.domain.territory.PlotType;
import net.riftbreaker.rifttowny.domain.territory.TerritoryIndex;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Who holds a chunk inside a town, and what it is for.
 *
 * <p>A plot is the same chunk the town already claimed, answered from a different angle. Holding one
 * is what turns {@link net.riftbreaker.rifttowny.domain.flag.Relationship#RESIDENT} from a rung
 * nobody could reach into the one that lets a town give a member authority over their own square
 * without giving it over everybody else's.</p>
 *
 * <p>Two people can change a plot: the town, through {@link Permission#MANAGE_PLOTS}, and whoever
 * holds it. A holder may set what their own plot is for and give it back; they may not take one
 * from somebody else, and they may not hold a plot in a town they do not belong to.</p>
 *
 * <p>Selling and renting are {@code RT-MOD-PROPERTY}'s, and need an economy. Nothing here charges
 * for anything: a plot is taken by asking, and the only limit is that somebody else has not taken
 * it first.</p>
 */
public final class PlotService {

    private final CivicStore store;
    private final Clock clock;
    private final TerritoryIndex index;
    private final net.riftbreaker.rifttowny.domain.bank.CivicPrices prices;
    private final net.riftbreaker.rifttowny.domain.bank.PlayerWallet wallet;

    /**
     * @param index updated after each change, because the protection check reads plot ownership from
     *        it on every block a player touches
     */
    public PlotService(
            final CivicStore store, final Clock clock, final TerritoryIndex index) {
        this(store, clock, index,
                net.riftbreaker.rifttowny.domain.bank.CivicPrices.free(),
                net.riftbreaker.rifttowny.domain.bank.PlayerWallet.absent());
    }

    /** @param prices what a plot costs the resident taking it, paid to their town */
    public PlotService(
            final CivicStore store,
            final Clock clock,
            final TerritoryIndex index,
            final net.riftbreaker.rifttowny.domain.bank.CivicPrices prices,
            final net.riftbreaker.rifttowny.domain.bank.PlayerWallet wallet
    ) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.index = Objects.requireNonNull(index, "index");
        this.prices = Objects.requireNonNull(prices, "prices");
        this.wallet = Objects.requireNonNull(wallet, "wallet");
    }

    /**
     * Takes an unheld plot.
     *
     * <p>The actor must be a resident of the town that owns the chunk. Everything else about who may
     * take what — a price, a limit, a queue — belongs to {@code RT-MOD-PROPERTY}; this is the part
     * that has to exist for any of that to have something to sell.</p>
     */
    public CompletableFuture<ServiceResult<Claim>> take(
            final ResidentId actor, final ChunkKey chunk) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(chunk, "chunk");

        // Paid by the resident and received by their town, so unlike a founding fee it stays in the
        // economy. The credit happens inside the transaction below, with the plot itself.
        return PlayerCharge.charging(wallet, actor, prices.plot(wallet.currency()),
                () -> takeInTransaction(actor, chunk));
    }

    private CompletableFuture<ServiceResult<Claim>> takeInTransaction(
            final ResidentId actor, final ChunkKey chunk) {
        return transaction(transaction -> {
            final Claim claim = claimAt(transaction, chunk);
            final Town town = town(transaction, claim.town());
            if (!town.hasResident(actor)) {
                throw new ChangeRefusedException(ChangeDenial.NOT_A_RESIDENT_OF_THIS_TOWN);
            }
            if (claim.isHeldBy(actor)) {
                throw new ChangeRefusedException(ChangeDenial.ALREADY_HOLD_THIS_PLOT);
            }
            if (claim.isHeld()) {
                throw new ChangeRefusedException(ChangeDenial.PLOT_ALREADY_HELD);
            }

            final Claim held = claim.heldBy(actor);
            transaction.claims().updatePlot(held);

            // The town receives what the resident paid, in the same transaction as the plot they
            // paid for. A price taken from a player that never arrived anywhere would be money the
            // server destroyed by accident.
            final var price = prices.plot(wallet.currency());
            if (!price.isZero()) {
                final var before = transaction.bank()
                        .balance(town.bankAccountId(), price.currency())
                        .orElseGet(() -> net.riftbreaker.rifttowny.domain.bank.Money
                                .zero(price.currency()));
                transaction.bank().record(
                        net.riftbreaker.rifttowny.domain.bank.LedgerEntry.of(
                                town.bankAccountId(), price, before.plus(price),
                                net.riftbreaker.rifttowny.domain.bank.LedgerEntry.Reason.TRANSFER_IN,
                                actor, "plot " + chunk.chunkX() + ", " + chunk.chunkZ(),
                                clock.instant()),
                        clock.instant());
            }

            transaction.publish(
                    new DomainEvent.PlotHeld(claim.town(), chunk.toString(), actor),
                    correlation(claim.town()));
            return held;
        }).thenApply(this::remember);
    }

    /**
     * Gives a plot back to the town.
     *
     * <p>Its holder may always do this. Somebody with {@link Permission#MANAGE_PLOTS} may do it to
     * anybody's plot, which is how a town reclaims the square of a member who has stopped
     * playing.</p>
     */
    public CompletableFuture<ServiceResult<Claim>> release(
            final ResidentId actor, final ChunkKey chunk) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(chunk, "chunk");

        return transaction(transaction -> {
            final Claim claim = claimAt(transaction, chunk);
            if (!claim.isHeld()) {
                throw new ChangeRefusedException(ChangeDenial.PLOT_NOT_HELD);
            }
            if (!claim.isHeldBy(actor)) {
                requirePermission(transaction, claim.town(), actor, Permission.MANAGE_PLOTS);
            }

            final Claim released = claim.released();
            transaction.claims().updatePlot(released);
            transaction.publish(
                    new DomainEvent.PlotReleased(
                            claim.town(), chunk.toString(), claim.owner(), actor),
                    correlation(claim.town()));
            return released;
        }).thenApply(this::remember);
    }

    /**
     * Says what a plot is for.
     *
     * <p>Its holder may set their own; anybody else needs {@link Permission#MANAGE_PLOTS}. A plot
     * type never changes the town's shape — that is {@code ClaimKind}'s job — so this cannot sever a
     * town however it is used.</p>
     */
    public CompletableFuture<ServiceResult<Claim>> setType(
            final ResidentId actor, final ChunkKey chunk, final PlotType type) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(chunk, "chunk");
        Objects.requireNonNull(type, "type");

        return transaction(transaction -> {
            final Claim claim = claimAt(transaction, chunk);
            if (!claim.isHeldBy(actor)) {
                requirePermission(transaction, claim.town(), actor, Permission.MANAGE_PLOTS);
            }
            if (claim.type() == type) {
                throw new ChangeRefusedException(ChangeDenial.PLOT_ALREADY_THAT_TYPE);
            }

            final Claim retyped = claim.asType(type);
            transaction.claims().updatePlot(retyped);
            transaction.publish(
                    new DomainEvent.PlotTypeChanged(claim.town(), chunk.toString(), type.name()),
                    correlation(claim.town()));
            return retyped;
        }).thenApply(this::remember);
    }

    /** Every plot this resident holds. */
    public CompletableFuture<List<Claim>> heldBy(final ResidentId who) {
        Objects.requireNonNull(who, "who");
        return store.inTransaction(transaction -> transaction.claims().heldBy(who));
    }

    /** The plot at a position, answered from memory. */
    public java.util.Optional<Claim> at(final ChunkKey chunk) {
        return index.at(chunk);
    }

    /**
     * Returns every plot a departing resident held in one town.
     *
     * <p>Called when somebody leaves or is removed. A plot is authority over a square inside a town,
     * and somebody who is no longer a member of that town should not keep it — the same rule as
     * their roles, for the same reason.</p>
     *
     * @return how many plots went back
     */
    static int releaseHeldPlots(
            final CivicTransaction transaction,
            final TerritoryIndex index,
            final TownId town,
            final ResidentId who
    ) {
        final int released = transaction.claims().releaseAllHeldBy(who, town);
        if (released > 0) {
            // The index is the copy the protection listener reads, so it has to follow. Re-read
            // rather than mutated in place: the rows were changed by a bulk update, and rebuilding
            // the entries from them is the only way to be sure the two agree.
            transaction.claims().of(town).forEach(index::put);
        }
        return released;
    }

    // --- internals -----------------------------------------------------------------------------

    private ServiceResult<Claim> remember(final ServiceResult<Claim> result) {
        // After the commit, never inside it. A rolled-back change that had already reached the
        // index would have the server enforcing a plot the database does not have.
        result.value().ifPresent(index::put);
        return result;
    }

    private static Claim claimAt(final CivicTransaction transaction, final ChunkKey chunk) {
        return transaction.claims().at(chunk)
                .orElseThrow(() -> new ChangeRefusedException(ChangeDenial.CHUNK_NOT_CLAIMED));
    }

    private static Town town(final CivicTransaction transaction, final TownId townId) {
        return transaction.towns().find(townId)
                .orElseThrow(() -> new ChangeRefusedException(ChangeDenial.TOWN_NOT_FOUND));
    }

    private static void requirePermission(
            final CivicTransaction transaction,
            final TownId townId,
            final ResidentId actor,
            final Permission permission
    ) {
        final Town town = town(transaction, townId);
        final RoleBook book = transaction.roles()
                .find(OrganisationScope.TOWN, townId.value())
                .orElseThrow(() -> new ChangeRefusedException(ChangeDenial.ROLE_NOT_FOUND));
        if (!book.allows(actor, permission, town.standingOf(actor))) {
            throw new ChangeRefusedException(ChangeDenial.MISSING_PERMISSION);
        }
    }

    private static String correlation(final TownId town) {
        return "plot:" + town.value();
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
