package net.riftbreaker.rifttowny.domain.store;

import net.riftbreaker.rifttowny.domain.event.DomainEvent;
import net.riftbreaker.rifttowny.domain.org.Nation;
import net.riftbreaker.rifttowny.domain.org.NationId;
import net.riftbreaker.rifttowny.domain.org.OrganisationId;
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

    ClaimStore claims();

    FlagStore flags();

    InvitationStore invitations();

    RuinStore ruins();

    SpawnStore spawns();

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

        /**
         * Every town on the server, oldest first.
         *
         * <p>For filling the in-memory civic cache at startup, and nothing else. It reads every town
         * row and each town's residents and trust list, so anything calling it per command or per
         * event is wrong.</p>
         */
        List<Town> all();

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

    /**
     * Claims, inside the transaction.
     *
     * <p>Written one chunk at a time rather than as a whole {@code TownClaims}, unlike roles. A
     * role book has a handful of entries; a town's territory can be thousands of chunks, and
     * rewriting all of them to claim one would turn an ordinary command into a table scan.</p>
     */
    interface ClaimStore {

        /** Whoever owns this chunk, if anybody. Not scoped to one town. */
        Optional<net.riftbreaker.rifttowny.domain.territory.Claim> at(
                net.riftbreaker.rifttowny.api.ChunkKey chunk);

        /** Every claim of one town, oldest first. */
        List<net.riftbreaker.rifttowny.domain.territory.Claim> of(
                net.riftbreaker.rifttowny.domain.org.TownId town);

        /**
         * Every claim on the server.
         *
         * <p>For loading the in-memory index at startup, and nothing else. It is a full table read,
         * so anything calling it per command or per event is wrong.</p>
         */
        List<net.riftbreaker.rifttowny.domain.territory.Claim> all();

        void insert(net.riftbreaker.rifttowny.domain.territory.Claim claim);

        boolean delete(net.riftbreaker.rifttowny.api.ChunkKey chunk);

        void updateKind(
                net.riftbreaker.rifttowny.api.ChunkKey chunk,
                net.riftbreaker.rifttowny.domain.territory.ClaimKind kind);

        /** Releases a whole town's territory. Returns how many chunks went. */
        int deleteAllOf(net.riftbreaker.rifttowny.domain.org.TownId town);
    }

    /**
     * Configured flag overrides, inside the transaction.
     *
     * <p>Written one opinion at a time rather than as a whole layer. A layer is partial by design —
     * an absent entry means "no opinion" — so replacing one wholesale to change a single flag would
     * turn every unrelated opinion into a deliberate deletion.</p>
     */
    interface FlagStore {

        /**
         * Every override on the server.
         *
         * <p>For filling the in-memory set at startup, and nothing else. Anything calling it per
         * command is wrong.</p>
         */
        List<net.riftbreaker.rifttowny.domain.flag.FlagOverride> all();

        /** Everything one target holds. */
        List<net.riftbreaker.rifttowny.domain.flag.FlagOverride> of(
                net.riftbreaker.rifttowny.domain.flag.FlagTarget target);

        /** Records one opinion, replacing any existing one for the same flag and relationship. */
        void set(net.riftbreaker.rifttowny.domain.flag.FlagOverride override);

        /** Removes one opinion. Returns whether there was one. */
        boolean clear(
                net.riftbreaker.rifttowny.domain.flag.FlagTarget target,
                net.riftbreaker.rifttowny.domain.flag.ProtectionFlag flag,
                net.riftbreaker.rifttowny.domain.flag.Relationship relationship);

        /** Removes everything a target holds. Returns how many went. */
        int clearAll(net.riftbreaker.rifttowny.domain.flag.FlagTarget target);
    }

    /**
     * Outstanding invitations, inside the transaction.
     *
     * <p>Read and consumed in the same transaction as the join they authorise. Checking for an
     * invitation, committing, then deleting it separately would leave a window in which one offer
     * could be accepted twice.</p>
     */
    interface InvitationStore {

        /** The offer from this organisation to this invitee, if one stands. */
        Optional<net.riftbreaker.rifttowny.domain.org.Invitation> find(
                OrganisationId inviter, net.riftbreaker.rifttowny.domain.org.Invitation.Invitee invitee);

        /** Every offer addressed to one invitee, newest first. */
        List<net.riftbreaker.rifttowny.domain.org.Invitation> to(
                net.riftbreaker.rifttowny.domain.org.Invitation.Invitee invitee);

        /** Every offer one organisation has outstanding. */
        List<net.riftbreaker.rifttowny.domain.org.Invitation> from(OrganisationId inviter);

        /** Records an offer, refreshing one that already exists for the same pairing. */
        void save(net.riftbreaker.rifttowny.domain.org.Invitation invitation);

        /** Withdraws or consumes one. Returns whether there was one. */
        boolean delete(
                OrganisationId inviter, net.riftbreaker.rifttowny.domain.org.Invitation.Invitee invitee);

        /** Removes everything an organisation offered or was offered, as when it is disbanded. */
        int deleteAllFor(OrganisationId organisation);

        /** Sweeps lapsed offers. Returns how many went. */
        int deleteExpired(java.time.Instant now);
    }

    /**
     * Ruins, inside the transaction.
     *
     * <p>The ruin row and the chunks it holds have different lifetimes: the chunks go when the ruin
     * lapses or is taken on, the row stays for as long as anything might ask what stood there. Both
     * are here so a fall, a reclaim and an expiry are each one transaction.</p>
     */
    interface RuinStore {

        Optional<net.riftbreaker.rifttowny.domain.territory.Ruin> find(java.util.UUID ruinId);

        /** The ruin holding this chunk, if one does. */
        Optional<net.riftbreaker.rifttowny.domain.territory.Ruin> at(
                net.riftbreaker.rifttowny.api.ChunkKey chunk);

        /**
         * Every ruin that still holds land, with the chunks it holds.
         *
         * <p>For filling the in-memory index at startup. Lapsed and reclaimed ruins are excluded:
         * their rows survive as history, but they own nothing.</p>
         */
        java.util.Map<net.riftbreaker.rifttowny.domain.territory.Ruin,
                java.util.Set<net.riftbreaker.rifttowny.api.ChunkKey>> standing();

        /** Every ruin whose window has closed, for the sweep. */
        List<net.riftbreaker.rifttowny.domain.territory.Ruin> lapsed(java.time.Instant now);

        /** The chunks one ruin holds. */
        java.util.Set<net.riftbreaker.rifttowny.api.ChunkKey> chunksOf(java.util.UUID ruinId);

        /** Records a fall, with the land it leaves behind. */
        void save(
                net.riftbreaker.rifttowny.domain.territory.Ruin ruin,
                java.util.Collection<net.riftbreaker.rifttowny.api.ChunkKey> chunks);

        /** Updates the row without touching its land, as when it is reclaimed. */
        void update(net.riftbreaker.rifttowny.domain.territory.Ruin ruin);

        /**
         * Releases a ruin's land, leaving the row.
         *
         * <p>The row is what {@code RT-MOD-REGEN} and the anti-recreation rule read afterwards, so
         * letting go of the ground is not the same as forgetting the town.</p>
         */
        int releaseLand(java.util.UUID ruinId);
    }

    /** Town spawns, inside the transaction. */
    interface SpawnStore {

        Optional<net.riftbreaker.rifttowny.domain.territory.SpawnPoint> of(TownId town);

        /** Every town's spawn, for filling a cache at startup. */
        java.util.Map<TownId, net.riftbreaker.rifttowny.domain.territory.SpawnPoint> all();

        /** Sets or replaces a town's spawn. A town has one, so this is an upsert. */
        void set(
                TownId town,
                net.riftbreaker.rifttowny.domain.territory.SpawnPoint spawn,
                ResidentId setBy,
                java.time.Instant now);

        /** Removes it, as when the land it stood on stops being the town's. */
        boolean clear(TownId town);
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
