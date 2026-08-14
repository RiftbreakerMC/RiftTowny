package net.riftbreaker.rifttowny.domain.directory;

import net.riftbreaker.rifttowny.domain.org.NationId;
import net.riftbreaker.rifttowny.domain.org.ResidentId;
import net.riftbreaker.rifttowny.domain.org.TownId;

import java.time.Instant;
import java.util.Objects;

/**
 * One nation, as a listing shows it.
 *
 * <p>Residents and chunks are summed through the member towns rather than held on the nation,
 * because the nation does not own either — its towns do, and a stored total would be a second copy
 * of a number that changes every time anybody claims a chunk anywhere in it.</p>
 *
 * @param towns how many member towns, which is the size a nation is actually judged by
 * @param capital the capital town, for the listing's second line
 */
public record NationSummary(
        NationId id,
        String name,
        ResidentId leader,
        TownId capital,
        int towns,
        int residents,
        int chunks,
        Instant foundedAt
) implements CivicSummary {

    public NationSummary {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(leader, "leader");
        Objects.requireNonNull(capital, "capital");
        Objects.requireNonNull(foundedAt, "foundedAt");
    }
}
