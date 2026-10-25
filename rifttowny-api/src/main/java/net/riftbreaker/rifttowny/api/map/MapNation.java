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
public record MapNation(UUID id, String name, UUID capitalTownId, String colorHex) {
    public MapNation {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(capitalTownId, "capitalTownId");
        Objects.requireNonNull(colorHex, "colorHex");
        if (!colorHex.matches("#[0-9a-f]{6}")) {
            throw new IllegalArgumentException("Expected canonical #rrggbb nation color");
        }
    }
}
