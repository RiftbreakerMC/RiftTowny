package net.riftbreaker.rifttowny.domain.org;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Storage for nations.
 *
 * <p>Mirrors {@link TownRepository}'s ownership rule one level up: a nation's member towns are
 * owned by {@code rt_town.nation_id}, not by this repository. {@link #save(Nation)} writes the
 * nation's own row and never its town set, so there is one place a town's allegiance is recorded
 * rather than two that can disagree.</p>
 *
 * <p>The ordering consequence: save the {@link Town} before the {@link Nation} that expects it.
 * {@link Nation#restore} refuses a nation whose capital is not one of its towns.</p>
 */
public interface NationRepository {

    /** The nation with this id, with its member towns populated. */
    CompletableFuture<Optional<Nation>> find(NationId id);

    /** Matched on the normalised name, which is the column carrying the unique constraint. */
    CompletableFuture<Optional<Nation>> findByName(String name);

    /** Inserts or updates the nation row. */
    CompletableFuture<Nation> save(Nation nation);

    /**
     * Deletes a nation.
     *
     * <p>Member towns are released, never cascaded: a town outlives the nation it belonged to, and
     * cascading would delete towns because their nation dissolved.</p>
     *
     * @return whether a row was actually removed
     */
    CompletableFuture<Boolean> delete(NationId id);

    /** How many nations exist. */
    CompletableFuture<Integer> count();

    /** Every nation, for administrative listings and leaderboard rollups. */
    CompletableFuture<List<Nation>> all();
}
