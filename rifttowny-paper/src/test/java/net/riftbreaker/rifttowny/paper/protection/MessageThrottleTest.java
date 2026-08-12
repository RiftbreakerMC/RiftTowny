package net.riftbreaker.rifttowny.paper.protection;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class MessageThrottleTest {

    private static final long MILLIS = 1_000_000L;

    private final AtomicLong now = new AtomicLong();
    private final MessageThrottle throttle = new MessageThrottle(2_000L, now::get);

    private void advanceMillis(final long millis) {
        now.addAndGet(millis * MILLIS);
    }

    @Test
    @DisplayName("the first refusal is always explained")
    void firstIsSent() {
        assertThat(throttle.shouldSend(UUID.randomUUID())).isTrue();
    }

    @Test
    @DisplayName("a player holding left-click is told once, not once a tick")
    void repeatsAreSuppressed() {
        final UUID player = UUID.randomUUID();
        assertThat(throttle.shouldSend(player)).isTrue();

        int sent = 0;
        for (int tick = 0; tick < 40; tick++) {
            advanceMillis(50);
            if (throttle.shouldSend(player)) {
                sent++;
            }
        }

        // Two seconds of ticking at 20/s: one more message, not forty.
        assertThat(sent).isEqualTo(1);
    }

    @Test
    @DisplayName("a later attempt is explained again")
    void laterAttemptsAreExplained() {
        final UUID player = UUID.randomUUID();
        throttle.shouldSend(player);

        advanceMillis(1_999);
        assertThat(throttle.shouldSend(player)).isFalse();
        advanceMillis(1);
        assertThat(throttle.shouldSend(player)).isTrue();
    }

    @Test
    @DisplayName("one player's spam does not silence another")
    void playersAreIndependent() {
        final UUID first = UUID.randomUUID();
        final UUID second = UUID.randomUUID();

        assertThat(throttle.shouldSend(first)).isTrue();
        assertThat(throttle.shouldSend(second)).isTrue();
        assertThat(throttle.shouldSend(first)).isFalse();
    }

    @Test
    @DisplayName("a wrapping nanoTime does not jam the throttle shut")
    void survivesWrapping() {
        final UUID player = UUID.randomUUID();
        now.set(Long.MAX_VALUE - MILLIS);
        assertThat(throttle.shouldSend(player)).isTrue();

        // Past the wrap. Compared by subtraction, the elapsed time is still correct; compared by
        // magnitude it would read as a huge negative and the player would never be told again.
        now.addAndGet(3_000L * MILLIS);
        assertThat(throttle.shouldSend(player)).isTrue();
    }

    @Test
    @DisplayName("a player who leaves is forgotten, so the map stays the size of the player list")
    void quittersAreForgotten() {
        final UUID player = UUID.randomUUID();
        throttle.shouldSend(player);
        assertThat(throttle.tracked()).isEqualTo(1);

        throttle.forget(player);

        assertThat(throttle.tracked()).isZero();
        assertThat(throttle.shouldSend(player)).isTrue();
    }

    @Test
    @DisplayName("nobody is not somebody")
    void nullIsNotSent() {
        assertThat(throttle.shouldSend(null)).isFalse();
        throttle.forget(null);
        assertThat(throttle.tracked()).isZero();
    }
}
