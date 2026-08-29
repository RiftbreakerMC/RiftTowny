package net.riftbreaker.rifttowny.storage;

import net.riftbreaker.rifttowny.api.ChunkKey;
import net.riftbreaker.rifttowny.domain.bank.CivicPrices;
import net.riftbreaker.rifttowny.domain.bank.LedgerEntry;
import net.riftbreaker.rifttowny.domain.bank.Money;
import net.riftbreaker.rifttowny.domain.bank.PlayerWallet;
import net.riftbreaker.rifttowny.domain.org.ChangeDenial;
import net.riftbreaker.rifttowny.domain.bank.TaxPolicy;
import net.riftbreaker.rifttowny.domain.civic.CivicCache;
import net.riftbreaker.rifttowny.domain.flag.FlagOverrides;
import net.riftbreaker.rifttowny.domain.naming.NamePolicy;
import net.riftbreaker.rifttowny.domain.org.Resident;
import net.riftbreaker.rifttowny.domain.org.ResidentId;
import net.riftbreaker.rifttowny.domain.org.Town;
import net.riftbreaker.rifttowny.domain.service.BankService;
import net.riftbreaker.rifttowny.domain.service.CivicCacheService;
import net.riftbreaker.rifttowny.domain.service.TaxService;
import net.riftbreaker.rifttowny.domain.service.TerritoryService;
import net.riftbreaker.rifttowny.domain.service.TownService;
import net.riftbreaker.rifttowny.domain.territory.ClaimKind;
import net.riftbreaker.rifttowny.domain.territory.RuinIndex;
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
 * Tax runs, and the two properties that make them safe to schedule: a period runs once, and a town
 * that cannot pay is given time rather than destroyed.
 */
class TaxServiceTest extends SqliteFixture {

    private static final Instant NOW = Instant.parse("2026-08-13T09:00:00Z");
    private static final String CURRENCY = "coins";
    private static final UUID WORLD = UUID.randomUUID();
    private static final ChunkKey HOME = new ChunkKey(WORLD, 0, 0);
    private static final ChunkKey NEXT = new ChunkKey(WORLD, 1, 0);

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
        store = new JdbcCivicStore(database, DIRECT, at(NOW));
        bank = new BankService(store, at(NOW), wallet);
        residents = new JdbcResidentRepository(database, DIRECT);
    }

    private static Clock at(final Instant when) {
        return Clock.fixed(when, ZoneOffset.UTC);
    }

    private static Money coins(final String amount) {
        return Money.of(new BigDecimal(amount), CURRENCY);
    }

    private TownService towns(final Instant when) {
        return new TownService(store, NamePolicy.defaults(), at(when), index,
                new CivicCacheService(store, civicCache, warning -> { }), FlagOverrides.empty(),
                ruinIndex, Duration.ZERO, Duration.ofDays(3), CivicPrices.free(), wallet);
    }

    private TaxService taxes(final TaxPolicy policy, final Instant when, final String serverId) {
        final TownService towns = towns(when);
        return new TaxService(store, at(when), wallet, policy, serverId,
                (town, reason) -> towns.collapse(town)
                        .thenApply(net.riftbreaker.rifttowny.domain.service.ServiceResult
                                ::succeeded));
    }

    private static TaxPolicy policy(
            final String resident, final String upkeep, final Duration grace) {
        return new TaxPolicy(true, Duration.ofDays(1), new BigDecimal(resident),
                new BigDecimal(upkeep), BigDecimal.ZERO, grace, new BigDecimal("1000"));
    }

    /** Riftholm with two chunks and two residents. */
    private Town riftholm(final Instant when) {
        residents.save(Resident.newcomer(MAYOR, "Mayor", NOW)).join();
        residents.save(Resident.newcomer(CITIZEN, "Citizen", NOW)).join();
        final TownService towns = towns(when);
        final Town town = towns.found(MAYOR, "Mayor", "Riftholm").join().value().orElseThrow();
        final TerritoryService territory = new TerritoryService(store, at(when), index);
        territory.claim(MAYOR, town.id(), HOME, ClaimKind.HOMEBLOCK).join();
        territory.claim(MAYOR, town.id(), NEXT, ClaimKind.ORDINARY).join();
        towns.join(MAYOR, CITIZEN, town.id()).join();
        return town;
    }

    @Nested
    @DisplayName("running once")
    class RunningOnce {

        @Test
        @DisplayName("a period runs once, however many servers try it")
        void periodIsClaimedOnce() {
            riftholm(NOW);
            final TaxPolicy policy = policy("0", "1", Duration.ofDays(3));

            assertThat(taxes(policy, NOW, "alpha").runIfDue().join()).isPresent();
            assertThat(taxes(policy, NOW, "beta").runIfDue().join())
                    .as("a second backend server sharing the database must not charge everybody "
                            + "again")
                    .isEmpty();
        }

        @Test
        @DisplayName("the next period runs again")
        void nextPeriodRuns() {
            riftholm(NOW);
            final TaxPolicy policy = policy("0", "1", Duration.ofDays(3));
            taxes(policy, NOW, "alpha").runIfDue().join();

            assertThat(taxes(policy, NOW.plus(Duration.ofDays(1)), "alpha").runIfDue().join())
                    .isPresent();
        }

        @Test
        @DisplayName("a policy that collects nothing never runs at all")
        void nothingToCollect() {
            riftholm(NOW);

            assertThat(taxes(TaxPolicy.off(), NOW, "alpha").runIfDue().join()).isEmpty();
            assertThat(taxes(policy("0", "0", Duration.ZERO), NOW, "alpha").runIfDue().join())
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("collecting")
    class Collecting {

        @Test
        @DisplayName("residents pay their town, then the town pays its upkeep")
        void residentsPayFirst() {
            final Town town = riftholm(NOW);
            wallet.balances.put(MAYOR, coins("50"));
            wallet.balances.put(CITIZEN, coins("50"));

            // Two residents at 10 each pays 20 in; upkeep of 2 chunks at 5 takes 10 out.
            taxes(policy("10", "5", Duration.ofDays(3)), NOW, "alpha").runIfDue().join();

            assertThat(bank.balanceOf(town.id()).join())
                    .as("charging the town before its residents paid would bankrupt it needlessly")
                    .isEqualTo(coins("10"));
            assertThat(wallet.balances.get(MAYOR)).isEqualTo(coins("40"));
        }

        @Test
        @DisplayName("upkeep scales with the land held")
        void upkeepScalesWithChunks() {
            final Town town = riftholm(NOW);
            bank.pay(town.id(), coins("100"), LedgerEntry.Reason.ADMIN, null).join();

            taxes(policy("0", "7", Duration.ofDays(3)), NOW, "alpha").runIfDue().join();

            assertThat(bank.balanceOf(town.id()).join())
                    .as("two chunks at 7")
                    .isEqualTo(coins("86"));
        }

        @Test
        @DisplayName("a resident who cannot pay is not evicted")
        void poorResidentsStay() {
            final Town town = riftholm(NOW);
            wallet.balances.put(MAYOR, coins("50"));

            taxes(policy("10", "0", Duration.ofDays(3)), NOW, "alpha").runIfDue().join();

            assertThat(store.inTransaction(t -> t.towns().find(town.id()).orElseThrow()
                    .hasResident(CITIZEN)).join())
                    .as("eviction by timer punishes somebody who may simply have been away")
                    .isTrue();
            assertThat(bank.balanceOf(town.id()).join())
                    .as("and the one who could pay still did")
                    .isEqualTo(coins("10"));
        }
    }

    @Nested
    @DisplayName("falling behind")
    class FallingBehind {

        @Test
        @DisplayName("a town that cannot pay is marked, not destroyed")
        void firstMissIsMarkedOnly() {
            final Town town = riftholm(NOW);

            taxes(policy("0", "50", Duration.ofDays(3)), NOW, "alpha").runIfDue().join();

            assertThat(store.inTransaction(t -> t.towns().find(town.id())).join())
                    .as("a server whose players log off for a week should not come back to an "
                            + "empty map")
                    .isPresent();
            assertThat(store.inTransaction(t -> t.taxes().unpaidSince(town.id())).join())
                    .isPresent();
        }

        @Test
        @DisplayName("paying clears the debt, so one bad week costs nothing")
        void payingClearsTheMark() {
            final Town town = riftholm(NOW);
            taxes(policy("0", "50", Duration.ofDays(3)), NOW, "alpha").runIfDue().join();
            bank.pay(town.id(), coins("500"), LedgerEntry.Reason.ADMIN, null).join();

            final Instant later = NOW.plus(Duration.ofDays(1));
            taxes(policy("0", "50", Duration.ofDays(3)), later, "alpha").runIfDue().join();

            assertThat(store.inTransaction(t -> t.taxes().unpaidSince(town.id())).join()).isEmpty();
        }

        @Test
        @DisplayName("a town that never pays falls, into a ruin like any other")
        void graceRunsOut() {
            final Town town = riftholm(NOW);
            final TaxPolicy policy = policy("0", "50", Duration.ofDays(3));
            taxes(policy, NOW, "alpha").runIfDue().join();

            final Instant tooLate = NOW.plus(Duration.ofDays(4));
            final var run = taxes(policy, tooLate, "alpha").runIfDue().join().orElseThrow();

            assertThat(run.townsFallen()).isEqualTo(1);
            assertThat(run.fallen()).containsExactly("Riftholm");
            assertThat(store.inTransaction(t -> t.towns().find(town.id())).join()).isEmpty();
            assertThat(ruinIndex.at(HOME))
                    .as("bankruptcy is the second source of ruins, and the first nobody chose")
                    .isPresent();
        }

        @Test
        @DisplayName("grace is measured from the first miss, not the latest run")
        void graceIsMeasuredFromTheFirstMiss() {
            final Town town = riftholm(NOW);
            final TaxPolicy policy = policy("0", "50", Duration.ofDays(3));
            taxes(policy, NOW, "alpha").runIfDue().join();
            taxes(policy, NOW.plus(Duration.ofDays(1)), "alpha").runIfDue().join();

            assertThat(store.inTransaction(t -> t.taxes().unpaidSince(town.id())).join())
                    .as("otherwise every run would reset the clock and nothing would ever fall")
                    .contains(NOW);
        }
    }

    @Test
    @DisplayName("a negative tax is refused rather than paying towns to exist")
    void negativeTaxesAreRefused() {
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> new TaxPolicy(
                true, Duration.ofDays(1), new BigDecimal("-1"), BigDecimal.ZERO, BigDecimal.ZERO,
                Duration.ZERO, BigDecimal.ZERO)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("every server computes the same period key for the same moment")
    void periodKeysAgree() {
        final TaxPolicy daily = policy("0", "1", Duration.ZERO);

        assertThat(daily.periodKey(NOW)).isEqualTo(daily.periodKey(NOW.plusSeconds(3600)));
        assertThat(daily.periodKey(NOW)).isNotEqualTo(daily.periodKey(NOW.plus(Duration.ofDays(1))));
    }

    private static final class FakeWallet implements PlayerWallet {

        private final Map<ResidentId, Money> balances = new ConcurrentHashMap<>();

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
            balances.merge(who, amount, Money::plus);
            return CompletableFuture.completedFuture(true);
        }
    }

    /**
     * Picking a run back up after it was interrupted.
     *
     * <p>The defect these exist for: {@code claimPeriod} was a bare insert on the primary key, so a
     * run that died part-way left the row behind and every later attempt lost the insert. The towns
     * it had not yet reached were never charged for that period — silently, because nothing read
     * {@code finished_at} either.</p>
     *
     * <p>Resuming is only safe because each charge now claims its own key in the transaction that
     * moves the money, so the half already done cannot be done twice.</p>
     */
    @Nested
    @DisplayName("resuming an interrupted run")
    class Resuming {

        private static final Duration LONG_AGO = Duration.ofHours(6);

        /** Leaves the table looking like a run that claimed the period and then died. */
        private void abandonedRun(final String period, final Instant startedAt) {
            store.inTransaction(t ->
                    t.taxes().claimPeriod(period, "alpha", startedAt, Duration.ofHours(2))).join();
        }

        /** Marks one town as already charged in that period, as a completed half-run would have. */
        private void alreadyCharged(final String period, final Town town, final Instant when) {
            store.inTransaction(t -> t.keys().claim(
                    "tax:" + period + ":town:" + town.id().value(), "tax", when)).join();
        }

        private String periodOf(final TaxPolicy policy, final Instant when) {
            return policy.periodKey(when);
        }

        @Test
        @DisplayName("charges the towns the interrupted run never reached")
        void resumesTheRemainder() {
            final TaxPolicy policy = policy("0", "1", Duration.ofDays(3));
            final Town town = riftholm(NOW);
            bank.pay(town.id(), coins("50"), LedgerEntry.Reason.ADMIN, null).join();
            final String period = periodOf(policy, NOW);

            abandonedRun(period, NOW.minus(LONG_AGO));

            final var resumed = taxes(policy, NOW, "beta").runIfDue().join();

            assertThat(resumed).as("an abandoned run must not block the period for ever").isPresent();
            assertThat(resumed.orElseThrow().townsCharged()).isEqualTo(1);
        }

        @Test
        @DisplayName("and leaves alone the ones it did reach")
        void doesNotChargeTwice() {
            final TaxPolicy policy = policy("0", "1", Duration.ofDays(3));
            final Town town = riftholm(NOW);
            bank.pay(town.id(), coins("50"), LedgerEntry.Reason.ADMIN, null).join();
            final String period = periodOf(policy, NOW);

            abandonedRun(period, NOW.minus(LONG_AGO));
            alreadyCharged(period, town, NOW.minus(LONG_AGO));

            final var resumed = taxes(policy, NOW, "beta").runIfDue().join();

            assertThat(resumed.orElseThrow().townsCharged())
                    .as("its key was already held, so the resumed run must skip it")
                    .isZero();
            assertThat(bank.balanceOf(town.id()).join())
                    .as("and its money must be untouched")
                    .isEqualTo(coins("50"));
        }

        @Test
        @DisplayName("a run somebody is still working is not taken over")
        void freshRunsAreLeftAlone() {
            // The window is what separates "crashed" from "busy". Without it a second server would
            // join a run already in progress, which the keys make harmless but which is still two
            // servers doing the same sweep against one database.
            final TaxPolicy policy = policy("0", "1", Duration.ofDays(3));
            riftholm(NOW);
            final String period = periodOf(policy, NOW);

            abandonedRun(period, NOW.minusSeconds(30));

            assertThat(taxes(policy, NOW, "beta").runIfDue().join()).isEmpty();
        }

        @Test
        @DisplayName("a finished run is never taken over, however old it gets")
        void finishedRunsAreFinished() {
            // The original guarantee, which the takeover must not weaken: finished_at is what
            // separates a run that ended from one that stopped.
            final TaxPolicy policy = policy("0", "1", Duration.ofDays(3));
            final Town town = riftholm(NOW);
            bank.pay(town.id(), coins("50"), LedgerEntry.Reason.ADMIN, null).join();

            taxes(policy, NOW, "alpha").runIfDue().join();
            final Money afterFirst = bank.balanceOf(town.id()).join();

            assertThat(taxes(policy, NOW.plus(LONG_AGO), "beta").runIfDue().join())
                    .as("the period is done, and age does not undo that")
                    .isEmpty();
            assertThat(bank.balanceOf(town.id()).join()).isEqualTo(afterFirst);
        }

        @Test
        @DisplayName("the keys are scoped to the period, so the next one starts clean")
        void keysDoNotLeakIntoTheNextPeriod() {
            final TaxPolicy policy = policy("0", "1", Duration.ofDays(3));
            final Town town = riftholm(NOW);
            bank.pay(town.id(), coins("50"), LedgerEntry.Reason.ADMIN, null).join();

            taxes(policy, NOW, "alpha").runIfDue().join();
            final var next = taxes(policy, NOW.plus(Duration.ofDays(1)), "alpha").runIfDue().join();

            assertThat(next.orElseThrow().townsCharged())
                    .as("last period's key must not silence this period's charge")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("the last run can be read back, finished or not")
        void lastRunIsReadable() {
            // rt_tax_run had no SELECT anywhere: six columns written on every run and read by
            // nothing, so an unfinished run was invisible - which is most of why one that died
            // part-way could go unnoticed.
            final TaxPolicy policy = policy("0", "1", Duration.ofDays(3));
            final Town town = riftholm(NOW);
            bank.pay(town.id(), coins("50"), LedgerEntry.Reason.ADMIN, null).join();

            assertThat(store.inTransaction(t -> t.taxes().lastRun()).join())
                    .as("nothing has run yet")
                    .isEmpty();

            taxes(policy, NOW, "alpha").runIfDue().join();

            final var last = store.inTransaction(t -> t.taxes().lastRun()).join().orElseThrow();
            assertThat(last.finished()).isTrue();
            assertThat(last.townsCharged()).isEqualTo(1);
            assertThat(last.serverId()).isEqualTo("alpha");
        }

        @Test
        @DisplayName("and an interrupted one reads back as unfinished")
        void unfinishedRunIsVisible() {
            final TaxPolicy policy = policy("0", "1", Duration.ofDays(3));
            riftholm(NOW);

            abandonedRun(periodOf(policy, NOW), NOW.minus(LONG_AGO));

            final var last = store.inTransaction(t -> t.taxes().lastRun()).join().orElseThrow();
            assertThat(last.finished())
                    .as("this is the state an operator needs to be able to see")
                    .isFalse();
        }
    }
    /**
     * A town setting its own resident tax.
     *
     * <p>The engine shipped reading one server-wide rate, so Permission.MANAGE_TAXES could be
     * granted to a role and gated nothing. These cover the lever it now gates, and the distinction
     * the whole design turns on: a town that has set nothing is not a town that has set zero.</p>
     */
    @Nested
    @DisplayName("a town's own resident tax")
    class TownRate {

        private static final java.math.BigDecimal CAP = new java.math.BigDecimal("1000");

        private TownService townService() {
            return towns(NOW);
        }

        @Test
        @DisplayName("is charged instead of the server's")
        void townRateReplacesTheDefault() {
            final Town town = riftholm(NOW);
            wallet.balances.put(MAYOR, coins("50"));
            wallet.balances.put(CITIZEN, coins("50"));
            townService().setResidentTax(MAYOR, town.id(), new java.math.BigDecimal("7"), CAP)
                    .join();

            taxes(policy("2", "0", Duration.ofDays(3)), NOW, "alpha").runIfDue().join();

            assertThat(wallet.balances.get(MAYOR))
                    .as("the town's seven, not the server's two")
                    .isEqualTo(coins("43"));
            assertThat(bank.balanceOf(town.id()).join()).isEqualTo(coins("14"));
        }

        @Test
        @DisplayName("set to zero stops charging, and stays stopped when the server raises its rate")
        void zeroIsADecision() {
            // The reason the column is nullable and null is not zero. A town that has deliberately
            // stopped charging must not start again because the server changed its default.
            final Town town = riftholm(NOW);
            wallet.balances.put(MAYOR, coins("50"));
            townService().setResidentTax(MAYOR, town.id(), java.math.BigDecimal.ZERO, CAP).join();

            taxes(policy("9", "0", Duration.ofDays(3)), NOW, "alpha").runIfDue().join();

            assertThat(wallet.balances.get(MAYOR)).isEqualTo(coins("50"));
        }

        @Test
        @DisplayName("cleared falls back to the server's rate again")
        void clearingRestoresTheDefault() {
            final Town town = riftholm(NOW);
            wallet.balances.put(MAYOR, coins("50"));
            townService().setResidentTax(MAYOR, town.id(), new java.math.BigDecimal("7"), CAP)
                    .join();
            townService().setResidentTax(MAYOR, town.id(), null, CAP).join();

            taxes(policy("2", "0", Duration.ofDays(3)), NOW, "alpha").runIfDue().join();

            assertThat(wallet.balances.get(MAYOR)).isEqualTo(coins("48"));
        }

        @Test
        @DisplayName("cannot be set above the server's ceiling")
        void theCeilingHolds() {
            // Without it a mayor could empty the pockets of everybody who joined their town, once
            // per interval, with one command and no further consent.
            final Town town = riftholm(NOW);

            assertThat(townService()
                    .setResidentTax(MAYOR, town.id(), new java.math.BigDecimal("1001"), CAP)
                    .join().denial())
                    .contains(ChangeDenial.TAX_ABOVE_SERVER_MAXIMUM);
        }

        @Test
        @DisplayName("cannot be negative, because that would be the town paying its residents")
        void negativeIsRefused() {
            final Town town = riftholm(NOW);

            assertThat(townService()
                    .setResidentTax(MAYOR, town.id(), new java.math.BigDecimal("-1"), CAP)
                    .join().denial())
                    .contains(ChangeDenial.AMOUNT_MUST_BE_POSITIVE);
        }

        @Test
        @DisplayName("needs MANAGE_TAXES, which is what the permission finally gates")
        void needsThePermission() {
            final Town town = riftholm(NOW);

            assertThat(townService()
                    .setResidentTax(CITIZEN, town.id(), new java.math.BigDecimal("5"), CAP)
                    .join().denial())
                    .contains(ChangeDenial.MISSING_PERMISSION);
        }

        @Test
        @DisplayName("survives a round trip through storage")
        void ratePersists() {
            final Town town = riftholm(NOW);
            final var outcome = townService()
                    .setResidentTax(MAYOR, town.id(), new java.math.BigDecimal("7"), CAP).join();
            assertThat(outcome.succeeded()).as("denial: %s", outcome.denial()).isTrue();

            assertThat(store.inTransaction(t -> t.towns().find(town.id()).orElseThrow())
                    .join().profile().residentTaxRate())
                    .contains(new java.math.BigDecimal("7"));
        }
    }

    /**
     * What a run announces.
     *
     * <p>FEATURE_CATALOG states the rule for the whole plugin: every mutating feature emits a typed
     * post-event and writes an outbox row. The tax run - which moves money for every town on the
     * server and can end one - emitted nothing at all, while its catalogue row listed two events it
     * did not produce. These hold the rule to the largest scheduled mutation there is.</p>
     */
    @Nested
    @DisplayName("what a run announces")
    class Events {

        /** Event types in the outbox, which is the only place a consumer would look. */
        private java.util.List<String> announced() throws Exception {
            final java.util.List<String> types = new java.util.ArrayList<>();
            database.read(connection -> {
                try (java.sql.PreparedStatement statement = connection.prepareStatement(
                        "SELECT event_type FROM rt_outbox ORDER BY created_at, event_id");
                        java.sql.ResultSet results = statement.executeQuery()) {
                    while (results.next()) {
                        types.add(results.getString("event_type"));
                    }
                }
                return null;
            });
            return types;
        }

        @Test
        @DisplayName("a completed run is announced, in the transaction that finished it")
        void runIsAnnounced() throws Exception {
            final Town town = riftholm(NOW);
            bank.pay(town.id(), coins("100"), LedgerEntry.Reason.ADMIN, null).join();

            taxes(policy("0", "7", Duration.ofDays(3)), NOW, "alpha").runIfDue().join();

            assertThat(announced()).contains("tax.run-completed");
        }

        @Test
        @DisplayName("a town that falls for unpaid upkeep says so, beyond saying it is gone")
        void bankruptcyIsAnnounced() throws Exception {
            // TownDisbanded says the town is gone. Without this, a relay cannot tell a town that
            // chose to disband from one taken by a timer, and the second is the one a moderator
            // gets asked about.
            final Town town = riftholm(NOW);
            final TaxPolicy policy = policy("0", "7", Duration.ZERO);
            taxes(policy, NOW, "alpha").runIfDue().join();

            taxes(policy, NOW.plus(Duration.ofDays(1)), "alpha").runIfDue().join();

            assertThat(announced()).contains("tax.town-fell-bankrupt");
        }

        @Test
        @DisplayName("a run that charged nobody still announces itself")
        void quietRunsAreAnnouncedToo() throws Exception {
            // A consumer counting runs needs the ones that did nothing as much as the ones that
            // did: a missing announcement reads as a run that never happened.
            taxes(policy("0", "1", Duration.ofDays(3)), NOW, "alpha").runIfDue().join();

            assertThat(announced()).contains("tax.run-completed");
        }
    }

}
