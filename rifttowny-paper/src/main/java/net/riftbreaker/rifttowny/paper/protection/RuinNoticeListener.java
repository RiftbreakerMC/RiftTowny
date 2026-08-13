package net.riftbreaker.rifttowny.paper.protection;

import net.riftbreaker.rifttowny.api.ChunkKey;
import net.riftbreaker.rifttowny.domain.territory.Ruin;
import net.riftbreaker.rifttowny.domain.territory.RuinIndex;
import net.riftbreaker.rifttowny.paper.message.MessageKey;
import net.riftbreaker.rifttowny.paper.message.MessageService;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Tells a player they are standing in a fallen town, and how long is left to take it on.
 *
 * <p>Without this the feature is invisible. A ruin looks exactly like a town whose owner is away:
 * the blocks refuse to break and nothing says why, or how long that will be true, or that the whole
 * place is available to anybody willing to claim it.</p>
 *
 * <p><strong>Movement handling is written for the cost.</strong> {@code PlayerMoveEvent} fires many
 * times a second per player, so the first thing this does is compare two pairs of integers and
 * return; the ruin lookup happens only when a player actually changes chunk. Even then the answer
 * comes from memory.</p>
 */
public final class RuinNoticeListener implements Listener {

    /** Long enough that pacing a border does not spam; short enough that re-entry is announced. */
    private static final long NOTICE_INTERVAL_MILLIS = 30_000L;

    private final RuinIndex ruins;
    private final MessageService messages;
    private final MessageThrottle throttle;
    private final Clock clock;

    public RuinNoticeListener(
            final RuinIndex ruins, final MessageService messages, final Clock clock) {
        this(ruins, messages, clock,
                new MessageThrottle(NOTICE_INTERVAL_MILLIS, System::nanoTime));
    }

    public RuinNoticeListener(
            final RuinIndex ruins,
            final MessageService messages,
            final Clock clock,
            final MessageThrottle throttle
    ) {
        this.ruins = Objects.requireNonNull(ruins, "ruins");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.throttle = Objects.requireNonNull(throttle, "throttle");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(final PlayerMoveEvent event) {
        if (!changedChunk(event.getFrom(), event.getTo())) {
            return;
        }
        announce(event.getPlayer(), event.getTo());
    }

    /** Teleporting skips the chunks between, so arriving in a ruin has to be caught separately. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(final PlayerTeleportEvent event) {
        announce(event.getPlayer(), event.getTo());
    }

    /** Logging back in inside a ruin is the same arrival, and the timer has moved since. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(final PlayerJoinEvent event) {
        announce(event.getPlayer(), event.getPlayer().getLocation());
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        throttle.forget(event.getPlayer().getUniqueId());
    }

    private void announce(final Player player, final Location where) {
        if (where == null || where.getWorld() == null) {
            return;
        }
        final ChunkKey chunk = Chunks.of(where);
        final Optional<Ruin> ruin = ruins.at(chunk);
        if (ruin.isEmpty() || !throttle.shouldSend(player.getUniqueId())) {
            return;
        }
        final java.time.Instant now = clock.instant();
        // Two messages rather than one with a conditional clause: whether the place can be rebuilt
        // yet is the only thing a player standing here actually has to decide, and burying it in a
        // parenthesis is how it gets missed.
        messages.send(
                player,
                ruin.get().isReclaimableAt(now)
                        ? MessageKey.RUIN_ENTERED_RECLAIMABLE
                        : MessageKey.RUIN_ENTERED,
                MessageService.value("ruin", ruin.get().name().display()),
                MessageService.value("remaining", describe(ruin.get().remaining(now))));
    }

    /**
     * A duration a player can act on.
     *
     * <p>Rounded to hours or minutes rather than printed exactly: "2h" is a decision, and
     * "PT2H13M46.221S" is a stack trace.</p>
     */
    static String describe(final Duration remaining) {
        final long hours = remaining.toHours();
        if (hours >= 1L) {
            return hours + "h";
        }
        final long minutes = remaining.toMinutes();
        return minutes >= 1L ? minutes + "m" : "less than a minute";
    }

    /** Two integer comparisons, because this runs on every movement packet. */
    private static boolean changedChunk(final Location from, final Location to) {
        if (to == null || from.getWorld() == null || to.getWorld() == null) {
            return false;
        }
        if (!from.getWorld().getUID().equals(to.getWorld().getUID())) {
            return true;
        }
        return (from.getBlockX() >> 4) != (to.getBlockX() >> 4)
                || (from.getBlockZ() >> 4) != (to.getBlockZ() >> 4);
    }
}
