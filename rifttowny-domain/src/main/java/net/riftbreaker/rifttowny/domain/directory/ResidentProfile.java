package net.riftbreaker.rifttowny.domain.directory;

import net.riftbreaker.rifttowny.domain.org.NationId;
import net.riftbreaker.rifttowny.domain.org.ResidentId;
import net.riftbreaker.rifttowny.domain.role.SystemRole;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One player's record, as {@code /resident} shows it.
 *
 * <p>Everything here is public knowledge on the server it describes: which town somebody belongs to,
 * what they may do in it, when they were last around. Nothing here is location, inventory or
 * anything a player would be surprised to have shown — that boundary is set in the master brief and
 * this is one of the screens it was written for.</p>
 *
 * @param town the town they belong to, or null for the majority of players who belong to none
 * @param nation reached through their town, never held on the player
 * @param standing what the town itself makes of them: its leader, a member, or a stranger
 * @param roles the names of the roles their town has given them, in the town's own order
 * @param plotsHeld how many plots they personally hold, counted rather than listed — the list is
 *        {@code /plot list} and belongs to the person who holds them
 */
public record ResidentProfile(
        ResidentId id,
        String name,
        TownSummary town,
        NationId nation,
        SystemRole standing,
        List<String> roles,
        int plotsHeld,
        Instant joinedAt,
        Instant lastSeenAt
) {

    public ResidentProfile {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(standing, "standing");
        Objects.requireNonNull(joinedAt, "joinedAt");
        Objects.requireNonNull(lastSeenAt, "lastSeenAt");
        roles = roles == null ? List.of() : List.copyOf(roles);
    }

    public Optional<TownSummary> townSummary() {
        return Optional.ofNullable(town);
    }

    public Optional<NationId> nationId() {
        return Optional.ofNullable(nation);
    }

    public boolean hasTown() {
        return town != null;
    }

    /** Whether this player leads their town. */
    public boolean isMayor() {
        return standing == SystemRole.LEADER;
    }
}
