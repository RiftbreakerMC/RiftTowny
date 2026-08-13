package net.riftbreaker.rifttowny.storage;

import net.riftbreaker.rifttowny.domain.bank.LedgerEntry;
import net.riftbreaker.rifttowny.domain.bank.Money;
import net.riftbreaker.rifttowny.domain.bank.PlayerWallet;
import net.riftbreaker.rifttowny.domain.civic.CivicCache;
import net.riftbreaker.rifttowny.domain.naming.NamePolicy;
import net.riftbreaker.rifttowny.domain.org.ChangeDenial;
import net.riftbreaker.rifttowny.domain.org.OrganisationScope;
import net.riftbreaker.rifttowny.domain.org.Resident;
import net.riftbreaker.rifttowny.domain.org.ResidentId;
import net.riftbreaker.rifttowny.domain.org.Town;
import net.riftbreaker.rifttowny.domain.role.Permission;
import net.riftbreaker.rifttowny.domain.role.RoleBook;
import net.riftbreaker.rifttowny.domain.role.RoleId;
import net.riftbreaker.rifttowny.domain.role.SystemRole;
import net.riftbreaker.rifttowny.domain.service.BankService;
import net.riftbreaker.rifttowny.domain.service.CivicCacheService;
import net.riftbreaker.rifttowny.domain.service.TownService;
import net.riftbreaker.rifttowny.domain.territory.TerritoryIndex;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

class BankServiceTest extends SqliteFixture {

    private static final Instant NOW = Instant.parse("2026-08-13T09:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String CURRENCY = "coins";

    private static final ResidentId MAYOR = ResidentId.of(UUID.randomUUID());
    private static final ResidentId CITIZEN = ResidentId.of(UUID.randomUUID());

    private final FakeWallet wallet = new FakeWallet();

    private JdbcCivicStore store;
    private BankService bank;
    private TownService towns;
    private JdbcResidentRepository residents;

    @BeforeEach
    void createServices() {
        store = new JdbcCivicStore(database, DIRECT, CLOCK);
        bank = new BankService(store, CLOCK, wallet);
        towns = new TownService(store, NamePolicy.defaults(), CLOCK, TerritoryIndex.empty(),
                new CivicCacheService(store, CivicCache.empty(), warning -> { }));
        residents = new JdbcResidentRepository(database, DIRECT);
    }

    private Town riftholm() {
        residents.save(Resident.newcomer(MAYOR, "Mayor", NOW)).join();
        residents.save(Resident.newcomer(CITIZEN, "Citizen", NOW)).join();
        final Town town = towns.found(MAYOR, "Mayor", "Riftholm").join().value().orElseThrow();
        towns.join(MAYOR, CITIZEN, town.id()).join();
        return town;
    }

    private static Money coins(final String amount) {
        return Money.of(new BigDecimal(amount), CURRENCY);
    }

    @Nested
    @DisplayName("the ledger")
    class Ledger {

        @Test
        @DisplayName("a new town holds nothing, and says so rather than failing")
        void emptyTreasury() {
            final Town town = riftholm();

            assertThat(bank.balanceOf(town.id()).join()).isEqualTo(Money.zero(CURRENCY));
            assertThat(bank.historyOf(town.id(), 10).join()).isEmpty();
        }

        @Test
        @DisplayName("a movement writes the balance and the entry that explains it")
        void movementIsRecorded() {
            final Town town = riftholm();

            bank.pay(town.id(), coins("100"), LedgerEntry.Reason.TAX, "harvest").join();

            assertThat(bank.balanceOf(town.id()).join()).isEqualTo(coins("100"));
            final List<LedgerEntry> history = bank.historyOf(town.id(), 10).join();
            assertThat(history).hasSize(1);
            assertThat(history.getFirst().reason()).isEqualTo(LedgerEntry.Reason.TAX);
            assertThat(history.getFirst().balance()).isEqualTo(coins("100"));
            assertThat(history.getFirst().note()).contains("harvest");
        }

        @Test
        @DisplayName("the balance always equals the sum of its history")
        void balanceMatchesHistory() {
            final Town town = riftholm();

            bank.pay(town.id(), coins("100"), LedgerEntry.Reason.TAX, null).join();
            bank.charge(town.id(), coins("30"), LedgerEntry.Reason.UPKEEP, null).join();
            bank.pay(town.id(), coins("5.5"), LedgerEntry.Reason.TAX, null).join();

            BigDecimal total = BigDecimal.ZERO;
            for (final LedgerEntry entry : bank.historyOf(town.id(), 100).join()) {
                total = entry.reason().credits()
                        ? total.add(entry.amount().amount())
                        : total.subtract(entry.amount().amount());
            }
            assertThat(bank.balanceOf(town.id()).join()).isEqualTo(Money.of(total, CURRENCY));
            assertThat(bank.balanceOf(town.id()).join()).isEqualTo(coins("75.5"));
        }

        @Test
        @DisplayName("a town cannot be charged more than it holds")
        void cannotOverdraw() {
            final Town town = riftholm();
            bank.pay(town.id(), coins("10"), LedgerEntry.Reason.TAX, null).join();

            assertThat(bank.charge(town.id(), coins("11"), LedgerEntry.Reason.UPKEEP, null)
                    .join().denial())
                    .as("a negative balance is debt, which needs terms rather than a minus sign")
                    .contains(ChangeDenial.INSUFFICIENT_CIVIC_FUNDS);
            assertThat(bank.balanceOf(town.id()).join()).isEqualTo(coins("10"));
        }

        @Test
        @DisplayName("money survives arithmetic that would lose it as a double")
        void decimalIsExact() {
            final Town town = riftholm();

            for (int i = 0; i < 10; i++) {
                bank.pay(town.id(), coins("0.1"), LedgerEntry.Reason.TAX, null).join();
            }

            assertThat(bank.balanceOf(town.id()).join())
                    .as("ten lots of 0.1 is 1, and as a double it is not")
                    .isEqualTo(coins("1"));
        }

        @Test
        @DisplayName("history is newest first and bounded")
        void historyIsBounded() {
            final Town town = riftholm();
            for (int i = 1; i <= 5; i++) {
                bank.pay(town.id(), coins(String.valueOf(i)), LedgerEntry.Reason.TAX, "run " + i)
                        .join();
            }

            final List<LedgerEntry> history = bank.historyOf(town.id(), 3).join();

            assertThat(history).hasSize(3);
            assertThat(history.getFirst().note()).contains("run 5");
        }
    }

    @Nested
    @DisplayName("with no economy plugin")
    class WithoutAnEconomy {

        @Test
        @DisplayName("the treasury still works, and player transfers refuse honestly")
        void civicSideStillWorks() {
            final BankService offline =
                    new BankService(store, CLOCK, PlayerWallet.absent());
            final Town town = riftholm();
            offline.pay(town.id(), coins("50"), LedgerEntry.Reason.ADMIN, null).join();

            assertThat(offline.economyAvailable()).isFalse();
            assertThat(offline.balanceOf(town.id()).join()).isEqualTo(coins("50"));
            assertThat(offline.deposit(MAYOR, town.id(), coins("10")).join().denial())
                    .contains(ChangeDenial.NO_ECONOMY);
            assertThat(offline.withdraw(MAYOR, town.id(), coins("10")).join().denial())
                    .contains(ChangeDenial.NO_ECONOMY);
        }
    }

    @Nested
    @DisplayName("moving money between a player and the town")
    class PlayerTransfers {

        @Test
        @DisplayName("a deposit leaves the player and arrives in the town")
        void depositing() {
            final Town town = riftholm();
            wallet.give(MAYOR, coins("100")).join();

            assertThat(bank.deposit(MAYOR, town.id(), coins("40")).join().value())
                    .contains(coins("40"));

            assertThat(wallet.balances.get(MAYOR)).isEqualTo(coins("60"));
            assertThat(bank.balanceOf(town.id()).join()).isEqualTo(coins("40"));
        }

        @Test
        @DisplayName("a player who does not have it keeps what they do")
        void depositingMoreThanYouHave() {
            final Town town = riftholm();
            wallet.give(MAYOR, coins("5")).join();

            assertThat(bank.deposit(MAYOR, town.id(), coins("40")).join().denial())
                    .contains(ChangeDenial.INSUFFICIENT_FUNDS);

            assertThat(wallet.balances.get(MAYOR)).isEqualTo(coins("5"));
            assertThat(bank.balanceOf(town.id()).join()).isEqualTo(Money.zero(CURRENCY));
        }

        @Test
        @DisplayName("zero is not an amount")
        void zeroIsRefused() {
            final Town town = riftholm();

            assertThat(bank.deposit(MAYOR, town.id(), Money.zero(CURRENCY)).join().denial())
                    .contains(ChangeDenial.AMOUNT_MUST_BE_POSITIVE);
        }

        @Test
        @DisplayName("a withdrawal needs BANK_WITHDRAW, which no default role but the leader has")
        void withdrawingNeedsPermission() {
            final Town town = riftholm();
            bank.pay(town.id(), coins("100"), LedgerEntry.Reason.ADMIN, null).join();

            assertThat(bank.withdraw(CITIZEN, town.id(), coins("10")).join().denial())
                    .as("the town's money is what one misjudged role grant can empty")
                    .contains(ChangeDenial.MISSING_PERMISSION);
            assertThat(bank.withdraw(MAYOR, town.id(), coins("10")).join().succeeded()).isTrue();
            assertThat(wallet.balances.get(MAYOR)).isEqualTo(coins("10"));
        }

        @Test
        @DisplayName("a deposit needs BANK_DEPOSIT, which every member holds by default")
        void depositingNeedsPermission() {
            final Town town = riftholm();
            wallet.give(CITIZEN, coins("50")).join();

            assertThat(bank.deposit(CITIZEN, town.id(), coins("10")).join().succeeded()).isTrue();

            revokeMemberPermission(town, Permission.BANK_DEPOSIT);
            assertThat(bank.deposit(CITIZEN, town.id(), coins("10")).join().denial())
                    .contains(ChangeDenial.MISSING_PERMISSION);
            assertThat(wallet.balances.get(CITIZEN))
                    .as("and the refusal costs them nothing")
                    .isEqualTo(coins("40"));
        }

        @Test
        @DisplayName("a wallet that will not pay leaves the money in the town, and says so")
        void walletRefusingPayment() {
            final Town town = riftholm();
            bank.pay(town.id(), coins("100"), LedgerEntry.Reason.ADMIN, null).join();
            wallet.refusePayments = true;

            assertThat(bank.withdraw(MAYOR, town.id(), coins("30")).join().denial())
                    .contains(ChangeDenial.ECONOMY_REFUSED);

            assertThat(bank.balanceOf(town.id()).join())
                    .as("debited then put back, so nothing is created and nothing is lost")
                    .isEqualTo(coins("100"));
            assertThat(bank.historyOf(town.id(), 10).join())
                    .as("and both halves are in the history for an operator to read")
                    .hasSize(3);
        }

        @Test
        @DisplayName("a town cannot pay out more than it holds")
        void withdrawingMoreThanTheTownHas() {
            final Town town = riftholm();
            bank.pay(town.id(), coins("10"), LedgerEntry.Reason.ADMIN, null).join();

            assertThat(bank.withdraw(MAYOR, town.id(), coins("50")).join().denial())
                    .contains(ChangeDenial.INSUFFICIENT_CIVIC_FUNDS);
            assertThat(wallet.balances.getOrDefault(MAYOR, Money.zero(CURRENCY)))
                    .isEqualTo(Money.zero(CURRENCY));
        }
    }

    @Test
    @DisplayName("an amount is parsed, or refused rather than guessed at")
    void parsingAmounts() {
        assertThat(Money.parse("12.5", CURRENCY)).contains(coins("12.5"));
        assertThat(Money.parse("-5", CURRENCY)).as("negative is not a deposit").isEmpty();
        assertThat(Money.parse("lots", CURRENCY)).isEmpty();
        assertThat(Money.parse("", CURRENCY)).isEmpty();
        assertThat(coins("5")).isEqualTo(coins("5.00"));
        assertThat(coins("12.5").describe()).isEqualTo("12.5 coins");
    }

    private void revokeMemberPermission(final Town town, final Permission permission) {
        store.inTransaction(transaction -> {
            final RoleBook book = transaction.roles()
                    .find(OrganisationScope.TOWN, town.id().value()).orElseThrow();
            final RoleId member = book.systemRole(SystemRole.MEMBER).orElseThrow().id();
            transaction.roles().save(book.revoke(member, permission).orElseThrow());
            return null;
        }).join();
    }

    /** A wallet that behaves, so the service's own ordering is what is under test. */
    private static final class FakeWallet implements PlayerWallet {

        private final Map<ResidentId, Money> balances = new ConcurrentHashMap<>();
        private boolean refusePayments;

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
            if (refusePayments) {
                return CompletableFuture.completedFuture(false);
            }
            balances.merge(who, amount, Money::plus);
            return CompletableFuture.completedFuture(true);
        }
    }
}
