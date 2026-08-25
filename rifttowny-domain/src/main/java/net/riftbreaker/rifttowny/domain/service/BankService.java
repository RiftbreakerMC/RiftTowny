package net.riftbreaker.rifttowny.domain.service;

import net.riftbreaker.rifttowny.domain.bank.LedgerEntry;
import net.riftbreaker.rifttowny.domain.bank.Money;
import net.riftbreaker.rifttowny.domain.bank.PlayerWallet;
import net.riftbreaker.rifttowny.domain.org.ChangeDenial;
import net.riftbreaker.rifttowny.domain.org.Nation;
import net.riftbreaker.rifttowny.domain.org.NationId;
import net.riftbreaker.rifttowny.domain.org.OrganisationScope;
import net.riftbreaker.rifttowny.domain.org.ResidentId;
import net.riftbreaker.rifttowny.domain.org.Town;
import net.riftbreaker.rifttowny.domain.org.TownId;
import net.riftbreaker.rifttowny.domain.role.Permission;
import net.riftbreaker.rifttowny.domain.role.RoleBook;
import net.riftbreaker.rifttowny.domain.store.ChangeRefusedException;
import net.riftbreaker.rifttowny.domain.store.CivicStore;
import net.riftbreaker.rifttowny.domain.store.CivicTransaction;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * A town's or nation's money.
 *
 * <p>The civic side is entirely ours: the balance, the history, and every rule about who may move
 * what. It works with no economy plugin installed, because an organisation's treasury is civic
 * state in the same way its membership is.</p>
 *
 * <p>The <em>player</em> side is not ours, and that is the whole of {@link PlayerWallet}. Which
 * means the two halves of a deposit have to be ordered, and the order matters: the money leaves the
 * player first, and only if that succeeded does it arrive in the town. The other way round would
 * credit a town from a player who turned out not to have it.</p>
 *
 * <p><strong>This is not a two-phase commit and does not pretend to be.</strong> The wallet is
 * another plugin's storage; a crash between taking from the player and committing the civic side
 * loses that transaction. The window is one database write wide, the direction of the loss is the
 * safe one — a player is never credited with money nobody paid — and the ledger records what did
 * commit, which is what an operator needs to put it right by hand.</p>
 */
public final class BankService {

    /** How many movements a history listing shows before it stops being a listing. */
    public static final int DEFAULT_HISTORY = 10;

    private final CivicStore store;
    private final Clock clock;
    private final PlayerWallet wallet;

    public BankService(final CivicStore store, final Clock clock, final PlayerWallet wallet) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.wallet = Objects.requireNonNull(wallet, "wallet");
    }

    /** Whether money can move between players and organisations at all. */
    public boolean economyAvailable() {
        return wallet.available();
    }

    public String currency() {
        return wallet.currency();
    }

    /** What a town holds. Zero for one that has never held anything. */
    public CompletableFuture<Money> balanceOf(final TownId townId) {
        Objects.requireNonNull(townId, "townId");
        return store.inTransaction(transaction -> {
            return currentBalance(transaction, of(townId).in(transaction).id());
        });
    }

    /** The most recent movements, newest first. */
    public CompletableFuture<List<LedgerEntry>> historyOf(final TownId townId, final int limit) {
        Objects.requireNonNull(townId, "townId");
        return store.inTransaction(transaction -> {
            return transaction.bank().history(of(townId).in(transaction).id(), limit);
        });
    }

    /**
     * A player putting their own money into the town.
     *
     * <p>Needs {@link Permission#BANK_DEPOSIT}, which every member holds by default — a town that
     * refused donations from its own residents would be an odd town, but a probationary role that
     * cannot is a thing a town might want.</p>
     */
    public CompletableFuture<ServiceResult<Money>> deposit(
            final ResidentId actor, final TownId townId, final Money amount) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(townId, "townId");
        Objects.requireNonNull(amount, "amount");

        if (!wallet.available()) {
            return completed(ServiceResult.refused(ChangeDenial.NO_ECONOMY));
        }
        if (amount.isZero()) {
            return completed(ServiceResult.refused(ChangeDenial.AMOUNT_MUST_BE_POSITIVE));
        }

        // Checked before the player's money is touched, so a refusal costs them nothing. Checked
        // again inside the transaction below, because this one is advisory - the authoritative
        // answer is the one taken while the row is held.
        return permitted(actor, townId, Permission.BANK_DEPOSIT).thenCompose(allowed -> {
            if (!allowed) {
                return completed(ServiceResult.<Money>refused(ChangeDenial.MISSING_PERMISSION));
            }
            return wallet.take(actor, amount).thenCompose(taken -> {
                if (!taken) {
                    return completed(ServiceResult.<Money>refused(ChangeDenial.INSUFFICIENT_FUNDS));
                }
                return credit(actor, of(townId), amount, LedgerEntry.Reason.DEPOSIT, null)
                        .thenCompose(result -> refundIfRefused(actor, amount, result));
            });
        });
    }

    /**
     * A player taking money out.
     *
     * <p>Needs {@link Permission#BANK_WITHDRAW}, which is sensitive and held by no default role but
     * the leader's: the town's money is the one thing a single misjudged role grant can empty.</p>
     */
    public CompletableFuture<ServiceResult<Money>> withdraw(
            final ResidentId actor, final TownId townId, final Money amount) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(townId, "townId");
        Objects.requireNonNull(amount, "amount");

        if (!wallet.available()) {
            return completed(ServiceResult.refused(ChangeDenial.NO_ECONOMY));
        }
        if (amount.isZero()) {
            return completed(ServiceResult.refused(ChangeDenial.AMOUNT_MUST_BE_POSITIVE));
        }

        // Debited first, and the player is paid only once it has committed. A failure to pay them
        // afterwards leaves the money in the town, which is recoverable; paying first and failing to
        // debit would create money.
        return debit(actor, of(townId), amount, LedgerEntry.Reason.WITHDRAWAL, null)
                .thenCompose(result -> {
                    if (!result.succeeded()) {
                        return completed(result);
                    }
                    return wallet.give(actor, amount).thenCompose(given -> given
                            ? completed(result)
                            // Put back, in its own transaction, and recorded: the ledger is what an
                            // operator reads when a player says the money vanished.
                            : credit(actor, of(townId), amount, LedgerEntry.Reason.ADMIN,
                                    "returned: wallet refused payment")
                            .thenApply(ignored ->
                                    ServiceResult.<Money>refused(ChangeDenial.ECONOMY_REFUSED)));
                });
    }

    /**
     * Charges a town for something, with no player involved.
     *
     * <p>The path taxes, upkeep and claim costs will use. No permission check: the caller is the
     * system, and a resident who could not afford the claim was already refused by the command that
     * asked for it.</p>
     */
    public CompletableFuture<ServiceResult<Money>> charge(
            final TownId townId,
            final Money amount,
            final LedgerEntry.Reason reason,
            final String detail
    ) {
        return debit(null, of(townId), amount, reason, detail);
    }

    /** Pays a town, with no player involved. */
    public CompletableFuture<ServiceResult<Money>> pay(
            final TownId townId,
            final Money amount,
            final LedgerEntry.Reason reason,
            final String detail
    ) {
        return credit(null, of(townId), amount, reason, detail);
    }


    // --- the same, for a nation ------------------------------------------------------------------

    /**
     * What a nation holds.
     *
     * <p>Nations have had a treasury since taxes did: the nation share of a town's tax bill is
     * credited to {@code nation.bankAccountId()} in the same transaction that takes it. Until now
     * nothing could show that money or spend it, so it accumulated where nobody could see it — which
     * is the worst state for a balance to be in, because it is indistinguishable from a tax that is
     * quietly not working.</p>
     */
    public CompletableFuture<Money> balanceOf(final NationId nationId) {
        Objects.requireNonNull(nationId, "nationId");
        return store.inTransaction(transaction ->
                currentBalance(transaction, of(nationId).in(transaction).id()));
    }

    /** The most recent movements, newest first. */
    public CompletableFuture<List<LedgerEntry>> historyOf(final NationId nationId, final int limit) {
        Objects.requireNonNull(nationId, "nationId");
        return store.inTransaction(transaction ->
                transaction.bank().history(of(nationId).in(transaction).id(), limit));
    }

    /**
     * A citizen putting their own money into the nation.
     *
     * <p>{@link Permission#BANK_DEPOSIT}, resolved against the <em>nation's</em> role book and the
     * actor's standing in it — which needs their town, because a nation has no residents of its own.
     * Everything else is the town path exactly: the wallet is debited first, and the money is handed
     * back if the civic half then refuses.</p>
     */
    public CompletableFuture<ServiceResult<Money>> deposit(
            final ResidentId actor, final NationId nationId, final Money amount) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(nationId, "nationId");
        Objects.requireNonNull(amount, "amount");

        if (!wallet.available()) {
            return completed(ServiceResult.refused(ChangeDenial.NO_ECONOMY));
        }
        if (amount.isZero()) {
            return completed(ServiceResult.refused(ChangeDenial.AMOUNT_MUST_BE_POSITIVE));
        }
        return permitted(actor, nationId, Permission.BANK_DEPOSIT).thenCompose(allowed -> {
            if (!allowed) {
                return completed(ServiceResult.<Money>refused(ChangeDenial.MISSING_PERMISSION));
            }
            return wallet.take(actor, amount).thenCompose(taken -> {
                if (!taken) {
                    return completed(ServiceResult.<Money>refused(ChangeDenial.INSUFFICIENT_FUNDS));
                }
                return credit(actor, of(nationId), amount, LedgerEntry.Reason.DEPOSIT, null)
                        .thenCompose(result -> refundIfRefused(actor, amount, result));
            });
        });
    }

    /**
     * A citizen taking money out.
     *
     * <p>{@link Permission#BANK_WITHDRAW}, which no default role but the king's holds — and it is
     * worth more care in a nation than in a town, because the treasury a king can empty was paid for
     * by every member town's taxes rather than by the people standing in front of them.</p>
     */
    public CompletableFuture<ServiceResult<Money>> withdraw(
            final ResidentId actor, final NationId nationId, final Money amount) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(nationId, "nationId");
        Objects.requireNonNull(amount, "amount");

        if (!wallet.available()) {
            return completed(ServiceResult.refused(ChangeDenial.NO_ECONOMY));
        }
        if (amount.isZero()) {
            return completed(ServiceResult.refused(ChangeDenial.AMOUNT_MUST_BE_POSITIVE));
        }
        return debit(actor, of(nationId), amount, LedgerEntry.Reason.WITHDRAWAL, null)
                .thenCompose(result -> {
                    if (!result.succeeded()) {
                        return completed(result);
                    }
                    return wallet.give(actor, amount).thenCompose(given -> given
                            ? completed(result)
                            : credit(actor, of(nationId), amount, LedgerEntry.Reason.ADMIN,
                                    "returned: wallet refused payment")
                            .thenApply(ignored ->
                                    ServiceResult.<Money>refused(ChangeDenial.ECONOMY_REFUSED)));
                });
    }

    /** Charges a nation for something, with no player involved. */
    public CompletableFuture<ServiceResult<Money>> charge(
            final NationId nationId,
            final Money amount,
            final LedgerEntry.Reason reason,
            final String detail
    ) {
        return debit(null, of(nationId), amount, reason, detail);
    }

    /** Pays a nation, with no player involved. */
    public CompletableFuture<ServiceResult<Money>> pay(
            final NationId nationId,
            final Money amount,
            final LedgerEntry.Reason reason,
            final String detail
    ) {
        return credit(null, of(nationId), amount, reason, detail);
    }
    /** Forgets a disbanded organisation's money. Settlement is the caller's decision, not this. */
    public CompletableFuture<Integer> forget(final UUID accountId) {
        Objects.requireNonNull(accountId, "accountId");
        return store.inTransaction(transaction -> transaction.bank().forget(accountId));
    }

    // --- internals -----------------------------------------------------------------------------

    /**
     * Whose money this is.
     *
     * <p>The ledger has always been keyed on a plain account id, so the storage never cared whether
     * a treasury belonged to a town or a nation. Only this service did, and only because every
     * method named a {@code TownId}. Resolving to an account instead is what lets one credit and one
     * debit serve both — the alternative was a second copy of the money-moving code, which is the
     * last place in the plugin where two implementations should be allowed to drift apart.</p>
     *
     * @param id the ledger key
     * @param gate throws if this actor may not move this money; a system movement never calls it
     */
    private record Account(UUID id, PermissionGate gate) {
    }

    @FunctionalInterface
    private interface PermissionGate {
        void require(ResidentId actor, Permission permission);
    }

    /** Resolved inside the transaction, because the town it names may be gone by the time it runs. */
    @FunctionalInterface
    private interface Holder {
        Account in(CivicTransaction transaction);
    }

    private static Holder of(final TownId townId) {
        Objects.requireNonNull(townId, "townId");
        return transaction -> {
            final Town town = CivicPermissions.town(transaction, townId);
            return new Account(town.bankAccountId(), (actor, permission) ->
                    CivicPermissions.requireTown(transaction, town, actor, permission));
        };
    }

    private static Holder of(final NationId nationId) {
        Objects.requireNonNull(nationId, "nationId");
        return transaction -> {
            final Nation nation = CivicPermissions.nation(transaction, nationId);
            return new Account(nation.bankAccountId(), (actor, permission) ->
                    CivicPermissions.requireNation(transaction, nationId, actor, permission));
        };
    }

    private CompletableFuture<ServiceResult<Money>> credit(
            final ResidentId actor,
            final Holder holder,
            final Money amount,
            final LedgerEntry.Reason reason,
            final String detail
    ) {
        return transaction(transaction -> {
            final Account account = holder.in(transaction);
            final Money before = currentBalance(transaction, account.id());
            final Money after = before.plus(amount);
            transaction.bank().record(
                    LedgerEntry.of(account.id(), amount, after, reason, actor, detail,
                            clock.instant()),
                    clock.instant());
            return after;
        });
    }

    private CompletableFuture<ServiceResult<Money>> debit(
            final ResidentId actor,
            final Holder holder,
            final Money amount,
            final LedgerEntry.Reason reason,
            final String detail
    ) {
        return transaction(transaction -> {
            final Account account = holder.in(transaction);
            if (actor != null) {
                account.gate().require(actor, Permission.BANK_WITHDRAW);
            }
            final Money before = currentBalance(transaction, account.id());
            final Money after = before.minus(amount)
                    .orElseThrow(() ->
                            new ChangeRefusedException(ChangeDenial.INSUFFICIENT_CIVIC_FUNDS));
            transaction.bank().record(
                    LedgerEntry.of(account.id(), amount, after, reason, actor, detail,
                            clock.instant()),
                    clock.instant());
            return after;
        });
    }

    /**
     * Gives a player their money back when the civic side refused after the wallet had already
     * taken it.
     *
     * <p>The one place the ordering can strand money, so it is unwound explicitly rather than left
     * to a caller who may not know it happened.</p>
     */
    private CompletableFuture<ServiceResult<Money>> refundIfRefused(
            final ResidentId actor, final Money amount, final ServiceResult<Money> result) {
        if (result.succeeded()) {
            return completed(result);
        }
        return wallet.give(actor, amount).thenApply(ignored -> result);
    }

    private Money currentBalance(final CivicTransaction transaction, final UUID accountId) {
        return transaction.bank().balance(accountId, wallet.currency())
                .orElseGet(() -> Money.zero(wallet.currency()));
    }

    /**
     * The advisory check, made before a player's wallet is touched.
     *
     * <p>Not the authoritative one: that is taken inside the transaction, while the row is held. A
     * refusal here costs the player nothing, which is the whole reason it exists.</p>
     */
    private CompletableFuture<Boolean> permitted(
            final ResidentId actor, final TownId townId, final Permission permission) {
        return store.inTransaction(transaction -> CivicPermissions.allowsTown(
                transaction, CivicPermissions.town(transaction, townId), actor, permission));
    }

    private CompletableFuture<Boolean> permitted(
            final ResidentId actor, final NationId nationId, final Permission permission) {
        return store.inTransaction(transaction -> CivicPermissions.allowsNation(
                transaction, CivicPermissions.nation(transaction, nationId), actor, permission));
    }

    private static <T> CompletableFuture<ServiceResult<T>> completed(final ServiceResult<T> result) {
        return CompletableFuture.completedFuture(result);
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
