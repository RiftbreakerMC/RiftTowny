package net.riftbreaker.rifttowny.domain;

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
 * The domain module must never import Bukkit.
 *
 * <p>This is not style. Domain rules are the part of RiftTowny that has to be right — who may build
 * where, what a war settlement transfers, whether a configuration is safe to start. The moment a
 * {@code org.bukkit} type appears in a signature, that rule can only be exercised with a running
 * server, which in practice means it stops being exercised at all. Keeping the module clean is what
 * makes the rules testable, and on Folia it is also what keeps them callable from any thread.</p>
 */
class BukkitFreeArchitectureTest {

    private static final List<String> FORBIDDEN_IMPORTS = List.of(
            "import org.bukkit",
            "import io.papermc.paper",
            "import net.kyori.adventure");

    @Test
    @DisplayName("no production source in this module imports a server type")
    void domainSourcesAreServerFree() throws IOException {
        final Path sourceRoot = Path.of("src", "main", "java");
        assertThat(sourceRoot).as("module source root").exists();

        try (Stream<Path> sources = Files.walk(sourceRoot)) {
            final List<String> offenders = sources
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(BukkitFreeArchitectureTest::importsAServerType)
                    .map(Path::toString)
                    .sorted()
                    .toList();

            assertThat(offenders)
                    .as("domain sources importing org.bukkit, io.papermc.paper or net.kyori.adventure")
                    .isEmpty();
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
