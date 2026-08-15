package net.riftbreaker.rifttowny.paper.protection;

import net.riftbreaker.rifttowny.domain.flag.ProtectionFlag;
import net.riftbreaker.rifttowny.domain.role.Permission;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Vehicle;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;

import java.util.Objects;
import java.util.Optional;

/**
 * Opening, flipping, riding and trampling.
 *
 * <p>Everything here shares one problem: Bukkit reports a single event for actions a town thinks of
 * as entirely different things. {@link BlockActions} makes the distinction for blocks; this makes it
 * for entities, where a villager, a minecart and an item frame all arrive as one interaction.</p>
 */
public final class InteractionListener implements Listener {

    private final ProtectionService protection;
    private final BlockActions blocks;

    public InteractionListener(final ProtectionService protection, final BlockActions blocks) {
        this.protection = Objects.requireNonNull(protection, "protection");
        this.blocks = Objects.requireNonNull(blocks, "blocks");
    }

    /**
     * Right-clicking, hitting and standing on things.
     *
     * <p>Left-clicking reaches {@link BlockActions#forLeftClick} rather than being ignored, but it
     * still answers for almost nothing: breaking a block is a {@code BlockBreakEvent} and is caught
     * there, and cancelling every swing would stop a player punching a chest to no effect, which is
     * not a rule worth having.</p>
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInteract(final PlayerInteractEvent event) {
        if (event.getClickedBlock() == null) {
            return;
        }
        final org.bukkit.Material clicked = event.getClickedBlock().getType();
        final Optional<BlockActions.Action> action = switch (event.getAction()) {
            // What is in the hand matters for this one case only: an axe, a shovel, a hoe, shears
            // or a honeycomb rewrites the block it is used on, and that arrives as a plain
            // right-click on a block which by itself means nothing.
            case RIGHT_CLICK_BLOCK -> blocks.forRightClick(clicked, heldType(event));
            case LEFT_CLICK_BLOCK -> blocks.forLeftClick(clicked);
            case PHYSICAL -> blocks.forStandingOn(clicked);
            default -> Optional.empty();
        };
        if (action.isEmpty()) {
            return;
        }

        final BlockActions.Action what = action.get();
        if (!protection.allows(event.getPlayer(), Chunks.of(event.getClickedBlock()),
                what.flag(), what.permission())) {
            event.setCancelled(true);
            // Pressure plates and tripwires arrive as PHYSICAL and are worth cancelling silently;
            // the player walked onto them, they did not ask for anything.
            if (event.getAction() == Action.PHYSICAL) {
                event.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
            }
        }
    }

    /**
     * Bone meal.
     *
     * <p>The one member of the tool-on-block family that a {@code (block, item)} pair cannot express:
     * what bone meal does depends on what is under it, and the set of things it grows is most of the
     * plant kingdom. Its own event fires only when the fertilising actually took effect, which is
     * both narrower and more accurate than any list of materials — a right-click that did nothing is
     * not worth a denial message.</p>
     *
     * <p>{@link ProtectionFlag#BUILD}, for the same reason as stripping a log: it is the town's
     * ground that ends up different. Growing somebody's sapling into a tree can put branches through
     * a roof, and turning their lawn to flowers is a change they did not ask for even where it was
     * meant kindly.</p>
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFertilise(final org.bukkit.event.block.BlockFertilizeEvent event) {
        // Null when a dispenser applies it. Nothing there holds a permission.
        if (event.getPlayer() == null) {
            return;
        }
        if (!protection.allows(event.getPlayer(), Chunks.of(event.getBlock()),
                ProtectionFlag.BUILD, Permission.BUILD)) {
            event.setCancelled(true);
        }
    }

    /** What the player was holding, or null for an empty hand. */
    private static org.bukkit.Material heldType(final PlayerInteractEvent event) {
        return event.getItem() == null ? null : event.getItem().getType();
    }

    /**
     * Putting an entity down: an item frame, a painting, an armour stand, a boat, a minecart.
     *
     * <p>None of these is a block, so none of them fires a {@code BlockPlaceEvent} — which made a
     * claim's build rule stop at the wall and let anybody hang a painting on the other side of it.
     * Both events carry the block the entity was placed against, and that block's chunk is the one
     * that owns the decision.</p>
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityPlace(final org.bukkit.event.entity.EntityPlaceEvent event) {
        // Null for a dispenser putting down a boat. Nothing there holds a permission, and the world
        // rules are the ones that apply to it.
        if (event.getPlayer() == null) {
            return;
        }
        if (!protection.allows(event.getPlayer(), Chunks.of(event.getBlock()),
                ProtectionFlag.BUILD, Permission.BUILD)) {
            event.setCancelled(true);
        }
    }

    /** The same, for the events Bukkit routes through its own hanging-entity hierarchy. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHangingPlace(final org.bukkit.event.hanging.HangingPlaceEvent event) {
        if (event.getPlayer() == null) {
            return;
        }
        if (!protection.allows(event.getPlayer(), Chunks.of(event.getBlock()),
                ProtectionFlag.BUILD, Permission.BUILD)) {
            event.setCancelled(true);
        }
    }

    /** Right-clicking an entity: trading, leading, rotating an item frame, boarding a boat. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInteractEntity(final PlayerInteractEntityEvent event) {
        final Entity target = event.getRightClicked();
        // Tested against the Vehicle interface rather than an EntityType list: boats are one type
        // per wood and gain more with every release, and a list would silently stop covering the
        // newest one.
        final boolean vehicle = target instanceof Vehicle;
        final ProtectionFlag flag = vehicle ? ProtectionFlag.VEHICLE : ProtectionFlag.INTERACT;
        final Permission permission = vehicle ? Permission.VEHICLE : Permission.ENTITY_INTERACT;

        if (!protection.allows(event.getPlayer(), Chunks.of(target), flag, permission)) {
            event.setCancelled(true);
        }
    }

    /**
     * Taking things off an armour stand.
     *
     * <p>Its own event, and its own permission. An armour stand holding a town's gear is closer to a
     * chest than to a lever, and a town that opened its doors has not opened its wardrobe.</p>
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onArmourStand(final PlayerArmorStandManipulateEvent event) {
        if (!protection.allows(event.getPlayer(), Chunks.of(event.getRightClicked()),
                ProtectionFlag.INTERACT, Permission.ENTITY_INTERACT)) {
            event.setCancelled(true);
        }
    }

}
