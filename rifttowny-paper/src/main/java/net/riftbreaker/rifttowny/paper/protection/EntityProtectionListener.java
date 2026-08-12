package net.riftbreaker.rifttowny.paper.protection;

import net.riftbreaker.rifttowny.domain.flag.ProtectionFlag;
import net.riftbreaker.rifttowny.domain.role.Permission;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.vehicle.VehicleDamageEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.projectiles.ProjectileSource;

import java.util.Objects;

/**
 * Harming things that are not blocks.
 *
 * <p>Animals, item frames, paintings, armour stands, boats and minecarts are a town's property as
 * much as its walls are, and none of them produce a {@code BlockBreakEvent}. Without this, a claim
 * protects the fence and not the cows inside it.</p>
 *
 * <p><strong>Projectiles are traced back to whoever fired them.</strong> An arrow is an entity, and
 * judging the arrow's relationship to the land would let anybody kill anything from outside a
 * border — the oldest way around a protection plugin there is.</p>
 */
public final class EntityProtectionListener implements Listener {

    private final ProtectionService protection;

    public EntityProtectionListener(final ProtectionService protection) {
        this.protection = Objects.requireNonNull(protection, "protection");
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamage(final EntityDamageByEntityEvent event) {
        final Player attacker = playerBehind(event.getDamager());
        if (attacker == null) {
            // A mob, a falling anvil, a dispenser. Nothing here has a relationship to the land, and
            // guarding it would stop a zombie hitting a cow in a town.
            return;
        }

        final Entity victim = event.getEntity();
        if (victim instanceof Player) {
            // PvP is about the land, not about the victim's gear, so it carries no role permission:
            // a town's members do not get to duel where its visitors may not.
            if (!protection.allows(attacker, Chunks.of(victim), ProtectionFlag.PVP, null)) {
                event.setCancelled(true);
            }
            return;
        }

        if (!protection.allows(attacker, Chunks.of(victim),
                ProtectionFlag.ENTITY_DAMAGE, Permission.ENTITY_DAMAGE)) {
            event.setCancelled(true);
        }
    }

    /** Item frames and paintings, which break on a punch rather than on a block event. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHangingBreak(final HangingBreakByEntityEvent event) {
        final Player breaker = playerBehind(event.getRemover());
        if (breaker == null) {
            return;
        }
        if (!protection.allows(breaker, Chunks.of(event.getEntity()),
                ProtectionFlag.ENTITY_DAMAGE, Permission.ENTITY_DAMAGE)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onVehicleDamage(final VehicleDamageEvent event) {
        final Player attacker = playerBehind(event.getAttacker());
        if (attacker == null) {
            return;
        }
        if (!protection.allows(attacker, Chunks.of(event.getVehicle()),
                ProtectionFlag.VEHICLE, Permission.VEHICLE)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onVehicleDestroy(final VehicleDestroyEvent event) {
        final Player attacker = playerBehind(event.getAttacker());
        if (attacker == null) {
            return;
        }
        if (!protection.allows(attacker, Chunks.of(event.getVehicle()),
                ProtectionFlag.VEHICLE, Permission.VEHICLE)) {
            event.setCancelled(true);
        }
    }

    /**
     * The player responsible for a damaging entity, following one projectile hop.
     *
     * <p>One hop and no further: a skeleton's arrow has a shooter that is not a player, and there is
     * no chain of ownership beyond that worth walking.</p>
     */
    private static Player playerBehind(final Entity damager) {
        if (damager instanceof Player player) {
            return player;
        }
        if (damager instanceof Projectile projectile) {
            final ProjectileSource source = projectile.getShooter();
            return source instanceof Player player ? player : null;
        }
        return null;
    }
}
