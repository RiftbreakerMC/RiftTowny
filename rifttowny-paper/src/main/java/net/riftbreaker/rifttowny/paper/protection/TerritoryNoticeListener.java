package net.riftbreaker.rifttowny.paper.protection;

import net.kyori.adventure.text.Component;
import net.riftbreaker.rifttowny.api.ChunkKey;
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
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tells a player whose land they have just walked onto.
 *
 * <p>Without it, territory is invisible until it refuses you something. A player finds a town by
 * failing to break a block in it, and finds a ruin by not being able to tell one from a town whose
 * owner is away. Everything RiftTowny protects is worth knowing about a moment <em>before</em> the
 * protection speaks.</p>
 *
 * <p><strong>Written for the cost of {@code PlayerMoveEvent}.</strong> It fires many times a second
 * per player, so the first thing this does is compare two pairs of integers and return; a player
 * walking within a chunk costs four comparisons. Crossing a chunk costs three map reads against the
 * caches the protection check already uses, and says nothing unless the ground actually changed —
 * a town is many chunks, and crossing its middle is not arriving anywhere.</p>
 */
public final class TerritoryNoticeListener implements Listener {

    private final TerritoryNotice notice;
    private final MessageService messages;
    private final net.riftbreaker.rifttowny.domain.civic.ResidentNames names;
    private final Clock clock;
    private final boolean actionBar;
    private final net.riftbreaker.rifttowny.domain.directory.LastKnownChunk positions;
    private final Map<UUID, TerritoryNotice.Territory> lastSeen = new ConcurrentHashMap<>();

    /**
     * @param actionBar whether to announce above the hotbar rather than in chat. The action bar is
     *        the better default: a border crossing is ambient information, and putting it in chat
     *        pushes conversation off the screen on a busy server
     */
    public TerritoryNoticeListener(
            final TerritoryNotice notice,
            final MessageService messages,
            final net.riftbreaker.rifttowny.domain.civic.ResidentNames names,
            final Clock clock,
            final boolean actionBar
    ) {
        this(notice, messages, names, clock, actionBar,
                net.riftbreaker.rifttowny.domain.directory.LastKnownChunk.empty());
    }

    /**
     * The same, recording where each player stands for the placeholder surface.
     *
     * <p>Recorded here rather than in a listener of its own because this one already runs on every
     * chunk crossing and already has the chunk in hand. A second {@code PlayerMoveEvent} handler
     * doing the same two integer comparisons would double the cost of the hottest event on the
     * server to learn something this one already knows.</p>
     */
    public TerritoryNoticeListener(
            final TerritoryNotice notice,
            final MessageService messages,
            final net.riftbreaker.rifttowny.domain.civic.ResidentNames names,
            final Clock clock,
            final boolean actionBar,
            final net.riftbreaker.rifttowny.domain.directory.LastKnownChunk positions
    ) {
        this.notice = Objects.requireNonNull(notice, "notice");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.names = Objects.requireNonNull(names, "names");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.actionBar = actionBar;
        this.positions = Objects.requireNonNull(positions, "positions");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(final PlayerMoveEvent event) {
        if (!changedChunk(event.getFrom(), event.getTo())) {
            return;
        }
        announce(event.getPlayer(), event.getTo());
    }

    /** A teleport skips the chunks between, so arriving somewhere has to be caught separately. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(final PlayerTeleportEvent event) {
        announce(event.getPlayer(), event.getTo());
    }

    /**
     * Logging in is an arrival too.
     *
     * <p>Recorded rather than announced when it is wilderness: a player who logs out in a town and
     * back in should be told where they are, and one who logs in on open ground has learned
     * nothing.</p>
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(final PlayerJoinEvent event) {
        announce(event.getPlayer(), event.getPlayer().getLocation());
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        lastSeen.remove(event.getPlayer().getUniqueId());
        // A player who has logged out is not standing anywhere. Leaving the entry would let a
        // scoreboard show where they were last week as though they were still there.
        positions.forget(event.getPlayer().getUniqueId());
    }

    /** How many players are being tracked, for diagnostics. */
    public int tracked() {
        return lastSeen.size();
    }

    private void announce(final Player player, final Location where) {
        if (where == null || where.getWorld() == null) {
            return;
        }
        final ChunkKey chunk = Chunks.of(where);
        // Recorded before the "worth announcing" test, not after: a player crossing between two
        // chunks of the same town gets no notice, and their position still moved.
        positions.record(player.getUniqueId(), chunk);
        final TerritoryNotice.Territory now = notice.at(chunk, clock.instant());
        final TerritoryNotice.Territory before = lastSeen.put(player.getUniqueId(), now);
        if (!TerritoryNotice.worthAnnouncing(before, now)) {
            return;
        }
        render(player, now).ifPresent(message -> send(player, message));
    }

    private java.util.Optional<Component> render(
            final Player player, final TerritoryNotice.Territory territory) {
        return switch (territory.kind()) {
            case WILDERNESS -> java.util.Optional.of(messages.render(MessageKey.NOTICE_WILDERNESS));
            case TOWN -> territory.displayName().map(name -> messages.render(
                    territory.holder() == null
                            ? MessageKey.NOTICE_TOWN
                            : MessageKey.NOTICE_TOWN_PLOT,
                    MessageService.value("town", name),
                    MessageService.value("holder",
                            territory.holder() == null ? "" : holderName(player, territory))));
            case RUIN -> territory.displayName().map(name -> messages.render(
                    MessageKey.NOTICE_RUIN,
                    MessageService.value("ruin", name),
                    MessageService.value("remaining", describe(territory.remaining()))));
        };
    }

    /**
     * Whose plot this is, in a form a player recognises.
     *
     * <p>"Yours" rather than their own name when it is theirs. Nobody needs to be told their own
     * username, and the distinction between your square and somebody else's is the only part of a
     * plot notice that matters while walking.</p>
     */
    private String holderName(final Player player, final TerritoryNotice.Territory territory) {
        if (territory.holder().value().equals(player.getUniqueId())) {
            return "yours";
        }
        // From the name cache, so an offline holder is named too. A border notice runs on movement
        // and must not wait on storage, which is what the cache is for.
        return names.describe(territory.holder());
    }

    private void send(final Player player, final Component message) {
        if (actionBar) {
            player.sendActionBar(message);
        } else {
            player.sendMessage(message);
        }
    }

    static String describe(final Duration remaining) {
        if (remaining == null) {
            return "";
        }
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
