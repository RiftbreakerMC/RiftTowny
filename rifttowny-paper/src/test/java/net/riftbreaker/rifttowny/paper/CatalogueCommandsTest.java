package net.riftbreaker.rifttowny.paper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A row that says it is done must name commands that exist.
 *
 * <p>The catalogue is the project's map, and it has been wrong in every direction this week: two
 * rows marked PLANNED whose engines shipped, a blocker naming a module id that appears nowhere, and
 * three ACTIVE rows naming commands that do not exist — {@code /plot area}, {@code /townyworld}, and
 * {@code /town set flag} for a tree that is really {@code /town flag}. None of those is harmless.
 * A row is what somebody reads before deciding whether to build something, and a command listed
 * beside ACTIVE reads as a command you can type.
 *
 * <p>This checks vocabulary, not tree shape. Every word of a named command must exist: the root
 * among the commands {@code plugin.yml} declares, and each subcommand as a node somewhere in the
 * command sources. What it cannot check is nesting — {@code /town set flag} uses two real words in
 * the wrong relationship, and passes here. That one belongs to {@code CommandRouterTest}, which
 * knows the trees. Between them, the case this exists for is covered: a row naming something that
 * exists nowhere at all.
 */
class CatalogueCommandsTest {

    private static final Path CATALOGUE = Path.of("..", "FEATURE_CATALOG.md");
    private static final Path PLUGIN_YML = Path.of("src/main/resources/plugin.yml");

    /** Rows whose status claims the feature is usable. */
    private static final Pattern DONE_ROW =
            Pattern.compile("^\\| (RT-[A-Z-]+) \\|.*(ACTIVE|SHIPPED).*$", Pattern.MULTILINE);

    /** A command as the catalogue writes it: `/town flag`, `/tc`, `/plot area *`. */
    private static final Pattern COMMAND = Pattern.compile("`/([a-z]+)([^`]*)`");

    /**
     * A subcommand word, however the sources declare it.
     *
     * <p>Two forms, because the plugin has two. Most trees are built from {@code CommandNode}, but
     * {@code /rifttowny} switches on the raw argument, and a check that knew only the first form
     * reported {@code /rifttowny status} as missing — a command that plainly exists.</p>
     */
    private static final Pattern NODE =
            Pattern.compile("(?:action|group)\\(\"([a-z]+)\"\\)|case \"([a-z]+)\"");

    @Test
    @DisplayName("every command named by a finished row is a command the plugin declares")
    void namedCommandsExist() throws IOException {
        final Set<String> declared = declaredCommands();
        assertThat(declared)
                .as("reading no commands from plugin.yml would make this vacuous")
                .isNotEmpty();

        final List<String> missing = new ArrayList<>();
        for (final String line : Files.readString(CATALOGUE, StandardCharsets.UTF_8).split("\n")) {
            final Matcher row = DONE_ROW.matcher(line);
            if (!row.matches()) {
                continue;
            }
            // The commands column only. The status prose mentions plenty of commands, including
            // ones it is explicitly saying do not exist yet, and those must not be read as claims.
            final String[] cells = line.split("\\|");
            if (cells.length < 10) {
                continue;
            }
            final Matcher named = COMMAND.matcher(cells[8]);
            while (named.find()) {
                if (!declared.contains(named.group(1))) {
                    missing.add(row.group(1) + " names /" + named.group(1));
                    continue;
                }
                for (final String word : named.group(2).trim().split("\\s+")) {
                    // Placeholders and wildcards describe an argument rather than a node.
                    if (word.isEmpty() || !word.matches("[a-z]+")) {
                        continue;
                    }
                    if (!nodes().contains(word)) {
                        missing.add(row.group(1) + " names /" + named.group(1) + ' ' + word);
                    }
                }
            }
        }

        assertThat(missing)
                .as("a command listed beside ACTIVE reads as one a player can type. Either build "
                        + "it, or move it into the row's Outstanding note where it belongs")
                .isEmpty();
    }

    /** Every subcommand word any tree declares, read once. */
    private static Set<String> nodes() throws IOException {
        if (NODES.isEmpty()) {
            try (java.util.stream.Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
                for (final Path path : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                    final Matcher node =
                            NODE.matcher(Files.readString(path, StandardCharsets.UTF_8));
                    while (node.find()) {
                        NODES.add(node.group(1) == null ? node.group(2) : node.group(1));
                    }
                }
            }
        }
        return NODES;
    }

    private static final Set<String> NODES = new LinkedHashSet<>();

    /** Command roots and aliases from plugin.yml, which is the only place Bukkit reads them from. */
    private static Set<String> declaredCommands() throws IOException {
        final Set<String> names = new LinkedHashSet<>();
        boolean inCommands = false;
        for (final String line : Files.readString(PLUGIN_YML, StandardCharsets.UTF_8).split("\n")) {
            if (line.startsWith("commands:")) {
                inCommands = true;
                continue;
            }
            if (inCommands && !line.isBlank() && !line.startsWith(" ")) {
                break;
            }
            if (!inCommands) {
                continue;
            }
            if (line.matches("^ {2}[a-z]+:$")) {
                names.add(line.trim().replace(":", ""));
            } else if (line.trim().startsWith("aliases:")) {
                for (final String alias : line.replaceAll(".*\\[|].*", "").split(",")) {
                    names.add(alias.trim());
                }
            }
        }
        return names;
    }
}
