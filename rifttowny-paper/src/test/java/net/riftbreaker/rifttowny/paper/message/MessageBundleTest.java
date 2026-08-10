package net.riftbreaker.rifttowny.paper.message;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keeps {@link MessageKey} and the bundled {@code messages.yml} in step.
 *
 * <p>Both failure modes this catches are the kind that ship: a key added in code but not in the
 * file, which leaves operators unable to customise it, and a template with an unclosed MiniMessage
 * tag, which reaches a player as literal markup.</p>
 */
class MessageBundleTest {

    private static final Path BUNDLE = Path.of("src", "main", "resources", "messages.yml");

    @Test
    @DisplayName("every message key has an entry in the bundled messages.yml")
    void everyKeyIsInTheBundle() throws IOException {
        final String bundle = Files.readString(BUNDLE, StandardCharsets.UTF_8);

        final List<String> missing = new ArrayList<>();
        for (final MessageKey key : MessageKey.values()) {
            // Leaf key rather than the full dotted path: messages.yml is nested, so
            // "status.header" appears in the file as "header:" under "status:".
            final String leaf = key.path().substring(key.path().lastIndexOf('.') + 1);
            if (!bundle.contains(leaf + ":")) {
                missing.add(key.path());
            }
        }

        assertThat(missing).as("keys defined in MessageKey but absent from messages.yml").isEmpty();
    }

    @Test
    @DisplayName("every built-in default is valid MiniMessage")
    void everyFallbackParses() {
        final MiniMessage miniMessage = MiniMessage.miniMessage();

        for (final MessageKey key : MessageKey.values()) {
            assertThat(miniMessage.deserialize(key.fallback()))
                    .as("default for %s", key.path())
                    .isNotNull();
        }
    }

    @Test
    @DisplayName("every template in the bundled file is valid MiniMessage")
    void everyBundledTemplateParses() throws IOException {
        final MiniMessage miniMessage = MiniMessage.miniMessage();

        final List<String> broken = new ArrayList<>();
        for (final String line : Files.readAllLines(BUNDLE, StandardCharsets.UTF_8)) {
            final String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || !trimmed.contains(": '")) {
                continue;
            }
            final String template = trimmed
                    .substring(trimmed.indexOf(": '") + 3)
                    .replaceAll("'$", "")
                    .replace("''", "'");
            try {
                miniMessage.deserialize(template);
            } catch (final RuntimeException invalid) {
                broken.add(trimmed + " -> " + invalid.getMessage());
            }
        }

        assertThat(broken).isEmpty();
    }
}
