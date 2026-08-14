package net.riftbreaker.rifttowny.domain.directory;

import net.riftbreaker.rifttowny.domain.org.NationId;
import net.riftbreaker.rifttowny.domain.org.ResidentId;
import net.riftbreaker.rifttowny.domain.org.TownId;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * One town, as a listing shows it.
 *
 * <p>A flat value rather than the {@link net.riftbreaker.rifttowny.domain.org.Town} aggregate,
 * because a listing needs one thing the aggregate does not hold: how much land the town has. That
 * lives in the territory index, and joining the two per row is what this record is.</p>
 *
 * <p>Deliberately without a treasury balance. Every other field here is already in memory, and a
 * balance is a database read per row — a hundred-town listing would become a hundred queries, which
 * is how a harmless-looking command becomes the reason a server stutters every time somebody is
 * curious. {@code /town info} shows one town's balance, and reads it once.</p>
 *
 * @param chunks counted from the territory index, so it is what protection would actually enforce
 */
public record TownSummary(
        TownId id,
        String name,
        ResidentId mayor,
        int residents,
        int chunks,
        NationId nation,
        Instant foundedAt
) implements CivicSummary {

    public TownSummary {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(mayor, "mayor");
        Objects.requireNonNull(foundedAt, "foundedAt");
    }

    public Optional<NationId> nationId() {
        return Optional.ofNullable(nation);
    }

    public boolean hasNation() {
        return nation != null;
    }
}
