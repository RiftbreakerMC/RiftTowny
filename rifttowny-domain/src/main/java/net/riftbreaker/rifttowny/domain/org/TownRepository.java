package net.riftbreaker.rifttowny.domain.org;

import net.riftbreaker.rifttowny.domain.naming.OrganisationName;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Storage for towns.
 *
 * <p><strong>A town's residents are owned by {@link ResidentRepository}, not by this one.</strong>
 * {@code rt_resident.town_id} is the single source of truth for membership; {@link #save(Town)}
 * writes the town's own row and its trusted outsiders, and never the resident set. Writing
 * membership from both sides would create two places to disagree, and the disagreement would
 * surface as a player belonging to a town that does not list them.</p>
 *
 * <p>The consequence for callers: save the {@link Resident} before the {@link Town} that expects
 * them. A town loaded with a mayor who has no resident row is corrupt, and {@link Town#restore}
 * refuses it rather than returning a half-built aggregate.</p>
 */
public interface TownRepository {

    /** The town with this id, with its residents and trusted outsiders populated. */
    CompletableFuture<Optional<Town>> find(TownId id);

    /**
     * Looks a town up by name.
     *
     * <p>Matched on {@link OrganisationName#normalised()}, which is the column carrying the unique
     * constraint, so the lookup and the uniqueness rule can never disagree.</p>
     */
    CompletableFuture<Optional<Town>> findByName(String name);

    /**
     * Inserts or updates the town row and reconciles its trusted outsiders.
     *
     * @return the stored town
     */
    CompletableFuture<Town> save(Town town);

    /**
     * Deletes a town.
     *
     * <p>Claims, areas and trust rows cascade. Residents deliberately do <em>not</em>: a disbanded
     * town's residents become townless, and cascading them would delete the players.</p>
     *
     * @return whether a row was actually removed
     */
    CompletableFuture<Boolean> delete(TownId id);

    /** Every town in a nation. */
    CompletableFuture<List<Town>> findByNation(NationId nation);

    /** How many towns exist, for {@code %townyadvanced_number_of_towns_in_server%} and diagnostics. */
    CompletableFuture<Integer> count();
}
