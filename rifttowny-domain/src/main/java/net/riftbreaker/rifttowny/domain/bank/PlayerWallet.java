package net.riftbreaker.rifttowny.domain.bank;

import net.riftbreaker.rifttowny.domain.org.ResidentId;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * The player's own money, which RiftTowny does not own.
 *
 * <p>The civic ledger is ours: an organisation's balance and every movement of it live in
 * {@code rt_organisation_balance} and {@code rt_bank_ledger}, and work with no economy plugin
 * installed. A <em>player's</em> balance is not ours and never will be — it is the economy plugin's,
 * and inventing a second one would give a server two answers to "how much money do I have".</p>
 *
 * <p>So this is the whole seam. Everything that moves money between a person and an organisation
 * goes through these four methods, and nothing else in RiftTowny knows what an economy plugin
 * is.</p>
 *
 * <p><strong>No implementation ships.</strong> RiftEco is the intended provider and its API is not
 * available to build against here, so the only implementation is {@link #absent()}, which refuses
 * everything and says why. That is deliberate: a stub that pretended to move money would produce a
 * town bank full of money nobody paid, and the failure would surface as a server-wide duplication
 * bug months later. The contract a real adapter must satisfy is in
 * {@code INTEGRATION_CONTRACTS.md}.</p>
 */
public interface PlayerWallet {

    /** Whether an economy is actually present. False for {@link #absent()}. */
    boolean available();

    /** The currency organisations bank in. */
    String currency();

    /** What this player has, or empty if the provider cannot say. */
    CompletableFuture<Optional<Money>> balanceOf(ResidentId who);

    /**
     * Takes money from a player.
     *
     * @return true if it was taken. False means they did not have it — not that anything failed
     */
    CompletableFuture<Boolean> take(ResidentId who, Money amount);

    /** Gives money to a player. */
    CompletableFuture<Boolean> give(ResidentId who, Money amount);

    /**
     * The one that refuses everything.
     *
     * <p>Used when no economy plugin is installed, and it is the only implementation that exists
     * today. Every civic-side operation still works: a town has a balance, a ledger and a history.
     * What cannot happen is money crossing between a player and a town, because there is nothing on
     * the player's side to cross to.</p>
     */
    static PlayerWallet absent() {
        return new PlayerWallet() {

            @Override
            public boolean available() {
                return false;
            }

            @Override
            public String currency() {
                return "coins";
            }

            @Override
            public CompletableFuture<Optional<Money>> balanceOf(final ResidentId who) {
                return CompletableFuture.completedFuture(Optional.empty());
            }

            @Override
            public CompletableFuture<Boolean> take(final ResidentId who, final Money amount) {
                return CompletableFuture.completedFuture(false);
            }

            @Override
            public CompletableFuture<Boolean> give(final ResidentId who, final Money amount) {
                return CompletableFuture.completedFuture(false);
            }
        };
    }
}
