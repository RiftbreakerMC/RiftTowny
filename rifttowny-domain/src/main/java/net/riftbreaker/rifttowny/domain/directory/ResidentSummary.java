package net.riftbreaker.rifttowny.domain.directory;

import net.riftbreaker.rifttowny.domain.org.ResidentId;
import net.riftbreaker.rifttowny.domain.org.TownId;

import java.util.Objects;

/**
 * One row of the resident directory.
 *
 * <p>Name and town together, because a list of names alone answers nothing a player could not get
 * from the server's own player list — what makes this RiftTowny's answer rather than the platform's
 * is which town each name belongs to.</p>
 *
 * @param id who this is
 * @param name their last known name, or a readable stand-in when nothing has ever named them
 * @param town the town they belong to
 * @param townName that town's display name, resolved once here rather than per line at the surface
 */
public record ResidentSummary(ResidentId id, String name, TownId town, String townName) {

    public ResidentSummary {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(town, "town");
        Objects.requireNonNull(townName, "townName");
    }
}
