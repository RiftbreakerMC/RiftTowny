package net.riftbreaker.rifttowny.paper.protection;

import net.riftbreaker.rifttowny.domain.flag.ProtectionFlag;
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every world flag must be read by something.
 *
 * <p>A flag is settable through {@code /town flag} and persisted the moment it exists as an enum
 * constant. Nothing checks that anything then honours it, so {@code REDSTONE} and
 * {@code MOB_SPAWNING} shipped as levers a town could pull that did nothing at all: stored,
 * displayed in the listing, and read by no listener. Both were found by a sweep rather than by a
 * player, which is luck rather than a process.
 *
 * <p>This reads the listener's source and asserts each {@code Category.WORLD} flag is named in it.
 * That is a weak check — naming a flag is not honouring it correctly — but it is exactly strong
 * enough for the failure that actually happened, which was a flag no line of code mentioned. The
 * behaviour of each handler needs a running server and is not verified anywhere yet.
 */
class WorldFlagCoverageTest {

    private static final Path LISTENER = Path.of(
            "src/main/java/net/riftbreaker/rifttowny/paper/protection/WorldProtectionListener.java");

    @Test
    @DisplayName("every world flag is named by the world listener")
    void everyWorldFlagIsRead() throws Exception {
        final String source = Files.readString(LISTENER, StandardCharsets.UTF_8);
        final List<String> unread = new ArrayList<>();
        for (final ProtectionFlag flag : ProtectionFlag.values()) {
            if (flag.category() == ProtectionFlag.Category.WORLD
                    && !source.contains("ProtectionFlag." + flag.name())) {
                unread.add(flag.name());
            }
        }

        assertThat(unread)
                .as("a world flag nothing reads is a setting that silently does nothing; either "
                        + "give it a handler or take the constant out")
                .isEmpty();
    }

    @Test
    @DisplayName("the listener source is where this test thinks it is")
    void theSourceIsFound() {
        // Without this the test above passes by reading nothing at all if the file ever moves.
        assertThat(LISTENER).exists();
    }

    /**
     * Which spawns a town may suppress.
     *
     * <p>The one decision in the mob-spawning handler that is ours rather than Bukkit's, and the
     * one that can be wrong without anything failing to compile.</p>
     */
    @Nested
    @DisplayName("ambient spawning")
    class Ambient {

        @Test
        @DisplayName("natural spawns are the ones a town may turn off")
        void naturalIsAmbient() {
            assertThat(WorldProtectionListener.ambient(SpawnReason.NATURAL)).isTrue();
        }

        @Test
        @DisplayName("anything a player did or built is left alone")
        void deliberateSpawnsAreNot() {
            // A town turning off mob spawning is asking the darkness to stop producing zombies. It
            // is not asking its animal farm to stop breeding, its golem not to assemble, or a
            // spawner it built not to work - SPAWNER has its own flag, and two levers fighting over
            // one behaviour is worse than one lever that does less.
            for (final SpawnReason reason : List.of(
                    SpawnReason.SPAWNER, SpawnReason.SPAWNER_EGG, SpawnReason.EGG,
                    SpawnReason.BREEDING, SpawnReason.BUILD_IRONGOLEM, SpawnReason.BUILD_SNOWMAN,
                    SpawnReason.BUILD_WITHER, SpawnReason.DISPENSE_EGG, SpawnReason.CUSTOM,
                    SpawnReason.COMMAND, SpawnReason.BUCKET, SpawnReason.NETHER_PORTAL)) {
                assertThat(WorldProtectionListener.ambient(reason)).as("%s", reason).isFalse();
            }
        }

        @Test
        @DisplayName("a reason added to the enum later is allowed until somebody decides otherwise")
        void unknownReasonsAreAllowed() {
            // Stated as a rule rather than left to be inferred from the implementation. The enum
            // moves in both directions - 26.2 added BUILD_COPPERGOLEM and deprecated CHUNK_GEN for
            // removal - and an exclusion list would have silently suppressed the new one.
            final long suppressed = java.util.Arrays.stream(SpawnReason.values())
                    .filter(WorldProtectionListener::ambient)
                    .count();

            assertThat(suppressed)
                    .as("exactly one reason is suppressed; adding to that list is a decision")
                    .isEqualTo(1);
        }
    }
}
