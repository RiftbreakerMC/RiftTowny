package net.riftbreaker.rifttowny.storage;

import net.riftbreaker.rifttowny.api.ChunkKey;
import net.riftbreaker.rifttowny.domain.bank.CivicPrices;
import net.riftbreaker.rifttowny.domain.bank.LedgerEntry;
import net.riftbreaker.rifttowny.domain.bank.Money;
import net.riftbreaker.rifttowny.domain.bank.PlayerWallet;
import net.riftbreaker.rifttowny.domain.civic.CivicCache;
import net.riftbreaker.rifttowny.domain.flag.FlagOverrides;
import net.riftbreaker.rifttowny.domain.naming.NamePolicy;
import net.riftbreaker.rifttowny.domain.org.ChangeDenial;
import net.riftbreaker.rifttowny.domain.org.Resident;
import net.riftbreaker.rifttowny.domain.org.ResidentId;
import net.riftbreaker.rifttowny.domain.org.Town;
import net.riftbreaker.rifttowny.domain.service.BankService;
import net.riftbreaker.rifttowny.domain.service.CivicCacheService;
import net.riftbreaker.rifttowny.domain.service.PlotService;
import net.riftbreaker.rifttowny.domain.service.RuinService;
import net.riftbreaker.rifttowny.domain.service.SpawnService;
import net.riftbreaker.rifttowny.domain.service.TerritoryService;
import net.riftbreaker.rifttowny.domain.service.TownService;
import net.riftbreaker.rifttowny.domain.territory.ClaimKind;
import net.riftbreaker.rifttowny.domain.territory.RuinIndex;
import net.riftbreaker.rifttowny.domain.territory.SpawnPoint;
import net.riftbreaker.rifttowny.domain.territory.TerritoryIndex;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The four prices paid from a player's own wallet.
 *
 * <p>Every one has the same shape and the same risk: the wallet is another plugin's and cannot join
 * our transaction, so the money leaves first and comes back if the civic half refuses. What is
 * asserted throughout is that nobody is ever charged for something that did not happen.
 */
class PlayerPriceTest extends SqliteFixture {

    private static final Instant NOW = Instant.parse("2026-08-13T09:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String CURRENCY = "coins";

    private static final UUID WORLD = UUID.randomUUID();
    private static final ChunkKey HOME = new ChunkKey(WORLD, 0, 0);
    private static final ChunkKey MARKET = new ChunkKey(WORLD, 1, 0);

    private static final ResidentId MAYOR = ResidentId.of(UUID.randomUUID());
    private static final ResidentId CITIZEN = ResidentId.of(UUID.randomUUID());

    private final TerritoryIndex index = TerritoryIndex.empty();
    private final RuinIndex ruinIndex = RuinIndex.empty();
    private final CivicCache civicCache = CivicCache.empty();
    private final FakeWallet wallet = new FakeWallet();

    private JdbcCivicStore store;
    private BankService bank;
    private JdbcResidentRepository residents;

    @BeforeEach
    void createServices() {
        store = new JdbcCivicStore(database, DIRECT, CLOCK);
        bank = new BankService(store, CLOCK, wallet);
        residents = new JdbcResidentRepository(database, DIRECT);
    }

    private static Money coins(final String amount) {
        return Money.of(new BigDecimal(amount), CURRENCY);
    }

    private static CivicPrices priced(
            final String founding, final String plot, final String reclaim, final String spawn) {
        return new CivicPrices(
                new BigDecimal(founding), BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal(plot), new BigDecimal(reclaim), new BigDecimal(spawn));
    }

    private TownService towns(final CivicPrices prices) {
        return new TownService(store, NamePolicy.defaults(), CLOCK, index,
                new CivicCacheService(store, civicCache, warning -> { }), FlagOverrides.empty(),
                ruinIndex, Duration.ZERO, Duration.ofDays(3), prices, wallet);
    }

    @Nested
    @DisplayName("founding a town")
    class Founding {

        @Test
        @DisplayName("the founder pays, and the fee leaves the economy")
        void founderPays() {
            residents.save(Resident.newcomer(MAYOR, "Mayor", NOW)).join();
            wallet.balances.put(MAYOR, coins("100"));

            final Town town = towns(priced("40", "0", "0", "0"))
                    .found(MAYOR, "Mayor", "Riftholm").join().value().orElseThrow();

            assertThat(wallet.balances.get(MAYOR)).isEqualTo(coins("60"));
            assertThat(bank.balanceOf(town.id()).join())
                    .as("a founding fee that funded the new town would be a fee in name only")
                    .isEqualTo(Money.zero(CURRENCY));
        }

        @Test
        @DisplayName("somebody who cannot afford it founds nothing")
        void cannotAfford() {
            residents.save(Resident.newcomer(MAYOR, "Mayor", NOW)).join();
            wallet.balances.put(MAYOR, coins("10"));

            assertThat(towns(priced("40", "0", "0", "0"))
                    .found(MAYOR, "Mayor", "Riftholm").join().denial())
                    .contains(ChangeDenial.INSUFFICIENT_FUNDS);

            assertThat(wallet.balances.get(MAYOR)).isEqualTo(coins("10"));
            assertThat(store.inTransaction(t -> t.towns().findByName("riftholm")).join()).isEmpty();
        }

        @Test
        @DisplayName("a refused founding is refunded")
        void refusedFoundingIsRefunded() {
            residents.save(Resident.newcomer(MAYOR, "Mayor", NOW)).join();
            residents.save(Resident.newcomer(CITIZEN, "Citizen", NOW)).join();
            wallet.balances.put(MAYOR, coins("100"));
            wallet.balances.put(CITIZEN, coins("100"));
            final TownService towns = towns(priced("40", "0", "0", "0"));
            towns.found(MAYOR, "Mayor", "Riftholm").join();

            assertThat(towns.found(CITIZEN, "Citizen", "Riftholm").join().denial())
                    .contains(ChangeDenial.NAME_TAKEN);

            assertThat(wallet.balances.get(CITIZEN))
                    .as("charged before the name check, so the refund is what makes it right")
                    .isEqualTo(coins("100"));
        }

        @Test
        @DisplayName("a priced server with no economy plugin blocks founding rather than waiving it")
        void pricedWithoutAnEconomy() {
            residents.save(Resident.newcomer(MAYOR, "Mayor", NOW)).join();
            final TownService towns = new TownService(store, NamePolicy.defaults(), CLOCK, index,
                    new CivicCacheService(store, civicCache, warning -> { }), FlagOverrides.empty(),
                    ruinIndex, Duration.ZERO, Duration.ofDays(3),
                    priced("40", "0", "0", "0"), PlayerWallet.absent());

            assertThat(towns.found(MAYOR, "Mayor", "Riftholm").join().denial())
                    .contains(ChangeDenial.NO_ECONOMY);
        }
    }

    @Nested
    @DisplayName("taking a plot")
    class Plots {

        @Test
        @DisplayName("the resident pays and the town receives")
        void plotPriceGoesToTheTown() {
            final Town town = townWithTwoChunks();
            wallet.balances.put(CITIZEN, coins("100"));
            final PlotService plots = new PlotService(store, CLOCK, index,
                    priced("0", "25", "0", "0"), wallet);

            assertThat(plots.take(CITIZEN, MARKET).join().succeeded()).isTrue();

            assertThat(wallet.balances.get(CITIZEN)).isEqualTo(coins("75"));
            assertThat(bank.balanceOf(town.id()).join())
                    .as("unlike a founding fee, this stays in the economy")
                    .isEqualTo(coins("25"));
            assertThat(bank.historyOf(town.id(), 1).join().getFirst().reason())
                    .isEqualTo(LedgerEntry.Reason.TRANSFER_IN);
        }

        @Test
        @DisplayName("a plot somebody else holds costs nothing to be refused")
        void refusedPlotIsRefunded() {
            townWithTwoChunks();
            wallet.balances.put(MAYOR, coins("100"));
            wallet.balances.put(CITIZEN, coins("100"));
            final PlotService plots = new PlotService(store, CLOCK, index,
                    priced("0", "25", "0", "0"), wallet);
            plots.take(MAYOR, MARKET).join();

            assertThat(plots.take(CITIZEN, MARKET).join().denial())
                    .contains(ChangeDenial.PLOT_ALREADY_HELD);

            assertThat(wallet.balances.get(CITIZEN)).isEqualTo(coins("100"));
        }
    }

    @Nested
    @DisplayName("rebuilding a ruin")
    class Reclaiming {

        @Test
        @DisplayName("the reclaimer pays, and the fee leaves the economy")
        void reclaimerPays() {
            final Town town = townWithTwoChunks();
            towns(CivicPrices.free()).disband(MAYOR, town.id()).join();
            wallet.balances.put(CITIZEN, coins("100"));
            final RuinService ruins = new RuinService(store, NamePolicy.defaults(), CLOCK, index,
                    ruinIndex, new CivicCacheService(store, civicCache, warning -> { }),
                    Duration.ofDays(3))
                    .pricedAt(priced("0", "0", "60", "0"), wallet);

            assertThat(ruins.reclaim(CITIZEN, "Citizen", HOME).join().succeeded()).isTrue();

            assertThat(wallet.balances.get(CITIZEN)).isEqualTo(coins("40"));
            assertThat(bank.balanceOf(town.id()).join())
                    .as("a reclaim fee that funded the restored town would be free")
                    .isEqualTo(Money.zero(CURRENCY));
        }

        @Test
        @DisplayName("somebody who cannot afford it rebuilds nothing")
        void cannotAffordToReclaim() {
            final Town town = townWithTwoChunks();
            towns(CivicPrices.free()).disband(MAYOR, town.id()).join();
            wallet.balances.put(CITIZEN, coins("10"));
            final RuinService ruins = new RuinService(store, NamePolicy.defaults(), CLOCK, index,
                    ruinIndex, new CivicCacheService(store, civicCache, warning -> { }),
                    Duration.ofDays(3))
                    .pricedAt(priced("0", "0", "60", "0"), wallet);

            assertThat(ruins.reclaim(CITIZEN, "Citizen", HOME).join().denial())
                    .as("the price is what stops one wealthy player collecting every fallen town")
                    .contains(ChangeDenial.INSUFFICIENT_FUNDS);
            assertThat(wallet.balances.get(CITIZEN)).isEqualTo(coins("10"));
            assertThat(ruinIndex.at(HOME)).isPresent();
        }
    }

    @Nested
    @DisplayName("travelling to a spawn")
    class SpawnFare {

        @Test
        @DisplayName("the fare is paid to the town on arrival")
        void farePaidOnArrival() {
            final Town town = townWithTwoChunks();
            wallet.balances.put(CITIZEN, coins("100"));
            final SpawnService spawns = new SpawnService(store, CLOCK, index)
                    .pricedAt(priced("0", "0", "0", "5"), wallet);
            spawns.set(MAYOR, town.id(), new SpawnPoint(WORLD, 8.5, 64, 8.5, 0f, 0f)).join();

            assertThat(spawns.chargeForTravel(CITIZEN, town.id()).join().succeeded()).isTrue();

            assertThat(wallet.balances.get(CITIZEN)).isEqualTo(coins("95"));
            assertThat(bank.balanceOf(town.id()).join()).isEqualTo(coins("5"));
        }

        @Test
        @DisplayName("no fare configured means the wallet is never touched")
        void freeTravel() {
            final Town town = townWithTwoChunks();
            final SpawnService spawns = new SpawnService(store, CLOCK, index);

            assertThat(spawns.chargeForTravel(CITIZEN, town.id()).join().succeeded()).isTrue();
            assertThat(spawns.travelFare().isZero()).isTrue();
            assertThat(wallet.takes).isZero();
        }
    }

    /** Riftholm with a homeblock, a market chunk and a second resident. */
    private Town townWithTwoChunks() {
        residents.save(Resident.newcomer(MAYOR, "Mayor", NOW)).join();
        residents.save(Resident.newcomer(CITIZEN, "Citizen", NOW)).join();
        final TownService towns = towns(CivicPrices.free());
        final Town town = towns.found(MAYOR, "Mayor", "Riftholm").join().value().orElseThrow();
        final TerritoryService territory = new TerritoryService(store, CLOCK, index);
        territory.claim(MAYOR, town.id(), HOME, ClaimKind.HOMEBLOCK).join();
        territory.claim(MAYOR, town.id(), MARKET, ClaimKind.ORDINARY).join();
        towns.join(MAYOR, CITIZEN, town.id()).join();
        return town;
    }

    /** Counts takes as well as holding balances, so "never touched" is assertable. */
    private static final class FakeWallet implements PlayerWallet {

        private final Map<ResidentId, Money> balances = new ConcurrentHashMap<>();
        private int takes;

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public String currency() {
            return CURRENCY;
        }

        @Override
        public CompletableFuture<Optional<Money>> balanceOf(final ResidentId who) {
            return CompletableFuture.completedFuture(Optional.ofNullable(balances.get(who)));
        }

        @Override
        public CompletableFuture<Boolean> take(final ResidentId who, final Money amount) {
            takes++;
            final Money held = balances.getOrDefault(who, Money.zero(CURRENCY));
            return held.minus(amount)
                    .map(left -> {
                        balances.put(who, left);
                        return CompletableFuture.completedFuture(true);
                    })
                    .orElseGet(() -> CompletableFuture.completedFuture(false));
        }

        @Override
        public CompletableFuture<Boolean> give(final ResidentId who, final Money amount) {
            balances.merge(who, amount, Money::plus);
            return CompletableFuture.completedFuture(true);
        }
    }
}
