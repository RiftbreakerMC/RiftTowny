package net.riftbreaker.rifttowny.paper.protection;

import net.riftbreaker.rifttowny.domain.flag.ProtectionQuery.ProtectionAnswer;
import net.riftbreaker.rifttowny.paper.message.MessageKey;
import net.riftbreaker.rifttowny.paper.message.MessageService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Objects;

/**
 * Tells a player why they were refused.
 *
 * <p>Three sentences, not one, because the three refusals want different things from the player. The
 * land refusing them is information; their own role refusing them is a thing to go and ask about;
 * and a town that could not be loaded is a fault they should report rather than work around.</p>
 *
 * <p>Also a listener, so the throttle does not outlive the players in it.</p>
 */
public final class ProtectionMessenger implements Listener {

    /** Long enough not to spam, short enough that a later attempt is explained again. */
    public static final long DEFAULT_INTERVAL_MILLIS = 2_000L;

    private final MessageService messages;
    private final MessageThrottle throttle;

    public ProtectionMessenger(final MessageService messages) {
        this(messages, new MessageThrottle(DEFAULT_INTERVAL_MILLIS, System::nanoTime));
    }

    public ProtectionMessenger(final MessageService messages, final MessageThrottle throttle) {
        this.messages = Objects.requireNonNull(messages, "messages");
        this.throttle = Objects.requireNonNull(throttle, "throttle");
    }

    /** Explains a refusal, unless this player was told recently. */
    public void explain(final Player player, final ProtectionAnswer answer) {
        if (player == null || answer == null || answer.allowed()) {
            return;
        }
        if (!throttle.shouldSend(player.getUniqueId())) {
            return;
        }
        if (answer.cacheFault()) {
            messages.send(player, MessageKey.PROTECTION_TOWN_NOT_LOADED);
            return;
        }
        final String town = answer.ownerName() == null ? "" : answer.ownerName();
        if (answer.refusedByRole()) {
            messages.send(player, MessageKey.PROTECTION_DENIED_BY_ROLE,
                    MessageService.value("town", town),
                    MessageService.value("permission", answer.missingPermission()));
            return;
        }
        messages.send(player, MessageKey.PROTECTION_DENIED, MessageService.value("town", town));
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        throttle.forget(event.getPlayer().getUniqueId());
    }

    /** How many players are currently throttled, for diagnostics. */
    public int tracked() {
        return throttle.tracked();
    }
}
