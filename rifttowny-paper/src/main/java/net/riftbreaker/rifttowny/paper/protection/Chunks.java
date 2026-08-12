package net.riftbreaker.rifttowny.paper.protection;

import net.riftbreaker.rifttowny.api.ChunkKey;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;

import java.util.UUID;

/**
 * Bukkit positions to {@link ChunkKey}.
 *
 * <p>One place, because getting the conversion wrong is invisible in testing and catastrophic in
 * play: {@code >> 4} and {@code / 16} agree for positive coordinates and disagree for negative ones,
 * so the mistake would protect the eastern half of a world and quietly leave the western half open.
 * {@link #fromBlock} is separated out precisely so that arithmetic can be tested without a
 * server.</p>
 */
public final class Chunks {

    private Chunks() {
    }

    /**
     * The chunk containing a block coordinate.
     *
     * <p>Arithmetic shift rather than division: {@code -1 >> 4} is {@code -1}, the chunk that
     * actually contains block {@code -1}, while {@code -1 / 16} is {@code 0}, the chunk next
     * door.</p>
     */
    public static ChunkKey fromBlock(final UUID worldId, final int blockX, final int blockZ) {
        return new ChunkKey(worldId, blockX >> 4, blockZ >> 4);
    }

    public static ChunkKey of(final Block block) {
        return fromBlock(block.getWorld().getUID(), block.getX(), block.getZ());
    }

    public static ChunkKey of(final Location location) {
        return fromBlock(
                location.getWorld().getUID(), location.getBlockX(), location.getBlockZ());
    }

    public static ChunkKey of(final Entity entity) {
        return of(entity.getLocation());
    }
}
