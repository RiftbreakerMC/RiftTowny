package net.riftbreaker.rifttowny.api.map;

import java.util.Objects;
import java.util.UUID;

/**
 * Minimal public nation information for borders and capital badges.
 *
 * <p>The capital is a stable town ID rather than player coordinates; a
 * client can resolve its location independently when town spawns become a
 * separately supported public map capability.</p>
 */
public record MapNation(UUID id, String name, UUID capitalTownId) {
    public MapNation {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(capitalTownId, "capitalTownId");
    }
}
