package net.riftbreaker.rifttowny.storage;

import net.riftbreaker.rifttowny.api.ChunkKey;
import net.riftbreaker.rifttowny.domain.bank.CivicPrices;
import net.riftbreaker.rifttowny.domain.bank.LedgerEntry;
import net.riftbreaker.rifttowny.domain.bank.Money;
import net.riftbreaker.rifttowny.domain.bank.PlayerWallet;
import net.riftbreaker.rifttowny.domain.civic.CivicCache;
import net.riftbreaker.rifttowny.domain.naming.NamePolicy;
import net.riftbreaker.rifttowny.domain.org.ChangeDenial;
import net.riftbreaker.rifttowny.domain.org.Resident;
import net.riftbreaker.rifttowny.domain.org.ResidentId;
import net.riftbreaker.rifttowny.domain.org.Town;
import net.riftbreaker.rifttowny.domain.service.BankService;
import net.riftbreaker.rifttowny.domain.service.CivicCacheService;
import net.riftbreaker.rifttowny.domain.service.TerritoryService;
import net.riftbreaker.rifttowny.domain.service.TownService;
import net.riftbreaker.rifttowny.domain.territory.ClaimKind;
import net.riftbreaker.rifttowny.domain.territory.TerritoryIndex;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Territory costs money, and the charge and the claim are one transaction.
 *
 * <p>The property worth protecting is that a town can never end up owning a chunk it did not pay
 * for, or paying for one it does not own.
 */
class ClaimPriceTest extends SqliteFixture {

    private static final Instant NOW = Instant.parse("2026-08-13T09:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String CURRENCY = "coins";

    private static final UUID WORLD = UUID.randomUUID();
    private static final ChunkKey HOME = new ChunkKey(WORLD, 0, 0);
    private static final ChunkKey NEXT = new ChunkKey(WORLD, 1, 0);

    private static final ResidentId MAYOR = ResidentId.of(UUID.randomUUID());

    private final TerritoryIndex index = TerritoryIndex.empty();

    private JdbcCivicStore store;
    private TownService towns;
    private BankService bank;
    private JdbcResidentRepository residents;

    @BeforeEach
    void createServices() {
        store = new JdbcCivicStore(database, DIRECT, CLOCK);
        towns = new TownService(store, NamePolicy.defaults(), CLOCK, index,
                new CivicCacheService(store, CivicCache.empty(), warning -> { }));
        bank = new BankService(store, CLOCK, new NamedCurrencyWallet());
        residents = new JdbcResidentRepository(database, DIRECT);
    }

    private TerritoryService territoryPriced(final String claim, final String refund) {
        return new TerritoryService(store, CLOCK, index,
                new CivicPrices(BigDecimal.ZERO, new BigDecimal(claim), new BigDecimal(refund),
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO),
                new NamedCurrencyWallet());
    }

    private Town riftholm() {
        residents.save(Resident.newcomer(MAYOR, "Mayor", NOW)).join();
        return towns.found(MAYOR, "Mayor", "Riftholm").join().value().orElseThrow();
    }

    private static Money coins(final String amount) {
        return Money.of(new BigDecimal(amount), CURRENCY);
    }

    @Test
    @DisplayName("claiming charges the treasury")
    void claimingCharges() {
        final Town town = riftholm();
        bank.pay(town.id(), coins("100"), LedgerEntry.Reason.ADMIN, null).join();
        final TerritoryService territory = territoryPriced("30", "0");

        assertThat(territory.claim(MAYOR, town.id(), HOME, ClaimKind.HOMEBLOCK).join().succeeded())
                .isTrue();

        assertThat(bank.balanceOf(town.id()).join()).isEqualTo(coins("70"));
        assertThat(bank.historyOf(town.id(), 5).join().getFirst().reason())
                .isEqualTo(LedgerEntry.Reason.CLAIM);
    }

    @Test
    @DisplayName("a town that cannot afford it does not get the chunk")
    void unaffordableClaimIsRefused() {
        final Town town = riftholm();
        bank.pay(town.id(), coins("10"), LedgerEntry.Reason.ADMIN, null).join();
        final TerritoryService territory = territoryPriced("30", "0");

        assertThat(territory.claim(MAYOR, town.id(), HOME, ClaimKind.HOMEBLOCK).join().denial())
                .contains(ChangeDenial.INSUFFICIENT_CIVIC_FUNDS);

        assertThat(index.at(HOME))
                .as("the charge and the claim are one transaction, so neither happened")
                .isEmpty();
        assertThat(store.inTransaction(t -> t.claims().at(HOME)).join()).isEmpty();
        assertThat(bank.balanceOf(town.id()).join()).isEqualTo(coins("10"));
    }

    @Test
    @DisplayName("a refused claim is not charged for either")
    void illegalClaimIsNotCharged() {
        final Town town = riftholm();
        bank.pay(town.id(), coins("100"), LedgerEntry.Reason.ADMIN, null).join();
        final TerritoryService territory = territoryPriced("30", "0");
        territory.claim(MAYOR, town.id(), HOME, ClaimKind.HOMEBLOCK).join();

        // Two homeblocks is refused by the shape rules, which run before the charge.
        assertThat(territory.claim(MAYOR, town.id(), NEXT, ClaimKind.HOMEBLOCK).join().succeeded())
                .isFalse();

        assertThat(bank.balanceOf(town.id()))
                .as("being told a claim is illegal is more useful than being told it is unaffordable")
                .isCompletedWithValue(coins("70"));
    }

    @Test
    @DisplayName("releasing a chunk refunds what the server configured")
    void unclaimingRefunds() {
        final Town town = riftholm();
        bank.pay(town.id(), coins("100"), LedgerEntry.Reason.ADMIN, null).join();
        final TerritoryService territory = territoryPriced("30", "20");
        territory.claim(MAYOR, town.id(), HOME, ClaimKind.HOMEBLOCK).join();
        territory.claim(MAYOR, town.id(), NEXT, ClaimKind.ORDINARY).join();

        territory.unclaim(MAYOR, town.id(), NEXT).join();

        assertThat(bank.balanceOf(town.id()).join())
                .as("100 less two claims of 30, plus one refund of 20")
                .isEqualTo(coins("60"));
        assertThat(bank.historyOf(town.id(), 1).join().getFirst().reason())
                .isEqualTo(LedgerEntry.Reason.UNCLAIM_REFUND);
    }

    @Test
    @DisplayName("with no prices configured nothing is charged and no ledger entry is written")
    void freeByDefault() {
        final Town town = riftholm();
        final TerritoryService territory =
                new TerritoryService(store, CLOCK, index, CivicPrices.free(),
                        new NamedCurrencyWallet());

        territory.claim(MAYOR, town.id(), HOME, ClaimKind.HOMEBLOCK).join();
        territory.unclaim(MAYOR, town.id(), HOME).join();

        assertThat(bank.balanceOf(town.id()).join()).isEqualTo(Money.zero(CURRENCY));
        assertThat(bank.historyOf(town.id(), 10).join())
                .as("a free action is not a movement of zero; it is not a movement")
                .isEmpty();
    }

    @Test
    @DisplayName("claiming works with no economy plugin, because the treasury is ours")
    void claimingNeedsNoEconomyPlugin() {
        final Town town = riftholm();
        final TerritoryService territory = new TerritoryService(store, CLOCK, index,
                CivicPrices.free(), PlayerWallet.absent());

        assertThat(territory.claim(MAYOR, town.id(), HOME, ClaimKind.HOMEBLOCK).join().succeeded())
                .isTrue();
    }

    @Test
    @DisplayName("a negative price is refused at startup rather than paying players to claim")
    void negativePricesAreRefused() {
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> new CivicPrices(
                BigDecimal.ZERO, new BigDecimal("-5"), BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(CivicPrices.free().anyCharged()).isFalse();
    }

    /** Supplies the currency name and nothing else; claims are paid by the treasury. */
    private static final class NamedCurrencyWallet implements PlayerWallet {

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public String currency() {
            return CURRENCY;
        }

        @Override
        public java.util.concurrent.CompletableFuture<java.util.Optional<Money>> balanceOf(
                final ResidentId who) {
            return java.util.concurrent.CompletableFuture.completedFuture(java.util.Optional.empty());
        }

        @Override
        public java.util.concurrent.CompletableFuture<Boolean> take(
                final ResidentId who, final Money amount) {
            return java.util.concurrent.CompletableFuture.completedFuture(false);
        }

        @Override
        public java.util.concurrent.CompletableFuture<Boolean> give(
                final ResidentId who, final Money amount) {
            return java.util.concurrent.CompletableFuture.completedFuture(false);
        }
    }
}
