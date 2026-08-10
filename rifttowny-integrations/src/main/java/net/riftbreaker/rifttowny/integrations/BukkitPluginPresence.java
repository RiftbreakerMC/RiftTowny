package net.riftbreaker.rifttowny.integrations;

import org.bukkit.Server;
import org.bukkit.plugin.Plugin;

import java.util.Objects;
import java.util.function.Predicate;

/**
 * The real plugin-presence check, kept apart from {@link DefaultCapabilityRegistry} so the registry
 * itself stays free of Bukkit and therefore testable.
 */
public final class BukkitPluginPresence {

    private BukkitPluginPresence() {
    }

    /**
     * A predicate answering whether a named plugin is installed <em>and enabled</em>.
     *
     * <p>Enabled matters: a plugin whose own enable failed is still returned by
     * {@code getPlugin}, and binding to it would fail in a much more confusing way.</p>
     */
    public static Predicate<String> of(final Server server) {
        Objects.requireNonNull(server, "server");
        return name -> {
            final Plugin plugin = server.getPluginManager().getPlugin(name);
            return plugin != null && plugin.isEnabled();
        };
    }
}
