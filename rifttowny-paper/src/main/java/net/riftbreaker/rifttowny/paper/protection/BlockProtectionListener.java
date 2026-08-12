package net.riftbreaker.rifttowny.paper.protection;

import net.riftbreaker.rifttowny.domain.flag.ProtectionFlag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;

import java.util.Objects;

/**
 * Breaking and placing.
 *
 * <p>The listeners here are the ones that make a claim mean anything at all. Everything else is a
 * refinement; this is the difference between owning land and drawing on a map.</p>
 *
 * <p><strong>Ignoring already-cancelled events is deliberate.</strong> Another plugin that already
 * refused this has settled the matter, and re-deciding it would only give the player a second,
 * contradictory explanation.</p>
 *
 * <p><strong>Priority is {@code LOW}.</strong> Early enough that plugins listening for the outcome
 * see the cancellation, late enough that a plugin explicitly un-cancelling — an admin tool, a build
 * event — still wins.</p>
 */
public final class BlockProtectionListener implements Listener {

    private final ProtectionService protection;

    public BlockProtectionListener(final ProtectionService protection) {
        this.protection = Objects.requireNonNull(protection, "protection");
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(final BlockBreakEvent event) {
        if (!protection.allows(event.getPlayer(), event.getBlock(), ProtectionFlag.BREAK)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(final BlockPlaceEvent event) {
        if (!protection.allows(event.getPlayer(), event.getBlock(), ProtectionFlag.BUILD)) {
            event.setCancelled(true);
        }
    }

    /**
     * Beds, doors and anything else that occupies more than one block.
     *
     * <p>Checked separately because the single-block event reports only the block clicked. A bed
     * placed just outside a border with its head inside it would otherwise be half in somebody
     * else's town.</p>
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onMultiPlace(final BlockMultiPlaceEvent event) {
        for (final BlockState state : event.getReplacedBlockStates()) {
            if (!protection.allows(event.getPlayer(), state.getBlock(), ProtectionFlag.BUILD)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    /**
     * Emptying a bucket.
     *
     * <p>The relevant block is the one the fluid lands in, not the one clicked — pouring lava at a
     * border from the wilderness side is the whole reason to check the destination.</p>
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketEmpty(final PlayerBucketEmptyEvent event) {
        final Block target = event.getBlockClicked().getRelative(event.getBlockFace());
        if (!protection.allows(event.getPlayer(), target, ProtectionFlag.BUILD)) {
            event.setCancelled(true);
        }
    }

    /** Filling a bucket removes a block of fluid, so it is a break. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketFill(final PlayerBucketFillEvent event) {
        if (!protection.allows(event.getPlayer(), event.getBlockClicked(), ProtectionFlag.BREAK)) {
            event.setCancelled(true);
        }
    }
}
