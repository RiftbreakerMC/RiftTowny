package net.riftbreaker.rifttowny.domain.store;

import net.riftbreaker.rifttowny.domain.event.DomainEvent;
import net.riftbreaker.rifttowny.domain.org.Nation;
import net.riftbreaker.rifttowny.domain.org.NationId;
import net.riftbreaker.rifttowny.domain.org.Resident;
import net.riftbreaker.rifttowny.domain.org.ResidentId;
import net.riftbreaker.rifttowny.domain.org.Town;
import net.riftbreaker.rifttowny.domain.org.TownId;

import java.util.List;
import java.util.Optional;

/**
 * One open transaction, and everything reachable inside it.
 *
 * <p>Exists because the interesting changes span aggregates. Founding a town writes a resident row
 * and a town row; a town joining a nation writes both sides; disbanding releases every resident and
 * deletes the town. Doing those through the asynchronous repositories would be several independent
 * transactions, and a crash between them leaves a resident pointing at a town that no longer exists.</p>
 *
 * <p>The methods here are <strong>synchronous on purpose</strong>. They are only reachable from
 * inside {@link CivicStore#inTransaction}, which is already running off any server thread, so there
 * is no thread to block. Returning futures here would let a caller compose work that escapes the
 * transaction it was handed.</p>
 *
 * <p>Events published here are written to the outbox in the same transaction as the state change
 * they describe. That is what makes announcements exactly-once rather than best-effort: there is no
 * window in which the change happened but the event was never queued.</p>
 */
public interface CivicTransaction {

    ResidentStore residents();

    TownStore towns();

    NationStore nations();

    RoleStore roles();

    /**
     * Queues an event for delivery.
     *
     * @param correlationId groups related events — every event of one town founding, or one war
     */
    void publish(DomainEvent event, String correlationId);

    /** Queues several events under one correlation id. */
    default void publishAll(final Iterable<DomainEvent> events, final String correlationId) {
        for (final DomainEvent event : events) {
            publish(event, correlationId);
        }
    }

    /** Residents, inside the transaction. */
    interface ResidentStore {
        Optional<Resident> find(ResidentId id);

        Optional<Resident> findByName(String name);

        List<Resident> findByTown(TownId town);

        int countByTown(TownId town);

        void save(Resident resident);
    }

    /** Towns, inside the transaction. */
    interface TownStore {
        Optional<Town> find(TownId id);

        Optional<Town> findByName(String name);

        void save(Town town);

        /** Deletes the town and releases its residents. Claims, areas and trust cascade. */
        boolean delete(TownId id);
    }

    /**
     * Roles, inside the transaction.
     *
     * <p>Loaded and saved as a whole {@link net.riftbreaker.rifttowny.domain.role.RoleBook}: its
     * rules are about the set — unique priorities, who outranks whom, which assignments survive a
     * deletion — so a partial write is not a smaller change, it is an inconsistent one.</p>
     */
    interface RoleStore {
        Optional<net.riftbreaker.rifttowny.domain.role.RoleBook> find(
                net.riftbreaker.rifttowny.domain.org.OrganisationScope scope,
                java.util.UUID organisationId);

        void save(net.riftbreaker.rifttowny.domain.role.RoleBook book);

        boolean delete(
                net.riftbreaker.rifttowny.domain.org.OrganisationScope scope,
                java.util.UUID organisationId);
    }

    /** Nations, inside the transaction. */
    interface NationStore {
        Optional<Nation> find(NationId id);

        Optional<Nation> findByName(String name);

        void save(Nation nation);

        /** Deletes the nation and releases its towns. */
        boolean delete(NationId id);
    }
}
