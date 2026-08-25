package net.riftbreaker.rifttowny.paper.protection;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one half of block protection a substitute cannot check.
 *
 * <p>{@link BlockActionsTest} proves the policy by handing {@link BlockActions} a stand-in registry,
 * which is the only way to test it at all — {@code org.bukkit.Tag}'s constants are initialised from
 * a running server and throw in a bare JVM. The cost of that seam is that the mapping in
 * {@link BukkitBlockTags} is invisible to it <em>by construction</em>. That is measured, not
 * assumed: wiring {@code Kind.AXES} to {@code Tag.ITEMS_HOES} leaves all seventeen cases in
 * {@code BlockActionsTest} green while a server would refuse to protect a log from an axe and
 * would deny a hoe on dirt instead. The tests below fail on that mutation, which is the only
 * reason they are worth their oddness.</p>
 *
 * <p>So the mapping is checked the only way left — by reading it. Not elegant, and it has a
 * precedent in this repository for the same reason: {@code BukkitFreeArchitectureTest} reads source
 * to enforce a rule the type system will not. What makes it work here is that the mapping is
 * required to be boring: a {@code Kind} is named after the vanilla tag it stands for, so any line
 * where the two names disagree is either a typo or a decision that needs writing down.</p>
 *
 * <p><strong>What this still cannot prove</strong> is tag <em>membership</em> — that vanilla's
 * {@code all_signs} really contains every sign. That is server data, absent from the API jar, and
 * nothing offline can establish it.</p>
 */
class BlockTagMappingTest {

    private static final Path SOURCE = Path.of(
            "src", "main", "java", "net", "riftbreaker", "rifttowny", "paper", "protection",
            "BukkitBlockTags.java");

    /**
     * The three that are deliberately not named after their tag.
     *
     * <p>They ask what is in the hand rather than what was clicked, and vanilla prefixes its item
     * tags. Listed here so the exception is a decision on the record rather than a line that happens
     * to differ.</p>
     */
    private static final Map<String, String> ITEM_TAGS = Map.of(
            "AXES", "ITEMS_AXES",
            "SHOVELS", "ITEMS_SHOVELS",
            "HOES", "ITEMS_HOES");

    /** Every {@code case KIND -> Tag.NAME;} in the switch, read in order. */
    private static Map<String, String> mapping() throws IOException {
        assertThat(SOURCE).as("the mapping source").exists();
        final Map<String, String> found = new LinkedHashMap<>();
        for (final String raw : Files.readAllLines(SOURCE, StandardCharsets.UTF_8)) {
            final String line = raw.trim();
            final int arrow = line.indexOf("-> Tag.");
            if (!line.startsWith("case ") || arrow < 0 || !line.endsWith(";")) {
                continue;
            }
            found.put(
                    line.substring("case ".length(), arrow).trim(),
                    line.substring(arrow + "-> Tag.".length(), line.length() - 1).trim());
        }
        return found;
    }

    @Test
    @DisplayName("every tag protection asks for is wired to the tag of the same name")
    void namesMatch() throws IOException {
        final List<String> wrong = new ArrayList<>();
        mapping().forEach((kind, tag) -> {
            final String expected = ITEM_TAGS.getOrDefault(kind, kind);
            if (!expected.equals(tag)) {
                wrong.add(kind + " -> Tag." + tag + ", expected Tag." + expected);
            }
        });

        assertThat(wrong)
                .as("a mis-wired tag protects the wrong blocks and no policy test can see it")
                .isEmpty();
    }

    @Test
    @DisplayName("every Kind is wired, exactly once")
    void allWiredOnce() throws IOException {
        final Map<String, String> found = mapping();

        final List<String> declared = new ArrayList<>();
        for (final BlockTags.Kind kind : BlockTags.Kind.values()) {
            declared.add(kind.name());
        }

        // The compiler already refuses a switch that misses one. What it cannot see is a Kind wired
        // twice, where the second arm is dead and the tag it names is never actually consulted.
        assertThat(found.keySet())
                .as("cases in the mapping switch")
                .containsExactlyInAnyOrderElementsOf(declared);
        assertThat(found).hasSameSizeAs(declared);
    }

    @Test
    @DisplayName("no two Kinds share a tag")
    void noDuplicateTags() throws IOException {
        // Two Kinds on one tag means one of them is asking a question nobody answers, which reads in
        // BlockActions as a branch that is simply never taken.
        assertThat(mapping().values())
                .as("tags used")
                .doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("the item tags are the only exceptions, and they are all still used")
    void itemTagsAreTheOnlyException() throws IOException {
        final Map<String, String> found = mapping();
        for (final Map.Entry<String, String> item : ITEM_TAGS.entrySet()) {
            assertThat(found)
                    .as("%s should still be wired to %s", item.getKey(), item.getValue())
                    .containsEntry(item.getKey(), item.getValue());
        }
        // And the exception list has not grown stale: anything else prefixed ITEMS_ is undeclared.
        found.forEach((kind, tag) -> {
            if (tag.startsWith("ITEMS_")) {
                assertThat(ITEM_TAGS)
                        .as("%s uses an item tag but is not recorded as one", kind)
                        .containsKey(kind);
            }
        });
        assertThat(ITEM_TAGS.keySet().stream().map(k -> k.toLowerCase(Locale.ROOT)).toList())
                .as("the recorded exceptions")
                .hasSize(3);
    }
}
