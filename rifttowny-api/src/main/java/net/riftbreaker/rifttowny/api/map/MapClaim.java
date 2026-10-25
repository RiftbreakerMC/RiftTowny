package net.riftbreaker.rifttowny.api.map;

import net.riftbreaker.rifttowny.api.ChunkKey;
import java.util.Objects;
import java.util.UUID;

/**
 * Public, privacy-minimized ownership record for one 16x16 Minecraft chunk.
 *
 * <p>Only the stable town ID and chunk position are exported. Plot holders,
 * trusted residents, permissions, and tax/account details are deliberately
 * excluded; map clients have no legitimate reason to receive that data.</p>
 *
 * @param chunk world UUID and chunk X/Z; chunk positions are not block positions
 * @param townId stable owner ID; a rename must not change ownership identity
 */
public record MapClaim(ChunkKey chunk, UUID townId) {
    public MapClaim {
        Objects.requireNonNull(chunk, "chunk");
        Objects.requireNonNull(townId, "townId");
    }
}
