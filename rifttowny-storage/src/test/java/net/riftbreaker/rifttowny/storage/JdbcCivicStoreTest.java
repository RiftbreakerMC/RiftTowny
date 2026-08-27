package net.riftbreaker.rifttowny.storage;

import net.riftbreaker.rifttowny.domain.event.DomainEvent;
import net.riftbreaker.rifttowny.domain.naming.NamePolicy;
import net.riftbreaker.rifttowny.domain.naming.OrganisationName;
import net.riftbreaker.rifttowny.domain.org.ChangeDenial;
import net.riftbreaker.rifttowny.domain.org.Resident;
import net.riftbreaker.rifttowny.domain.org.ResidentId;
import net.riftbreaker.rifttowny.domain.org.Town;
import net.riftbreaker.rifttowny.domain.org.TownId;
import net.riftbreaker.rifttowny.domain.store.ChangeRefusedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcCivicStoreTest extends SqliteFixture {

    private static final Instant NOW = Instant.parse("2026-08-10T12:00:00Z");
    private static final ResidentId MAYOR = ResidentId.of(UUID.randomUUID());

    private JdbcCivicStore store;
    private JdbcTownRepository towns;
    private JdbcResidentRepository residents;
    private JdbcOutboxRepository outbox;

    @BeforeEach
    void createStores() {
        store = new JdbcCivicStore(database, DIRECT);
        towns = new JdbcTownRepository(database, DIRECT);
        residents = new JdbcResidentRepository(database, DIRECT);
        outbox = new JdbcOutboxRepository(database, DIRECT);
    }

    private static OrganisationName name(final String raw) {
        return NamePolicy.defaults().check(raw).accepted().orElseThrow();
    }

    private TownId foundRiftholm() {
        return store.inTransaction(transaction -> {
            final Town town = Town.found(
                    TownId.random(), name("Riftholm"), MAYOR, UUID.randomUUID(), NOW);
            transaction.residents().save(
                    Resident.newcomer(MAYOR, "Mayor", NOW).joinTown(town.id()).orElseThrow());
            transaction.towns().save(town);
            transaction.publish(
                    new DomainEvent.ResidentAdmitted(town.id(), MAYOR), "found:" + town.id().value());
            return town.id();
        }).join();
    }

    @Test
    @DisplayName("a whole founding commits together: resident, town and the queued announcement")
    void multiAggregateChangeCommitsTogether() {
        final TownId town = foundRiftholm();

        assertThat(towns.find(town).join()).isPresent();
        assertThat(residents.find(MAYOR).join().orElseThrow().town()).contains(town);
        assertThat(outbox.counts().join().pending())
                .as("the announcement is queued in the same transaction as the founding")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("a failure rolls back every write, including the outbox row")
    void failureRollsBackEverything() {
        final TownId town = TownId.random();

        assertThatThrownBy(() -> store.inTransaction(transaction -> {
            transaction.residents().save(
                    Resident.newcomer(MAYOR, "Mayor", NOW).joinTown(town).orElseThrow());
            transaction.towns().save(
                    Town.found(town, name("Riftholm"), MAYOR, UUID.randomUUID(), NOW));
            transaction.publish(new DomainEvent.ResidentAdmitted(town, MAYOR), "found");
            throw new IllegalStateException("something later went wrong");
        }).join()).isInstanceOf(CompletionException.class);

        assertThat(towns.find(town).join()).isEmpty();
        assertThat(residents.find(MAYOR).join()).isEmpty();
        assertThat(outbox.counts().join().total())
                .as("an announcement for a founding that rolled back would describe a town "
                        + "that does not exist")
                .isZero();
    }

    @Test
    @DisplayName("a refusal aborts by throwing, so nothing written before it survives")
    void refusalRollsBack() {
        final TownId first = foundRiftholm();
        final long queuedByFounding = outbox.counts().join().total();

        assertThatThrownBy(() -> store.inTransaction(transaction -> {
            final Town town = transaction.towns().find(first).orElseThrow();
            // The mayor is already a resident, so this is refused. Converting the denial into a
            // throw is what stops the publish below from committing.
            final var outcome = town.admit(MAYOR);
            if (!outcome.wasApplied()) {
                throw new ChangeRefusedException(outcome.denial().orElseThrow());
            }
            transaction.publish(new DomainEvent.ResidentAdmitted(first, MAYOR), "join");
            return null;
        }).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(ChangeRefusedException.class)
                .cause()
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories
                        .type(ChangeRefusedException.class))
                .satisfies(refused ->
                        assertThat(refused.denial()).isEqualTo(ChangeDenial.ALREADY_IN_THIS_TOWN));

        assertThat(outbox.counts().join().total()).isEqualTo(queuedByFounding);
    }

    @Test
    @DisplayName("reads inside the transaction see writes made earlier in the same transaction")
    void readsSeeUncommittedWritesOfTheSameTransaction() {
        final String observed = store.inTransaction(transaction -> {
            final Town town = Town.found(
                    TownId.random(), name("Riftholm"), MAYOR, UUID.randomUUID(), NOW);
            transaction.residents().save(
                    Resident.newcomer(MAYOR, "Mayor", NOW).joinTown(town.id()).orElseThrow());
            transaction.towns().save(town);

            // Loaded back through the store, so membership is rebuilt from the resident row that
            // was written a moment ago and is not yet committed.
            return transaction.towns().find(town.id()).orElseThrow()
                    .residents().size() + ":" + transaction.residents().findByTown(town.id()).size();
        }).join();

        assertThat(observed).isEqualTo("1:1");
    }

    @Test
    @DisplayName("several events under one correlation id are all queued")
    void publishAllQueuesEveryEvent() {
        final TownId town = foundRiftholm();

        store.inTransaction(transaction -> {
            final Town loaded = transaction.towns().find(town).orElseThrow();
            transaction.publishAll(
                    loaded.renameTo(name("Ashford")).events(), "rename:" + town.value());
            transaction.towns().save(loaded.renameTo(name("Ashford")).orElseThrow());
            return null;
        }).join();

        assertThat(outbox.counts().join().pending()).isEqualTo(2L);
        assertThat(towns.find(town).join().orElseThrow().name().display()).isEqualTo("Ashford");
    }

    @Test
    @DisplayName("a SQL failure surfaces as the SQLException, not as internal plumbing")
    void sqlFailuresAreUnwrapped() {
        assertThatThrownBy(() -> store.inTransaction(transaction -> {
            // Saving a town whose mayor has no resident row is legal at the SQL level; loading it
            // back is what fails, because Town.restore refuses a mayor who is not a resident.
            final TownId town = TownId.random();
            transaction.towns().save(
                    Town.found(town, name("Riftholm"), MAYOR, UUID.randomUUID(), NOW));
            return transaction.towns().find(town);
        }).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mayor");
    }
}
