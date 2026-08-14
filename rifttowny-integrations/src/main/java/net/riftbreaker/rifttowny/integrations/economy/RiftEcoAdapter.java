package net.riftbreaker.rifttowny.integrations.economy;

import net.riftbreaker.rifttowny.api.capability.Capability;
import net.riftbreaker.rifttowny.domain.bank.PlayerWallet;
import net.riftbreaker.rifttowny.integrations.IntegrationAdapter;

import java.util.Objects;

/**
 * Binds RiftEco, through the registry so a version mismatch is recorded rather than fatal.
 *
 * <p>{@link #bind()} does not merely find the service — it reads the default currency out of it.
 * That is the smallest call that proves the binding actually works: a reference stored without
 * touching the API would let the registry report {@code ACTIVE} for an integration that throws on
 * the first deposit somebody tries.</p>
 */
public final class RiftEcoAdapter implements IntegrationAdapter, PlayerWallet {

    /**
     * The bound wallet, or the one that refuses everything.
     *
     * <p>Volatile because it is written on the enabling thread when {@link #bind()} runs and read
     * from every thread a bank operation reaches. This class being the wallet, rather than handing
     * one out, is what lets it be given to {@code BankService} before binding has happened — the
     * service holds the adapter and always sees its current state.</p>
     */
    private volatile PlayerWallet wallet = PlayerWallet.absent();

    @Override
    public Capability capability() {
        return Capability.ECONOMY_RIFTECO;
    }

    @Override
    public Object bind() {
        final PlayerWallet bound = RiftEcoWallet.find().orElse(null);
        if (bound == null) {
            return null;
        }
        // Reading the currency is the proof. RiftEcoWallet.find() already did it, and holding the
        // result means /town bank can name the currency without another call.
        Objects.requireNonNull(bound.currency(), "currency");
        wallet = bound;
        return bound;
    }

    @Override
    public boolean available() {
        return wallet.available();
    }

    @Override
    public String currency() {
        return wallet.currency();
    }

    @Override
    public java.util.concurrent.CompletableFuture<java.util.Optional<
            net.riftbreaker.rifttowny.domain.bank.Money>> balanceOf(
            final net.riftbreaker.rifttowny.domain.org.ResidentId who) {
        return wallet.balanceOf(who);
    }

    @Override
    public java.util.concurrent.CompletableFuture<Boolean> take(
            final net.riftbreaker.rifttowny.domain.org.ResidentId who,
            final net.riftbreaker.rifttowny.domain.bank.Money amount) {
        return wallet.take(who, amount);
    }

    @Override
    public java.util.concurrent.CompletableFuture<Boolean> give(
            final net.riftbreaker.rifttowny.domain.org.ResidentId who,
            final net.riftbreaker.rifttowny.domain.bank.Money amount) {
        return wallet.give(who, amount);
    }

    @Override
    public String describe(final Object bound) {
        return bound instanceof PlayerWallet economy
                ? "player wallets in " + economy.currency()
                : "bound";
    }
}
