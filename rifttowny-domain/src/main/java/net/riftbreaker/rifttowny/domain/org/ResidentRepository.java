package net.riftbreaker.rifttowny.domain.org;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Storage for residents.
 *
 * <p>A port, declared here beside the aggregate it serves so the domain states what it needs rather
 * than importing what happens to exist. The JDBC implementation lives in {@code rifttowny-storage}.</p>
 *
 * <p>Every method is asynchronous, with no synchronous variant. Offering one would guarantee it was
 * called from a listener, and a blocking database call on a server thread is the easiest way to
 * stall a Minecraft server.</p>
 */
public interface ResidentRepository {

    /** The resident with this id, or empty if the player has never been seen. */
    CompletableFuture<Optional<Resident>> find(ResidentId id);

    /**
     * Inserts or updates a resident.
     *
     * @return the stored resident, so a caller can chain without assuming the write round-tripped
     */
    CompletableFuture<Resident> save(Resident resident);

    /**
     * Every resident of a town, in the order they joined.
     *
     * <p>This is where a town's membership actually lives: {@code rt_resident.town_id} is the single
     * source of truth, and {@link Town} is rebuilt from it. Storing membership on both sides would
     * create two places to disagree.</p>
     */
    CompletableFuture<List<Resident>> findByTown(TownId town);

    /** Looks a resident up by their last known Minecraft name, case-insensitively. */
    CompletableFuture<Optional<Resident>> findByName(String name);
}
