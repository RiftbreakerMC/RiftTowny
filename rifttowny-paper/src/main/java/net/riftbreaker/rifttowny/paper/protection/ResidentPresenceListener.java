package net.riftbreaker.rifttowny.paper.protection;

import net.riftbreaker.rifttowny.domain.org.ResidentId;
import net.riftbreaker.rifttowny.domain.service.ResidentNameService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * Notices that a player is here, and under what name.
 *
 * <p>Names are stored as {@code last_known_name} beside a UUID, and without something watching for
 * arrivals that column starts lying the first time somebody renames themselves: every listing would
 * show a town's mayor under a name they abandoned months ago.</p>
 *
 * <p>The write is asynchronous and its result is ignored on purpose. Nothing a player does next
 * depends on their name being current, so making them wait for a database round trip at the least
 * convenient moment — the tick they log in — would be a cost with no benefit.</p>
 */
public final class ResidentPresenceListener implements Listener {

    private final ResidentNameService names;
    private final BiConsumer<String, Throwable> onFailure;

    public ResidentPresenceListener(
            final ResidentNameService names, final BiConsumer<String, Throwable> onFailure) {
        this.names = Objects.requireNonNull(names, "names");
        this.onFailure = Objects.requireNonNull(onFailure, "onFailure");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(final PlayerJoinEvent event) {
        final ResidentId who = ResidentId.of(event.getPlayer().getUniqueId());
        final String name = event.getPlayer().getName();
        names.seen(who, name).whenComplete((renamed, failure) -> {
            if (failure != null) {
                // Reported rather than swallowed: a name that silently stops updating is the kind of
                // fault that is noticed months later, by which point the cause is long gone.
                onFailure.accept("Could not record the name of " + name, failure);
            }
        });
    }
}
