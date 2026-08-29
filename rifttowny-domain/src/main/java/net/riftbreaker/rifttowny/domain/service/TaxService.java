package net.riftbreaker.rifttowny.domain.service;

import net.riftbreaker.rifttowny.domain.bank.LedgerEntry;
import net.riftbreaker.rifttowny.domain.bank.Money;
import net.riftbreaker.rifttowny.domain.bank.PlayerWallet;
import net.riftbreaker.rifttowny.domain.bank.TaxPolicy;
import net.riftbreaker.rifttowny.domain.event.DomainEvent;
import net.riftbreaker.rifttowny.domain.org.Nation;
import net.riftbreaker.rifttowny.domain.org.ResidentId;
import net.riftbreaker.rifttowny.domain.org.Town;
import net.riftbreaker.rifttowny.domain.org.TownId;
import net.riftbreaker.rifttowny.domain.store.CivicStore;
import net.riftbreaker.rifttowny.domain.store.CivicTransaction;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Collecting tax, and what happens to a town that cannot pay.
 *
 * <p>Three charges in one run, in an order that is not arbitrary: <strong>residents pay their town
 * first, then the town pays its upkeep and its nation.</strong> The other way round would bankrupt
 * a town for a bill it could have covered with money arriving in the same minute.</p>
 *
 * <p><strong>Failure is graduated, not immediate.</strong> A town that cannot pay is marked, not
 * destroyed; it falls only after the configured grace has passed with the debt outstanding, and the
 * mark is cleared the moment it pays. A server whose players log off for a week should not come back
 * to an empty map.</p>
 *
 * <p>When a town does fall it falls the same way any other town does — into a ruin, through the same
 * disband path — so its land is held, is lootable, and can be rebuilt by somebody who can afford the
 * upkeep. That is the second source of ruins the feature was designed for, and the first that is not
 * a mayor choosing it.</p>
 */
public final class TaxService {

    /**
     * How long an unfinished run is left alone before another attempt takes it over.
     *
     * <p>Generous, because the cost of waiting is one late tax run and the cost of being hasty is
     * two servers sweeping at once. Not that the second is unsafe — every charge claims its own key
     * — but a run doing nothing but losing key claims is wasted work against a live database.</p>
     */
    private static final java.time.Duration STALE_AFTER = java.time.Duration.ofHours(2);

    /** The scope written beside every key this service takes, so a prune can find them. */
    private static final String KEY_SCOPE = "tax";

    private final CivicStore store;
    private final Clock clock;
    private final PlayerWallet wallet;
    private final TaxPolicy policy;
    private final String serverId;
    private final TownFall fall;

    /**
     * @param fall how a bankrupt town is ended. Injected rather than called directly because
     *        disbanding lives in {@link TownService} and a tax run reaching into it would make the
     *        two services mutually dependent
     */
    public TaxService(
            final CivicStore store,
            final Clock clock,
            final PlayerWallet wallet,
            final TaxPolicy policy,
            final String serverId,
            final TownFall fall
    ) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.wallet = Objects.requireNonNull(wallet, "wallet");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.serverId = Objects.requireNonNull(serverId, "serverId");
        this.fall = Objects.requireNonNull(fall, "fall");
    }

    /** Ending a town that could not pay. */
    @FunctionalInterface
    public interface TownFall {
        CompletableFuture<Boolean> collapse(TownId town, String reason);
    }

    public TaxPolicy policy() {
        return policy;
    }

    /**
     * Runs a collection if one is due and nobody else has taken it.
     *
     * <p>Safe to call as often as the scheduler likes: the period claim is an insert on a primary
     * key, so of every server that tries, exactly one proceeds. Calling this every few minutes for a
     * daily tax is the intended usage.</p>
     */
    public CompletableFuture<Optional<TaxRun>> runIfDue() {
        if (!policy.collectsAnything()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        final Instant now = clock.instant();
        final String period = policy.periodKey(now);

        return store.inTransaction(transaction ->
                        transaction.taxes().claimPeriod(period, serverId, now, STALE_AFTER))
                .thenCompose(claimed -> claimed
                        ? collect(period, now).thenApply(Optional::of)
                        : CompletableFuture.completedFuture(Optional.empty()));
    }

    /** What one run did. */
    public record TaxRun(
            String periodKey,
            int townsCharged,
            int residentsCharged,
            int townsFallen,
            List<String> fallen
    ) {

        public TaxRun {
            fallen = List.copyOf(Objects.requireNonNull(fallen, "fallen"));
        }

        public String describe() {
            return "charged " + residentsCharged + " resident(s) and " + townsCharged + " town(s)"
                    + (townsFallen == 0 ? "" : "; " + townsFallen + " fell: " + String.join(", ", fallen));
        }
    }

    // --- internals -----------------------------------------------------------------------------

    private CompletableFuture<TaxRun> collect(final String period, final Instant now) {
        return store.inTransaction(transaction -> transaction.taxes().allTowns())
                .thenCompose(towns -> chargeEach(towns, period, now))
                .thenCompose(tally -> endBankruptTowns(tally, now))
                .thenCompose(tally -> store.inTransaction(transaction -> {
                    transaction.taxes().finishRun(period, tally.townsCharged,
                            tally.residentsCharged, tally.fallen.size(), now);
                    // In the same transaction as finishing the run. The catalogue's own rule is
                    // that every mutating feature emits a typed post-event and writes an outbox
                    // row, and the tax run - which moves money for every town on the server -
                    // emitted nothing at all until this.
                    transaction.publish(
                            new DomainEvent.TaxRunCompleted(period, tally.townsCharged,
                                    tally.residentsCharged, tally.fallen.size()),
                            "tax:" + period);
                    return new TaxRun(period, tally.townsCharged, tally.residentsCharged,
                            tally.fallen.size(), tally.fallenNames);
                }));
    }

    /**
     * Charges every town, one at a time.
     *
     * <p>Sequential rather than parallel, deliberately. A resident tax touches a wallet in another
     * plugin, and a hundred towns charging concurrently would be a hundred concurrent transactions
     * against a database sized for a game server. A tax run has all interval to finish in.</p>
     */
    private CompletableFuture<Tally> chargeEach(
            final List<TownId> towns, final String period, final Instant now) {
        CompletableFuture<Tally> chain = CompletableFuture.completedFuture(new Tally());
        for (final TownId townId : towns) {
            chain = chain.thenCompose(tally -> chargeOne(townId, period, now, tally));
        }
        return chain;
    }

    private CompletableFuture<Tally> chargeOne(
            final TownId townId, final String period, final Instant now, final Tally tally) {
        // Residents first, so a town is judged on what it holds after its own people have paid in.
        return collectResidentTax(townId, period, now).thenCompose(paid -> {
            tally.residentsCharged += paid;
            return store.inTransaction(transaction -> {
                // Taken in the transaction that moves the money, so the two share a fate. A run
                // that was interrupted part-way can therefore be resumed: towns already charged
                // hold their key and are skipped, and the rest are charged exactly once.
                if (!transaction.keys().claim(townKey(period, townId), KEY_SCOPE, now)) {
                    return tally;
                }
                final Optional<Town> found = transaction.towns().find(townId);
                if (found.isEmpty()) {
                    return tally;
                }
                final Town town = found.get();
                final Money owed = billFor(transaction, town);
                if (owed.isZero()) {
                    return tally;
                }

                final Money held = transaction.bank()
                        .balance(town.bankAccountId(), owed.currency())
                        .orElseGet(() -> Money.zero(owed.currency()));
                final Optional<Money> after = held.minus(owed);
                if (after.isEmpty()) {
                    // Marked, not destroyed. The timestamp is only set the first time, so grace is
                    // measured from when the trouble started rather than from the latest run.
                    if (transaction.taxes().unpaidSince(townId).isEmpty()) {
                        transaction.taxes().markUnpaid(townId, now);
                    }
                    return tally;
                }

                payBill(transaction, town, owed, after.get(), now);
                transaction.taxes().markUnpaid(townId, null);
                tally.townsCharged++;
                return tally;
            });
        });
    }

    /** Upkeep plus nation tax, as one bill: a town either covers what it owes or it does not. */
    private Money billFor(final CivicTransaction transaction, final Town town) {
        final String currency = wallet.currency();
        Money owed = policy.upkeepFor(transaction.claims().of(town.id()).size(), currency);
        if (town.nation().isPresent() && TaxPolicy.charged(policy.nationTaxPerTown())) {
            owed = owed.plus(policy.nationTax(currency));
        }
        return owed;
    }

    /**
     * Takes the bill and pays the nation's share on.
     *
     * <p>Two ledger entries rather than one, because they are two different things: upkeep leaves
     * the economy and nation tax moves to another civic account, and a history that showed one line
     * could not tell an operator which.</p>
     */
    private void payBill(
            final CivicTransaction transaction,
            final Town town,
            final Money owed,
            final Money after,
            final Instant now
    ) {
        transaction.bank().record(
                LedgerEntry.of(town.bankAccountId(), owed, after, LedgerEntry.Reason.UPKEEP,
                        null, "tax run", now),
                now);

        if (town.nation().isEmpty() || !TaxPolicy.charged(policy.nationTaxPerTown())) {
            return;
        }
        final Money share = policy.nationTax(wallet.currency());
        transaction.nations().find(town.nation().orElseThrow()).ifPresent(nation -> {
            final Money before = transaction.bank()
                    .balance(nation.bankAccountId(), share.currency())
                    .orElseGet(() -> Money.zero(share.currency()));
            transaction.bank().record(
                    LedgerEntry.of(nation.bankAccountId(), share, before.plus(share),
                            LedgerEntry.Reason.TAX, null, "from " + town.name().display(), now),
                    now);
        });
    }

    /**
     * Charges every resident of a town and pays it in.
     *
     * <p>A resident who cannot pay is <strong>not</strong> removed from their town. Towny's own
     * behaviour is to evict them, and this deliberately differs: eviction is a punishment applied
     * by a timer to somebody who may simply have been away, and the town has better remedies —
     * {@code /town kick} exists and takes a person to use it.</p>
     *
     * @return how many actually paid
     */
    private CompletableFuture<Integer> collectResidentTax(
            final TownId townId, final String period, final Instant now) {
        // Deliberately not short-circuited on the server's rate any more. A town may charge when
        // the server charges nothing, so the rate that decides whether there is anything to collect
        // is the town's, and it is not known until the sweep reads it.
        if (!wallet.available()) {
            return CompletableFuture.completedFuture(0);
        }

        // Claimed before the sweep rather than inside it, which is the opposite of the town charge
        // above and deliberate. wallet.take reaches into another plugin, outside this transaction
        // and outside this database, so nothing here can roll it back. Between skipping a town
        // whose sweep may not have finished and taking from its residents a second time, the first
        // costs the town one period of resident tax and the second costs real players real money.
        return store.inTransaction(transaction ->
                transaction.keys().claim(residentKey(period, townId), KEY_SCOPE, now))
                .thenCompose(won -> won
                        ? sweepResidents(townId)
                        : CompletableFuture.completedFuture(0));
    }

    private CompletableFuture<Integer> sweepResidents(final TownId townId) {
        // The rate and the roll are read in one transaction, so a town cannot be charged its old
        // rate against its new membership or the other way round.
        return store.inTransaction(transaction -> new Sweep(
                        rateFor(transaction, townId),
                        transaction.residents().findByTown(townId).stream()
                                .map(net.riftbreaker.rifttowny.domain.org.Resident::id)
                                .toList()))
                .thenCompose(sweep -> {
                    final Money due = sweep.due();
                    if (!TaxPolicy.charged(due.amount())) {
                        return CompletableFuture.completedFuture(0);
                    }
                    final java.util.List<ResidentId> people = sweep.people();
                    CompletableFuture<Integer> chain = CompletableFuture.completedFuture(0);
                    for (final ResidentId who : people) {
                        chain = chain.thenCompose(paid -> wallet.take(who, due)
                                .thenCompose(took -> took
                                        ? creditTown(townId, due, who).thenApply(ok -> paid + 1)
                                        : CompletableFuture.completedFuture(paid)));
                    }
                    return chain;
                });
    }

    private CompletableFuture<Boolean> creditTown(
            final TownId townId, final Money due, final ResidentId from) {
        final Instant now = clock.instant();
        return store.inTransaction(transaction -> {
            final Optional<Town> found = transaction.towns().find(townId);
            if (found.isEmpty()) {
                return false;
            }
            final Town town = found.get();
            final Money before = transaction.bank().balance(town.bankAccountId(), due.currency())
                    .orElseGet(() -> Money.zero(due.currency()));
            transaction.bank().record(
                    LedgerEntry.of(town.bankAccountId(), due, before.plus(due),
                            LedgerEntry.Reason.TAX, from, "resident tax", now),
                    now);
            return true;
        });
    }

    /** Ends the towns whose grace has run out. */
    private CompletableFuture<Tally> endBankruptTowns(final Tally tally, final Instant now) {
        return store.inTransaction(transaction -> {
            final List<TownId> doomed = new ArrayList<>();
            for (final TownId townId : transaction.taxes().allTowns()) {
                final Optional<Instant> since = transaction.taxes().unpaidSince(townId);
                if (since.isPresent() && policy.hasRunOutOfTime(since.get(), now)) {
                    doomed.add(townId);
                    transaction.towns().find(townId)
                            .ifPresent(town -> tally.fallenNames.add(town.name().display()));
                }
            }
            return doomed;
        }).thenCompose(doomed -> {
            CompletableFuture<Tally> chain = CompletableFuture.completedFuture(tally);
            for (final TownId townId : doomed) {
                chain = chain.thenCompose(running -> fall.collapse(townId, "unpaid upkeep")
                        .thenCompose(fell -> {
                            if (!Boolean.TRUE.equals(fell)) {
                                return CompletableFuture.completedFuture(running);
                            }
                            running.fallen.add(townId);
                            // After the collapse has committed, not with it. The collapse emits
                            // TownDisbanded, which says the town is gone; this says why, and a
                            // relay cannot otherwise tell a town that chose to disband from one
                            // taken by a timer - which is the one a moderator gets asked about.
                            return store.<Void>inTransaction(transaction -> {
                                transaction.publish(
                                        new DomainEvent.TownFellBankrupt(townId, "unpaid upkeep"),
                                        "tax:" + townId.value());
                                return null;
                            }).thenApply(ignored -> running);
                        }));
            }
            return chain;
        });
    }


    /** One town's rate and its people, read together so the two cannot come from different states. */
    private record Sweep(Money due, java.util.List<ResidentId> people) {
    }

    /**
     * What this town charges its residents.
     *
     * <p>The town's own rate when it has set one, and the server's otherwise. Absent is not zero:
     * a town that has deliberately set zero keeps charging nothing when the server raises its
     * default, which is the whole point of letting a town decide.</p>
     */
    private Money rateFor(final CivicTransaction transaction, final TownId townId) {
        return transaction.towns().find(townId)
                .flatMap(town -> town.profile().residentTaxRate())
                .map(rate -> Money.of(rate, wallet.currency()))
                .orElseGet(() -> policy.residentTax(wallet.currency()));
    }

    /**
     * The key for one town's own bill in one period.
     *
     * <p>The period is in the key rather than the table being cleared between runs, so a resumed
     * run recognises its own earlier work and the next period starts with a clean set of keys
     * regardless.</p>
     */
    private static String townKey(final String period, final TownId town) {
        return "tax:" + period + ":town:" + town.value();
    }

    /** The key for one town's resident sweep in one period. */
    private static String residentKey(final String period, final TownId town) {
        return "tax:" + period + ":residents:" + town.value();
    }
    /** Mutable running totals for one run. Confined to the chain that builds it. */
    private static final class Tally {
        private int townsCharged;
        private int residentsCharged;
        private final List<TownId> fallen = new ArrayList<>();
        private final List<String> fallenNames = new ArrayList<>();
    }
}
