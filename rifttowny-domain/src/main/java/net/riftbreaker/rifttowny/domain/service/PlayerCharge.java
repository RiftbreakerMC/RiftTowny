package net.riftbreaker.rifttowny.domain.service;

import net.riftbreaker.rifttowny.domain.bank.Money;
import net.riftbreaker.rifttowny.domain.bank.PlayerWallet;
import net.riftbreaker.rifttowny.domain.org.ChangeDenial;
import net.riftbreaker.rifttowny.domain.org.ResidentId;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Taking a player's money for something civic, and giving it back if the civic part refuses.
 *
 * <p>Four things charge a player rather than a treasury — founding a town, taking a plot, rebuilding
 * a ruin, travelling to a spawn — and every one of them has the same two-system problem: the wallet
 * belongs to another plugin and cannot join our transaction. Solving it once here means the four
 * cannot solve it four subtly different ways.</p>
 *
 * <p><strong>The order is deliberate and is the whole of the safety argument.</strong> The money
 * leaves the player first; only then does the civic work run. The other way round would hand
 * somebody a town, a plot or a rebuilt settlement and then discover they could not pay for it. If
 * the civic work refuses, the money goes straight back.</p>
 *
 * <p>Not a two-phase commit. A crash between the wallet taking and the civic work committing loses
 * that payment, and the loss is in the safe direction: a player is out of pocket rather than a
 * server issuing something for free. The window is one transaction wide.</p>
 */
final class PlayerCharge {

    private PlayerCharge() {
    }

    /**
     * Runs civic work, having first taken its price.
     *
     * <p>A price of zero skips the wallet entirely, so an unpriced server never touches the economy
     * and never needs one installed.</p>
     *
     * @param work the civic half. Must be a single transaction, because a refusal is what triggers
     *        the refund and a partial commit would be refunded while still having happened
     */
    static <T> CompletableFuture<ServiceResult<T>> charging(
            final PlayerWallet wallet,
            final ResidentId who,
            final Money price,
            final Supplier<CompletableFuture<ServiceResult<T>>> work
    ) {
        Objects.requireNonNull(wallet, "wallet");
        Objects.requireNonNull(who, "who");
        Objects.requireNonNull(price, "price");
        Objects.requireNonNull(work, "work");

        if (price.isZero()) {
            return work.get();
        }
        if (!wallet.available()) {
            // Priced, but nothing can pay. Refused rather than waived: a server that configured a
            // price and lost its economy plugin should find the action blocked, not free.
            return CompletableFuture.completedFuture(ServiceResult.refused(ChangeDenial.NO_ECONOMY));
        }

        return wallet.take(who, price).thenCompose(taken -> {
            if (!taken) {
                return CompletableFuture.completedFuture(
                        ServiceResult.<T>refused(ChangeDenial.INSUFFICIENT_FUNDS));
            }
            return work.get().thenCompose(result -> result.succeeded()
                    ? CompletableFuture.completedFuture(result)
                    : wallet.give(who, price).thenApply(ignored -> result));
        });
    }
}
