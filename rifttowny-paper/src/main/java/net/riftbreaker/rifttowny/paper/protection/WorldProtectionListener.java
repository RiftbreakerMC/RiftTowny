package net.riftbreaker.rifttowny.paper.protection;

import net.riftbreaker.rifttowny.api.ChunkKey;
import net.riftbreaker.rifttowny.domain.flag.ProtectionFlag;
import net.riftbreaker.rifttowny.domain.org.TownId;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.entity.EntityExplodeEvent;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What the world does to a town without anybody doing it.
 *
 * <p>These are the flags with no actor, and they are the ones a protection plugin is actually
 * judged on. A town that can be rearranged by a creeper, burned down by a lightning strike or
 * dismantled one block at a time by a piston reaching across its border is not protected, however
 * carefully its build permissions are configured.</p>
 *
 * <p>Every check here resolves at wilderness — see {@link ProtectionFlag#effectiveRelationship} —
 * so a member standing nearby cannot widen them.</p>
 */
public final class WorldProtectionListener implements Listener {

    private final ProtectionService protection;

    public WorldProtectionListener(final ProtectionService protection) {
        this.protection = Objects.requireNonNull(protection, "protection");
    }

    // --- explosions ----------------------------------------------------------------------------

    /**
     * Creepers, TNT, end crystals, beds in the nether.
     *
     * <p>The block list is filtered rather than the event cancelled, so an explosion that straddles
     * a border still damages the unclaimed half. Cancelling outright would make a town's edge a
     * blast shield for the wilderness beside it.</p>
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityExplode(final EntityExplodeEvent event) {
        event.blockList().removeIf(block ->
                !protection.allowsWorldAction(block, ProtectionFlag.EXPLOSIONS));
    }

    /** A block exploding, which is a bed or a respawn anchor rather than an entity. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBlockExplode(final BlockExplodeEvent event) {
        event.blockList().removeIf(block ->
                !protection.allowsWorldAction(block, ProtectionFlag.EXPLOSIONS));
    }

    // --- fire ----------------------------------------------------------------------------------

    /** A block burning away. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBurn(final BlockBurnEvent event) {
        if (!protection.allowsWorldAction(event.getBlock(), ProtectionFlag.FIRE_SPREAD)) {
            event.setCancelled(true);
        }
    }

    /** Fire spreading to a new block. Other spreads — grass, mushrooms — are left alone. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSpread(final BlockSpreadEvent event) {
        if (event.getSource().getType() != org.bukkit.Material.FIRE) {
            return;
        }
        if (!protection.allowsWorldAction(event.getBlock(), ProtectionFlag.FIRE_SPREAD)) {
            event.setCancelled(true);
        }
    }

    /**
     * Something catching fire.
     *
     * <p>A player with a flint and steel is judged as a player — it is their build permission that
     * decides — while lightning, lava and spreading fire are judged as the world.</p>
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onIgnite(final BlockIgniteEvent event) {
        final Player igniter = event.getPlayer();
        if (igniter != null) {
            if (!protection.allows(igniter, event.getBlock(), ProtectionFlag.BUILD)) {
                event.setCancelled(true);
            }
            return;
        }
        if (!protection.allowsWorldAction(event.getBlock(), ProtectionFlag.FIRE_SPREAD)) {
            event.setCancelled(true);
        }
    }

    // --- fluids and pistons --------------------------------------------------------------------

    /**
     * Water and lava flowing across a border.
     *
     * <p>Only checked when the fluid actually changes owner. A river inside a town flows normally;
     * lava poured at the edge of the wilderness does not get to run into it.</p>
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFlow(final BlockFromToEvent event) {
        final ChunkKey from = Chunks.of(event.getBlock());
        final ChunkKey to = Chunks.of(event.getToBlock());
        if (from.equals(to)) {
            return;
        }
        if (sameOwner(from, to)) {
            return;
        }
        if (!protection.allowsWorldAction(to, ProtectionFlag.FLUID_FLOW)) {
            event.setCancelled(true);
        }
    }

    /**
     * A piston pushing blocks.
     *
     * <p>The classic border attack: a piston outside a town pushing its blocks around, or pulling
     * them out. Checked per moved block and against the chunk it is moving <em>into</em>, because a
     * block that starts inside the claim and ends outside it has still been taken.</p>
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPistonExtend(final BlockPistonExtendEvent event) {
        if (pistonReachesForeignLand(event.getBlock(), event.getBlocks(),
                event.getDirection().getModX(), event.getDirection().getModZ())) {
            event.setCancelled(true);
        }
    }

    /**
     * Retraction, checked at the blocks being pulled rather than where they land.
     *
     * <p>Only the origin. {@code getDirection()} on a retraction reports the piston's own facing
     * while the blocks travel the opposite way, and a destination computed from it would be wrong in
     * a way nothing would notice. Pulling a claimed block out is the attack worth stopping; pulling
     * a wilderness block into a town is somebody adding to their own land.</p>
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPistonRetract(final BlockPistonRetractEvent event) {
        if (pistonReachesForeignLand(event.getBlock(), event.getBlocks(), 0, 0)) {
            event.setCancelled(true);
        }
    }

    /**
     * Whether this piston moves a block belonging to somebody other than its own chunk's owner.
     *
     * <p>A piston and the blocks it moves inside one town's territory is ordinary redstone and is
     * left alone: the check is about ownership changing, not about pistons existing. That is also
     * why the comparison is against the piston's owner rather than against "is claimed" — a town's
     * own machinery must keep working inside its own walls.</p>
     */
    private boolean pistonReachesForeignLand(
            final Block piston, final List<Block> moved, final int deltaX, final int deltaZ) {
        final Optional<TownId> pistonOwner = protection.ownerAt(Chunks.of(piston));
        for (final Block block : moved) {
            final ChunkKey origin = Chunks.of(block);
            if (refuses(pistonOwner, origin)) {
                return true;
            }
            if (deltaX == 0 && deltaZ == 0) {
                continue;
            }
            final ChunkKey destination = new ChunkKey(
                    origin.worldId(),
                    (block.getX() + deltaX) >> 4,
                    (block.getZ() + deltaZ) >> 4);
            if (refuses(pistonOwner, destination)) {
                return true;
            }
        }
        return false;
    }

    private boolean refuses(final Optional<TownId> pistonOwner, final ChunkKey chunk) {
        return !protection.ownerAt(chunk).equals(pistonOwner)
                && !protection.allowsWorldAction(chunk, ProtectionFlag.PISTONS);
    }

    private boolean sameOwner(final ChunkKey from, final ChunkKey to) {
        return protection.ownerAt(from).equals(protection.ownerAt(to));
    }
}
