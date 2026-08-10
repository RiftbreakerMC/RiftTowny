package net.riftbreaker.rifttowny.domain.service;

import net.riftbreaker.rifttowny.domain.event.DomainEvent;
import net.riftbreaker.rifttowny.domain.naming.NameCheck;
import net.riftbreaker.rifttowny.domain.naming.NamePolicy;
import net.riftbreaker.rifttowny.domain.naming.OrganisationName;
import net.riftbreaker.rifttowny.domain.org.ChangeDenial;
import net.riftbreaker.rifttowny.domain.org.Outcome;
import net.riftbreaker.rifttowny.domain.org.Resident;
import net.riftbreaker.rifttowny.domain.org.ResidentId;
import net.riftbreaker.rifttowny.domain.org.Town;
import net.riftbreaker.rifttowny.domain.org.TownId;
import net.riftbreaker.rifttowny.domain.store.ChangeRefusedException;
import net.riftbreaker.rifttowny.domain.store.CivicStore;
import net.riftbreaker.rifttowny.domain.store.CivicTransaction;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Town lifecycle.
 *
 * <p>Every operation runs inside one transaction, and the events it produces are queued in that
 * same transaction. Nothing here half-happens: a founding that fails leaves no resident pointing at
 * a town that does not exist, and no announcement describing one.</p>
 *
 * <p>Uniqueness is checked <em>inside</em> the transaction rather than before it. Checking first
 * would leave a window in which two founders both saw the name free; the unique constraint on
 * {@code name_normalised} is the real guard, and the check here is how it becomes a message instead
 * of a stack trace.</p>
 *
 * <p><strong>Not yet enforced here: authority.</strong> These methods check membership invariants
 * only. Who is <em>allowed</em> to kick, rename or disband is a role question, and roles are
 * {@code RT-CORE-ROLE}, still to be built. Until then the caller is the only gate, which is why no
 * command is wired to these yet.</p>
 */
public final class TownService {

    private final CivicStore store;
    private final NamePolicy namePolicy;
    private final Clock clock;

    public TownService(final CivicStore store, final NamePolicy namePolicy, final Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.namePolicy = Objects.requireNonNull(namePolicy, "namePolicy");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Founds a town.
     *
     * <p>The founder becomes its sole resident and mayor. The civic account id is generated here and
     * never changes again, which is what lets the town be renamed and hand over its mayoralty
     * without orphaning its treasury.</p>
     *
     * @param founderName the founder's current Minecraft name, recorded if they are new to RiftTowny
     */
    public CompletableFuture<ServiceResult<Town>> found(
            final ResidentId founder, final String founderName, final String rawName) {
        Objects.requireNonNull(founder, "founder");
        Objects.requireNonNull(founderName, "founderName");

        final NameCheck check = namePolicy.check(rawName);
        if (!(check instanceof NameCheck.Accepted accepted)) {
            return completed(ServiceResult.nameRejected(check.problems()));
        }
        final OrganisationName name = accepted.name();

        return transaction(transaction -> {
            if (transaction.towns().findByName(name.normalised()).isPresent()) {
                throw new ChangeRefusedException(ChangeDenial.NAME_TAKEN);
            }

            // The id is minted before the founder is asked to join it, because joinTown is where
            // the one-town-per-resident rule lives and it needs a target. Minting an id for a
            // founding that is then refused costs nothing; a UUID is not a scarce resource.
            final TownId id = TownId.random();
            final Resident resident = transaction.residents().find(founder)
                    .orElseGet(() -> Resident.newcomer(founder, founderName, clock.instant()));
            final Resident joined = require(resident.joinTown(id));

            // Written in this order deliberately: Town.restore refuses a mayor who is not one of
            // its residents, so the resident row has to exist before the town can be read back.
            final Town town = Town.found(id, name, founder, UUID.randomUUID(), clock.instant());
            transaction.residents().save(joined);
            transaction.towns().save(town);
            transaction.publish(new DomainEvent.TownFounded(id, name, founder), correlation("found", id));
            return town;
        });
    }

    /** Adds a resident to a town. */
    public CompletableFuture<ServiceResult<Town>> join(final ResidentId who, final TownId townId) {
        Objects.requireNonNull(who, "who");
        Objects.requireNonNull(townId, "townId");

        return transaction(transaction -> {
            final Town town = town(transaction, townId);
            final Resident resident = resident(transaction, who);

            final Resident joined = require(resident.joinTown(townId));
            final Outcome<Town> admitted = town.admit(who);
            final Town updated = require(admitted);

            transaction.residents().save(joined);
            transaction.towns().save(updated);
            transaction.publishAll(admitted.events(), correlation("join", townId));
            return updated;
        });
    }

    /** Removes a resident, whether they left or were removed. */
    public CompletableFuture<ServiceResult<Town>> leave(
            final ResidentId who, final TownId townId, final boolean voluntary) {
        Objects.requireNonNull(who, "who");
        Objects.requireNonNull(townId, "townId");

        return transaction(transaction -> {
            final Town town = town(transaction, townId);
            final Resident resident = resident(transaction, who);

            final Outcome<Town> released = town.release(who, voluntary);
            final Town updated = require(released);
            final Resident departed = require(resident.leaveTown());

            transaction.residents().save(departed);
            transaction.towns().save(updated);
            transaction.publishAll(released.events(), correlation("leave", townId));
            return updated;
        });
    }

    /** Hands the mayoralty to another resident. */
    public CompletableFuture<ServiceResult<Town>> transferMayoralty(
            final TownId townId, final ResidentId candidate) {
        Objects.requireNonNull(townId, "townId");
        Objects.requireNonNull(candidate, "candidate");

        return transaction(transaction -> {
            final Outcome<Town> transferred = town(transaction, townId).transferLeadership(candidate);
            final Town updated = require(transferred);
            transaction.towns().save(updated);
            transaction.publishAll(transferred.events(), correlation("leadership", townId));
            return updated;
        });
    }

    /** Renames a town, keeping its id and civic account. */
    public CompletableFuture<ServiceResult<Town>> rename(final TownId townId, final String rawName) {
        Objects.requireNonNull(townId, "townId");

        final NameCheck check = namePolicy.check(rawName);
        if (!(check instanceof NameCheck.Accepted accepted)) {
            return completed(ServiceResult.nameRejected(check.problems()));
        }
        final OrganisationName name = accepted.name();

        return transaction(transaction -> {
            final Town town = town(transaction, townId);

            // A town may keep its own normalised name across a recapitalisation, so a match on a
            // different id is the only real conflict.
            final Optional<Town> holder = transaction.towns().findByName(name.normalised());
            if (holder.isPresent() && !holder.get().id().equals(townId)) {
                throw new ChangeRefusedException(ChangeDenial.NAME_TAKEN);
            }

            final Outcome<Town> renamed = town.renameTo(name);
            final Town updated = require(renamed);
            transaction.towns().save(updated);
            transaction.publishAll(renamed.events(), correlation("rename", townId));
            return updated;
        });
    }

    /**
     * Disbands a town.
     *
     * <p>Residents are released, not deleted. Claims, areas and trust rows cascade with the town.
     * Settling the civic account is {@code RT-MOD-BANK}'s job and is not done here, so a server with
     * banking enabled must not call this directly until that module lands.</p>
     */
    public CompletableFuture<ServiceResult<TownId>> disband(final TownId townId) {
        Objects.requireNonNull(townId, "townId");

        return transaction(transaction -> {
            final Town town = town(transaction, townId);
            int released = 0;
            for (final Resident resident : transaction.residents().findByTown(townId)) {
                transaction.residents().save(require(resident.leaveTown()));
                released++;
            }
            transaction.towns().delete(townId);
            transaction.publish(
                    new DomainEvent.TownDisbanded(townId, town.name(), released),
                    correlation("disband", townId));
            return townId;
        });
    }

    private static Town town(final CivicTransaction transaction, final TownId id) {
        return transaction.towns().find(id)
                .orElseThrow(() -> new ChangeRefusedException(ChangeDenial.TOWN_NOT_FOUND));
    }

    private static Resident resident(final CivicTransaction transaction, final ResidentId id) {
        return transaction.residents().find(id)
                .orElseThrow(() -> new ChangeRefusedException(ChangeDenial.RESIDENT_NOT_FOUND));
    }

    /**
     * Unwraps an outcome, turning a denial into the throw that aborts the transaction.
     *
     * <p>This is the only correct way to refuse inside a transaction: returning a denial would let
     * the store commit whatever had already been written.</p>
     */
    private static <T> T require(final Outcome<T> outcome) {
        return outcome.value().orElseThrow(() ->
                new ChangeRefusedException(outcome.denial().orElseThrow()));
    }

    private static String correlation(final String action, final TownId town) {
        return action + ':' + town.value();
    }

    private static <T> CompletableFuture<ServiceResult<T>> completed(final ServiceResult<T> result) {
        return CompletableFuture.completedFuture(result);
    }

    /**
     * Runs work in a transaction and turns a refusal back into a result.
     *
     * <p>The exception exists to roll the transaction back; it is not how a caller should learn that
     * a player is already in a town. Catching it here keeps the command layer free of try/catch.</p>
     */
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
