package net.riftbreaker.rifttowny.paper.spawn;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class SpawnCooldownTest {

    private static final long SECOND = 1_000_000_000L;

    private final AtomicLong now = new AtomicLong();
    private final SpawnCooldown cooldown =
            new SpawnCooldown(Duration.ofSeconds(60), now::get);

    private void advanceSeconds(final long seconds) {
        now.addAndGet(seconds * SECOND);
    }

    @Test
    @DisplayName("the first travel is free")
    void firstIsFree() {
        assertThat(cooldown.remaining(UUID.randomUUID())).isEmpty();
    }

    @Test
    @DisplayName("a second travel waits out the cooldown")
    void secondWaits() {
        final UUID player = UUID.randomUUID();
        cooldown.started(player);

        assertThat(cooldown.remaining(player)).isPresent();
        advanceSeconds(59);
        assertThat(cooldown.remaining(player)).isPresent();
        advanceSeconds(1);
        assertThat(cooldown.remaining(player))
                .as("exactly at the boundary the wait is over")
                .isEmpty();
    }

    @Test
    @DisplayName("the wait reported is what is actually left")
    void reportsTheRemainder() {
        final UUID player = UUID.randomUUID();
        cooldown.started(player);
        advanceSeconds(20);

        assertThat(cooldown.remaining(player).orElseThrow()).isEqualTo(Duration.ofSeconds(40));
    }

    @Test
    @DisplayName("one player's cooldown is not another's")
    void playersAreIndependent() {
        final UUID first = UUID.randomUUID();
        final UUID second = UUID.randomUUID();
        cooldown.started(first);

        assertThat(cooldown.remaining(first)).isPresent();
        assertThat(cooldown.remaining(second)).isEmpty();
    }

    @Test
    @DisplayName("a zero cooldown never makes anybody wait")
    void zeroDisablesIt() {
        final SpawnCooldown none = new SpawnCooldown(Duration.ZERO, now::get);
        final UUID player = UUID.randomUUID();

        none.started(player);

        assertThat(none.remaining(player)).isEmpty();
        assertThat(none.tracked())
                .as("and it does not accumulate players it will never consult")
                .isZero();
    }

    @Test
    @DisplayName("a wrapping nanoTime does not strand anybody")
    void survivesWrapping() {
        final UUID player = UUID.randomUUID();
        now.set(Long.MAX_VALUE - SECOND);
        cooldown.started(player);

        // Past the wrap. By subtraction the elapsed time is right; by magnitude it would read as an
        // enormous negative and the player would be told to wait forever.
        now.addAndGet(61L * SECOND);

        assertThat(cooldown.remaining(player)).isEmpty();
    }

    @Test
    @DisplayName("a player who leaves is forgotten")
    void quittersAreForgotten() {
        final UUID player = UUID.randomUUID();
        cooldown.started(player);

        cooldown.forget(player);

        assertThat(cooldown.tracked()).isZero();
        assertThat(cooldown.remaining(player)).isEmpty();
    }

    @Test
    @DisplayName("a wait reads in whole seconds, and never as zero")
    void describing() {
        assertThat(SpawnCooldown.describe(Duration.ofSeconds(40))).isEqualTo("40s");
        assertThat(SpawnCooldown.describe(Duration.ofMillis(1_500))).isEqualTo("2s");
        assertThat(SpawnCooldown.describe(Duration.ofMillis(200)))
                .as("'0s' would read as a bug to somebody being told to wait")
                .isEqualTo("1s");
    }

    @Test
    @DisplayName("nobody is not somebody")
    void nullIsNotTracked() {
        assertThat(cooldown.remaining(null)).isEmpty();
        cooldown.started(null);
        cooldown.forget(null);
        assertThat(cooldown.tracked()).isZero();
    }
}
