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
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;
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
            for (final ChunkKey chunk
                    : touched(Chunks.of(block), block.getX(), block.getZ(), deltaX, deltaZ)) {
                if (refuses(pistonOwner, chunk)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The chunks one moved block puts at stake.
     *
     * <p>Its own, always: a block that starts in somebody's claim has been taken whatever happens
     * next. And on an extend the chunk it lands in as well, because a block that starts outside a
     * claim and ends inside it has been pushed into somebody's town.</p>
     *
     * <p>A retraction passes a zero delta and gets the origin alone. {@code getDirection()} reports
     * the piston's own facing while the pulled blocks travel the opposite way, so a destination
     * computed from it would be wrong in a direction nothing would notice — and pulling a
     * wilderness block into your own land is not an attack worth stopping anyway.</p>
     *
     * <p>Separated from the event handler so it can be tested. The arithmetic behind it is the one
     * place a chunk boundary is crossed by calculation rather than by a player walking, and the
     * cost of it being wrong is a piston that either grabs land it should not or stops working
     * inside a town's own walls.</p>
     */
    static List<ChunkKey> touched(
            final ChunkKey origin,
            final int blockX,
            final int blockZ,
            final int deltaX,
            final int deltaZ
    ) {
        // A short circuit, not a rule. With a zero delta the destination is the origin, so the
        // comparison below would answer the same thing; this only saves computing it. Said plainly
        // because the branch reads like the place retraction is special-cased, and it is not —
        // what makes a retraction different is the caller passing no delta at all.
        if (deltaX == 0 && deltaZ == 0) {
            return List.of(origin);
        }
        final ChunkKey destination =
                Chunks.fromBlock(origin.worldId(), blockX + deltaX, blockZ + deltaZ);
        // One entry when the block does not leave its chunk, which is the ordinary case: checking
        // the same chunk twice would be harmless but says something untrue about what is at stake.
        return origin.equals(destination) ? List.of(origin) : List.of(origin, destination);
    }


    /**
     * Redstone, which a town may switch off across its own land.
     *
     * <p>{@code BlockRedstoneEvent} is not {@code Cancellable} — it reports a current that is about
     * to change, and the way to refuse it is to put the old current back. Verified against the
     * pinned Paper API rather than assumed: the class has {@code getOldCurrent},
     * {@code getNewCurrent} and {@code setNewCurrent} and implements nothing.</p>
     *
     * <p>The flag was settable through {@code /town flag} and persisted from the day it was written,
     * and no listener ever read it, so a town could turn redstone off and watch it keep running.</p>
     */
    @EventHandler(priority = EventPriority.LOW)
    public void onRedstone(final BlockRedstoneEvent event) {
        if (!protection.allowsWorldAction(event.getBlock(), ProtectionFlag.REDSTONE)) {
            event.setNewCurrent(event.getOldCurrent());
        }
    }

    /**
     * Mobs the world spawns on its own.
     *
     * <p>Only {@code NATURAL}. Every other reason in the enum traces back to
     * something somebody did or built — an egg, a breeding pair, a snow golem, a spawner, a portal,
     * a bucket, a command — and a town that turns off mob spawning is asking the ambient darkness to
     * stop producing zombies, not asking its animal farm to stop breeding or its iron golem to fail
     * to assemble. Blocking those would read as a bug on the first day it shipped.</p>
     *
     * <p>Spawners have their own flag, {@code SPAWNER_USE}, so treating {@code SPAWNER} here would
     * also mean two levers fighting over one behaviour.</p>
     *
     * <p>Listed explicitly rather than as everything-but, because the enum moves in both
     * directions: 26.2 added {@code BUILD_COPPERGOLEM}, which an exclusion list would have silently
     * started suppressing, and deprecated {@code CHUNK_GEN} for removal, which this handler named
     * until the compiler objected. A new reason should default to being allowed and be considered
     * on its merits.</p>
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onCreatureSpawn(final CreatureSpawnEvent event) {
        if (!ambient(event.getSpawnReason())) {
            return;
        }
        if (!protection.allowsWorldAction(
                Chunks.of(event.getLocation()), ProtectionFlag.MOB_SPAWNING)) {
            event.setCancelled(true);
        }
    }
    /**
     * Whether the world produced this mob on its own.
     *
     * <p>Separated from the handler so it can be tested: the handler needs a live server for its
     * Block and Location, but which reasons count as ambient is a decision made here rather than by
     * Bukkit, and it is the part that can be got wrong quietly.</p>
     */
    static boolean ambient(final SpawnReason reason) {
        return reason == SpawnReason.NATURAL;
    }

    private boolean refuses(final Optional<TownId> pistonOwner, final ChunkKey chunk) {
        return !protection.ownerAt(chunk).equals(pistonOwner)
                && !protection.allowsWorldAction(chunk, ProtectionFlag.PISTONS);
    }

    private boolean sameOwner(final ChunkKey from, final ChunkKey to) {
        return protection.ownerAt(from).equals(protection.ownerAt(to));
    }
}
