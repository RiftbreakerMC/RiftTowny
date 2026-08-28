package net.riftbreaker.rifttowny.paper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A listener nobody registers is a file that compiles and never runs.
 *
 * <p>Nothing in Bukkit complains about it. The class is well formed, its handlers are annotated, its
 * tests pass if it has any, and no event ever reaches it — which is the same shape as the two world
 * flags that were settable and read by nothing, and as the role service methods that existed with no
 * command. That pattern has turned up often enough in this plugin to be worth a guard rather than
 * another sweep.
 *
 * <p>Source-level, and deliberately shallow: it asks whether the plugin names each listener, not
 * whether it registers it correctly or on the right thread. Being named is the difference between
 * "wired" and "forgotten", and forgotten is the failure that actually happens.
 */
class ListenerRegistrationTest {

    private static final Path SOURCE = Path.of("src/main/java");
    private static final Path PLUGIN =
            SOURCE.resolve("net/riftbreaker/rifttowny/paper/RiftTownyPlugin.java");

    @Test
    @DisplayName("every listener in the plugin is named where listeners are registered")
    void everyListenerIsRegistered() throws IOException {
        final String plugin = Files.readString(PLUGIN, StandardCharsets.UTF_8);
        final List<String> listeners = listenerClasses();

        assertThat(listeners)
                .as("finding no listeners at all would make this test vacuous")
                .isNotEmpty();

        final List<String> unregistered = new ArrayList<>();
        for (final String listener : listeners) {
            if (!plugin.contains(listener)) {
                unregistered.add(listener);
            }
        }

        assertThat(unregistered)
                .as("a listener the plugin never mentions receives no events, and nothing at "
                        + "runtime will say so")
                .isEmpty();
    }

    @Test
    @DisplayName("the plugin class is where this test thinks it is")
    void thePluginIsFound() {
        // Otherwise a moved file turns the check above into a comparison against an empty string,
        // which passes.
        assertThat(PLUGIN).exists();
    }

    /** Every class in this module declaring itself a Bukkit listener, by simple name. */
    private static List<String> listenerClasses() throws IOException {
        try (Stream<Path> files = Files.walk(SOURCE)) {
            return files.filter(path -> path.toString().endsWith(".java"))
                    .filter(ListenerRegistrationTest::declaresListener)
                    .map(path -> path.getFileName().toString().replace(".java", ""))
                    .sorted()
                    .toList();
        }
    }

    private static boolean declaresListener(final Path path) {
        try {
            final String source = Files.readString(path, StandardCharsets.UTF_8);
            return source.contains("implements Listener")
                    || source.contains("implements org.bukkit.event.Listener")
                    || source.contains(", Listener")
                    || source.contains("Listener,");
        } catch (final IOException unreadable) {
            throw new IllegalStateException("Could not read " + path, unreadable);
        }
    }
}
