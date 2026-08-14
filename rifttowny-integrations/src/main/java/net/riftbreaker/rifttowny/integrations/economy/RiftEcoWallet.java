package net.riftbreaker.rifttowny.integrations.economy;

import net.riftbreaker.eco.api.AccountRef;
import net.riftbreaker.eco.api.CurrencyDefinition;
import net.riftbreaker.eco.api.CurrencyKey;
import net.riftbreaker.eco.api.RiftEcoProvider;
import net.riftbreaker.eco.api.RiftEcoService;
import net.riftbreaker.eco.api.TransactionReceipt;
import net.riftbreaker.rifttowny.domain.bank.Money;
import net.riftbreaker.rifttowny.domain.bank.PlayerWallet;
import net.riftbreaker.rifttowny.domain.org.ResidentId;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * A player's money, as RiftEco holds it.
 *
 * <p>Written against {@code net.riftbreaker.eco.api} read from source, not guessed at. The four
 * methods map onto {@code RiftEcoService.balance}, {@code withdraw} and {@code deposit}; everything
 * else RiftEco offers — banks, fees, rewards, ore exchange, its own Towny bridge — is deliberately
 * untouched, because the only thing RiftTowny needs from an economy is a player's wallet.</p>
 *
 * <p><strong>Never RiftEco's bank accounts for civic money.</strong> RiftEco has an
 * {@code AccountType.TOWN} and a whole Towny bridge, and using them would put a town's treasury in
 * another plugin's storage with another plugin's rules — the balance would be outside our
 * transaction, the ledger would have two sources of truth, and disbanding a town would depend on a
 * second plugin agreeing. The civic ledger stays ours; this seam is one wallet, one direction at a
 * time.</p>
 */
public final class RiftEcoWallet implements PlayerWallet {

    private final RiftEcoService economy;
    private final CurrencyKey currencyKey;
    private final String currencyName;

    private RiftEcoWallet(
            final RiftEcoService economy,
            final CurrencyKey currencyKey,
            final String currencyName
    ) {
        this.economy = economy;
        this.currencyKey = currencyKey;
        this.currencyName = currencyName;
    }

    /**
     * Finds RiftEco, or does not.
     *
     * <p>Empty when the plugin is absent, and — deliberately — also when anything about loading it
     * throws. A version mismatch is a {@code NoSuchMethodError} rather than an exception, and
     * catching {@link Throwable} here is what keeps that from taking the server down with it. The
     * cost of being wrong in this direction is that deposits refuse; the cost of the other is a
     * plugin that will not enable.</p>
     */
    public static Optional<PlayerWallet> find() {
        try {
            return RiftEcoProvider.find().map(economy -> {
                final CurrencyDefinition currency = economy.defaultCurrency();
                return new RiftEcoWallet(economy, currency.key(), currency.pluralName());
            });
        } catch (final Throwable unavailable) {
            return Optional.empty();
        }
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public String currency() {
        return currencyName;
    }

    @Override
    public CompletableFuture<Optional<Money>> balanceOf(final ResidentId who) {
        Objects.requireNonNull(who, "who");
        return economy.balance(AccountRef.player(who.value()), currencyKey)
                .thenApply(amount -> Optional.of(Money.of(amount, currencyName)))
                .exceptionally(failure -> Optional.empty());
    }

    @Override
    public CompletableFuture<Boolean> take(final ResidentId who, final Money amount) {
        Objects.requireNonNull(who, "who");
        Objects.requireNonNull(amount, "amount");
        return economy.withdraw(
                        AccountRef.player(who.value()),
                        moneyFor(amount),
                        "RiftTowny: civic deposit")
                .thenApply(TransactionReceipt::successful)
                // A failed future is not a taken payment. Reported as "did not happen", which is the
                // safe reading: the civic side then credits nothing.
                .exceptionally(failure -> false);
    }

    @Override
    public CompletableFuture<Boolean> give(final ResidentId who, final Money amount) {
        Objects.requireNonNull(who, "who");
        Objects.requireNonNull(amount, "amount");
        return economy.deposit(
                        AccountRef.player(who.value()),
                        moneyFor(amount),
                        "RiftTowny: civic withdrawal")
                .thenApply(TransactionReceipt::successful)
                // Here the safe reading is the other way: a false makes BankService put the money
                // back in the treasury, so a payment that did happen and reported false costs the
                // server money. RiftEco reports a status rather than throwing for an ordinary
                // refusal, so a thrown failure really is a failure.
                .exceptionally(failure -> false);
    }

    /**
     * Our amount as RiftEco's.
     *
     * <p>Both are {@link java.math.BigDecimal}, so nothing rounds. That is the property
     * {@code INTEGRATION_CONTRACTS.md} §2.7 asks of an adapter, and it holds here by construction
     * rather than by care.</p>
     */
    private net.riftbreaker.eco.api.Money moneyFor(final Money amount) {
        return new net.riftbreaker.eco.api.Money(currencyKey, amount.amount());
    }
}
