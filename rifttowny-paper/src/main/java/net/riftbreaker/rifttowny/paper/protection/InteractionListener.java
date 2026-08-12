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

    public InteractionListener(final ProtectionService protection) {
        this.protection = Objects.requireNonNull(protection, "protection");
    }

    /**
     * Right-clicking and standing on things.
     *
     * <p>Left-clicking is not handled: breaking a block is a {@code BlockBreakEvent}, and cancelling
     * the swing as well would stop a player punching a chest to no effect, which is not a rule
     * worth having.</p>
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInteract(final PlayerInteractEvent event) {
        if (event.getClickedBlock() == null) {
            return;
        }
        final Optional<BlockActions.Action> action = switch (event.getAction()) {
            case RIGHT_CLICK_BLOCK -> BlockActions.forRightClick(event.getClickedBlock());
            case PHYSICAL -> BlockActions.forStandingOn(event.getClickedBlock());
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
