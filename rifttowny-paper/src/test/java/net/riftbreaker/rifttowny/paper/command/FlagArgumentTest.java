package net.riftbreaker.rifttowny.paper.command;

import net.riftbreaker.rifttowny.paper.command.RiftTownyCommand.FlagArgument;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where the operator is in {@code /rifttowny flag}.
 *
 * <p>Worth its own test because the scope is one word or two — {@code server}, or {@code world} and
 * a name — so every argument after it shifts by one when a world was named. An off-by-one there is
 * invisible until somebody tab-completes a list of world names into the flag slot, which is the kind
 * of thing nobody reports as a bug and everybody works around.</p>
 */
class FlagArgumentTest {

    /** What tab completion sees: everything after {@code /rifttowny}, partial word included. */
    private static FlagArgument at(final String... typed) {
        return RiftTownyCommand.flagArgument(typed);
    }

    @Nested
    @DisplayName("the server scope")
    class ServerScope {

        @Test
        @DisplayName("verb, then scope, then flag, relationship and decision")
        void walkTheWholeCommand() {
            assertThat(at("flag", "")).isEqualTo(FlagArgument.VERB);
            assertThat(at("flag", "set", "")).isEqualTo(FlagArgument.SCOPE);
            assertThat(at("flag", "set", "server", "")).isEqualTo(FlagArgument.FLAG);
            assertThat(at("flag", "set", "server", "build", "")).isEqualTo(FlagArgument.RELATIONSHIP);
            assertThat(at("flag", "set", "server", "build", "visitor", ""))
                    .isEqualTo(FlagArgument.DECISION);
            assertThat(at("flag", "set", "server", "build", "visitor", "allow", ""))
                    .isEqualTo(FlagArgument.NONE);
        }

        @Test
        @DisplayName("clear takes no decision")
        void clearStopsShort() {
            assertThat(at("flag", "clear", "server", "")).isEqualTo(FlagArgument.FLAG);
            assertThat(at("flag", "clear", "server", "build", "")).isEqualTo(FlagArgument.RELATIONSHIP);
            assertThat(at("flag", "clear", "server", "build", "visitor", ""))
                    .isEqualTo(FlagArgument.NONE);
        }

        @Test
        @DisplayName("list takes nothing after its scope")
        void listStopsAtTheScope() {
            // Offering a flag here would suggest an argument the command then refuses.
            assertThat(at("flag", "list", "")).isEqualTo(FlagArgument.SCOPE);
            assertThat(at("flag", "list", "server", "")).isEqualTo(FlagArgument.NONE);
        }
    }

    @Nested
    @DisplayName("the world scope, which is two words")
    class WorldScope {

        @Test
        @DisplayName("everything after the world name shifts by one")
        void everythingShifts() {
            assertThat(at("flag", "set", "world", "")).isEqualTo(FlagArgument.WORLD_NAME);
            assertThat(at("flag", "set", "world", "nether", "")).isEqualTo(FlagArgument.FLAG);
            assertThat(at("flag", "set", "world", "nether", "build", ""))
                    .isEqualTo(FlagArgument.RELATIONSHIP);
            assertThat(at("flag", "set", "world", "nether", "build", "visitor", ""))
                    .isEqualTo(FlagArgument.DECISION);
        }

        @Test
        @DisplayName("a world named 'server' is still reachable")
        void aWorldCalledServer() {
            // The reason the scope is two words rather than bare names with 'server' reserved
            // among them: nothing here silently means something other than what was typed.
            assertThat(at("flag", "set", "world", "server", "")).isEqualTo(FlagArgument.FLAG);
        }

        @Test
        @DisplayName("the scope word is matched however it is capitalised")
        void caseInsensitive() {
            assertThat(at("flag", "set", "WORLD", "")).isEqualTo(FlagArgument.WORLD_NAME);
            assertThat(at("flag", "LIST", "server", "")).isEqualTo(FlagArgument.NONE);
        }

        @Test
        @DisplayName("list still stops, even with a world named")
        void listWithAWorld() {
            assertThat(at("flag", "list", "world", "")).isEqualTo(FlagArgument.WORLD_NAME);
            assertThat(at("flag", "list", "world", "nether", "")).isEqualTo(FlagArgument.NONE);
        }
    }

    @Nested
    @DisplayName("the subcommand name itself")
    class NotYetInside {

        @Test
        @DisplayName("typing 'flag' is still typing the subcommand, not its arguments")
        void theWordItself() {
            // Answering with the verbs here would mean tabbing on 'flag' offers nothing, because
            // none of set/clear/list starts with 'flag'. The operator loses the completion of the
            // subcommand name they were in the middle of.
            assertThat(RiftTownyCommand.insideFlag(new String[] { "flag" })).isFalse();
            assertThat(RiftTownyCommand.insideFlag(new String[] { "fla" })).isFalse();
            assertThat(RiftTownyCommand.insideFlag(new String[] { })).isFalse();
        }

        @Test
        @DisplayName("once there is a second word, it is the flag command's own")
        void onceInside() {
            assertThat(RiftTownyCommand.insideFlag(new String[] { "flag", "" })).isTrue();
            assertThat(RiftTownyCommand.insideFlag(new String[] { "FLAG", "set" })).isTrue();
            assertThat(RiftTownyCommand.insideFlag(new String[] { "migrate", "towny" })).isFalse();
        }
    }

    @Nested
    @DisplayName("the decision words")
    class Decisions {

        @Test
        @DisplayName("the same set /town flag accepts")
        void synonyms() {
            // A word that works in one command and not the other reads as a bug in whichever was
            // typed second.
            for (final String yes : new String[] { "allow", "allowed", "true", "on", "yes", "ALLOW" }) {
                assertThat(RiftTownyCommand.parseDecision(yes)).as("%s", yes).contains(true);
            }
            for (final String no : new String[] { "deny", "denied", "false", "off", "no", "DENY" }) {
                assertThat(RiftTownyCommand.parseDecision(no)).as("%s", no).contains(false);
            }
        }

        @Test
        @DisplayName("anything else is a usage error rather than a guess")
        void refusals() {
            assertThat(RiftTownyCommand.parseDecision("maybe")).isEmpty();
            assertThat(RiftTownyCommand.parseDecision("")).isEmpty();
            assertThat(RiftTownyCommand.parseDecision("1")).isEmpty();
        }
    }
}
