package net.riftbreaker.rifttowny.paper.spawn;

import net.riftbreaker.rifttowny.api.scheduler.RiftScheduler;
import net.riftbreaker.rifttowny.api.scheduler.RiftTask;
import net.riftbreaker.rifttowny.domain.org.ResidentId;
import net.riftbreaker.rifttowny.domain.territory.SpawnPoint;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Moving a player, with the delay that makes it fair.
 *
 * <p>An instant teleport home is an escape from every fight on the server. The warmup is what turns
 * {@code /town spawn} from that into a decision: stand still for a few seconds, in the open, where
 * anybody who objects can do something about it. Moving cancels it, and so does being hit.</p>
 *
 * <p>Also a listener, because those two cancellations are events. Registered once and consulted only
 * for players who actually have a teleport pending — a movement handler that did anything more than
 * a map lookup for everybody else would cost more than the feature is worth.</p>
 */
public final class TeleportService implements Listener {

    private final RiftScheduler scheduler;
    private final SpawnCooldown cooldown;
    private final Duration warmup;
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();

    public TeleportService(
            final RiftScheduler scheduler, final Duration warmup, final SpawnCooldown cooldown) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.warmup = Objects.requireNonNull(warmup, "warmup");
        this.cooldown = Objects.requireNonNull(cooldown, "cooldown");
    }

    /** How long this player still has to wait before travelling again, if at all. */
    public java.util.Optional<Duration> cooldownRemaining(final ResidentId who) {
        return who == null ? java.util.Optional.empty() : cooldown.remaining(who.value());
    }

    public Duration warmup() {
        return warmup;
    }

    /**
     * Sends a player to a spawn, after the warmup.
     *
     * <p>The future completes with what actually happened, so the caller can say something useful
     * about each — a player who was hit mid-warmup wants to be told that, not that the command
     * failed.</p>
     */
    public CompletableFuture<Outcome> travel(final ResidentId who, final SpawnPoint destination) {
        Objects.requireNonNull(who, "who");
        Objects.requireNonNull(destination, "destination");

        final Player player = Bukkit.getPlayer(who.value());
        if (player == null) {
            return CompletableFuture.completedFuture(Outcome.NOT_ONLINE);
        }
        final World world = Bukkit.getWorld(destination.worldId());
        if (world == null) {
            // Unloaded since the spawn was set. A server state rather than a fault in the command.
            return CompletableFuture.completedFuture(Outcome.NO_DESTINATION);
        }
        final Location target = new Location(
                world, destination.x(), destination.y(), destination.z(),
                destination.yaw(), destination.pitch());

        final CompletableFuture<Outcome> done = new CompletableFuture<>();
        if (warmup.isZero() || warmup.isNegative()) {
            move(player, target, done);
            return done;
        }

        // Replaces any teleport this player already had pending. Running two would have them
        // arriving twice and the second cancellation resolving the first one's future.
        final Pending previous = pending.remove(who.value());
        if (previous != null) {
            previous.cancel(Outcome.SUPERSEDED);
        }

        final Pending waiting = new Pending(done, player.getLocation());
        pending.put(who.value(), waiting);
        waiting.task(scheduler.entityDelayed(
                player.getUniqueId(),
                () -> {
                    if (pending.remove(who.value()) != waiting) {
                        // Cancelled while the task was already on its way in. Whoever removed it
                        // has completed the future.
                        return;
                    }
                    move(player, target, done);
                },
                () -> finish(who.value(), waiting, Outcome.NOT_ONLINE),
                warmup));
        return done;
    }

    private void move(
            final Player player, final Location target, final CompletableFuture<Outcome> done) {
        scheduler.entity(
                player.getUniqueId(),
                () -> player.teleportAsync(target).whenComplete((moved, failure) -> {
                    if (failure != null) {
                        done.completeExceptionally(failure);
                        return;
                    }
                    if (Boolean.TRUE.equals(moved)) {
                        // Recorded on arrival, not on the attempt: a cancelled teleport should not
                        // cost a player their next one.
                        cooldown.started(player.getUniqueId());
                        done.complete(Outcome.ARRIVED);
                    } else {
                        done.complete(Outcome.NO_DESTINATION);
                    }
                }),
                () -> done.complete(Outcome.NOT_ONLINE));
    }

    // --- cancellation ---------------------------------------------------------------------------

    /**
     * Moving cancels it.
     *
     * <p>A whole block, not any movement at all: turning on the spot and the drift a client sends
     * while standing still are not somebody running away, and cancelling on those would make the
     * warmup impossible to survive.</p>
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(final PlayerMoveEvent event) {
        final Pending waiting = pending.get(event.getPlayer().getUniqueId());
        if (waiting == null || !waiting.movedFrom(event.getTo())) {
            return;
        }
        cancel(event.getPlayer().getUniqueId(), Outcome.CANCELLED_MOVED);
    }

    /** Being hit cancels it, which is the whole reason the warmup exists. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(final EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player) {
            cancel(player.getUniqueId(), Outcome.CANCELLED_DAMAGED);
        }
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        cancel(event.getPlayer().getUniqueId(), Outcome.NOT_ONLINE);
        cooldown.forget(event.getPlayer().getUniqueId());
    }

    private void cancel(final UUID player, final Outcome outcome) {
        final Pending waiting = pending.remove(player);
        if (waiting != null) {
            waiting.cancel(outcome);
        }
    }

    private void finish(final UUID player, final Pending waiting, final Outcome outcome) {
        if (pending.remove(player) == waiting) {
            waiting.cancel(outcome);
        }
    }

    public int waiting() {
        return pending.size();
    }

    /** What became of a travel. */
    public enum Outcome {
        ARRIVED,
        CANCELLED_MOVED,
        CANCELLED_DAMAGED,
        /** The player asked again before the first one landed. */
        SUPERSEDED,
        /** The world is not loaded, or the teleport itself declined. */
        NO_DESTINATION,
        NOT_ONLINE
    }

    /** One teleport waiting out its warmup. */
    private static final class Pending {

        private final CompletableFuture<Outcome> done;
        private final Location origin;
        private volatile RiftTask task;

        private Pending(final CompletableFuture<Outcome> done, final Location origin) {
            this.done = done;
            this.origin = origin.clone();
        }

        private void task(final RiftTask scheduled) {
            this.task = scheduled;
        }

        /** Whether the player has left the block they started in. */
        private boolean movedFrom(final Location to) {
            if (to == null || to.getWorld() == null || origin.getWorld() == null) {
                return false;
            }
            if (!to.getWorld().getUID().equals(origin.getWorld().getUID())) {
                return true;
            }
            return to.getBlockX() != origin.getBlockX()
                    || to.getBlockY() != origin.getBlockY()
                    || to.getBlockZ() != origin.getBlockZ();
        }

        private void cancel(final Outcome outcome) {
            final RiftTask scheduled = task;
            if (scheduled != null) {
                scheduled.cancel();
            }
            done.complete(outcome);
        }
    }
}
