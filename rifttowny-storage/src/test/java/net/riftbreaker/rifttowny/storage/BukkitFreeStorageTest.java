package net.riftbreaker.rifttowny.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Persistence must never import Bukkit.
 *
 * <p>The storage layer is where a duplication or double-spend bug would live. Keeping it free of
 * server types is what lets those paths be tested against a real database with no server running,
 * which is the only way they get tested at all.</p>
 */
class BukkitFreeStorageTest {

    private static final List<String> FORBIDDEN_IMPORTS = List.of(
            "import org.bukkit",
            "import io.papermc.paper");

    @Test
    @DisplayName("no production source in this module imports a server type")
    void storageSourcesAreServerFree() throws IOException {
        final Path sourceRoot = Path.of("src", "main", "java");
        assertThat(sourceRoot).as("module source root").exists();

        try (Stream<Path> sources = Files.walk(sourceRoot)) {
            final List<String> offenders = sources
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(BukkitFreeStorageTest::importsAServerType)
                    .map(Path::toString)
                    .sorted()
                    .toList();

            assertThat(offenders).isEmpty();
        }
    }

    private static boolean importsAServerType(final Path source) {
        try {
            final String content = Files.readString(source, StandardCharsets.UTF_8);
            return FORBIDDEN_IMPORTS.stream().anyMatch(content::contains);
        } catch (final IOException exception) {
            throw new IllegalStateException("Unable to read " + source, exception);
        }
    }
}
