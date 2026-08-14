package net.riftbreaker.rifttowny.paper.message;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How a moment is written down.
 *
 * <p>The cases here are the ones that reach a player as nonsense: "1 days ago", "in -3 minutes" for
 * a clock that has drifted backwards, and a last-seen that says "0 hours ago" for somebody who just
 * logged out.</p>
 */
class TimesTest {

    private static final Instant NOW = Instant.parse("2026-08-12T09:00:00Z");

    @Test
    @DisplayName("intervals use the largest unit that still says something")
    void intervalsPickOneUnit() {
        assertThat(Times.ago(NOW.minusSeconds(90), NOW)).isEqualTo("1 minute ago");
        assertThat(Times.ago(NOW.minusSeconds(3_600 * 5), NOW)).isEqualTo("5 hours ago");
        assertThat(Times.ago(NOW.minusSeconds(86_400 * 3), NOW)).isEqualTo("3 days ago");
        assertThat(Times.ago(NOW.minusSeconds(86_400 * 200), NOW)).isEqualTo("6 months ago");
    }

    @Test
    @DisplayName("something that happened moments ago says so rather than counting zero")
    void veryRecentIsJustNow() {
        assertThat(Times.ago(NOW.minusSeconds(20), NOW)).isEqualTo("just now");
        assertThat(Times.ago(NOW, NOW)).isEqualTo("just now");
    }

    @Test
    @DisplayName("a timestamp in the future reads as just now rather than as negative time")
    void futureTimestampsDoNotGoNegative() {
        // Clocks drift, and on a network two backends disagree. "in -4 minutes" is a bug report.
        assertThat(Times.ago(NOW.plusSeconds(600), NOW)).isEqualTo("just now");
    }

    @Test
    @DisplayName("one of something is singular")
    void singularsAreSingular() {
        assertThat(Times.remaining(Duration.ofDays(1))).isEqualTo("1 day");
        assertThat(Times.remaining(Duration.ofDays(2))).isEqualTo("2 days");
        assertThat(Times.remaining(Duration.ofHours(1))).isEqualTo("1 hour");
        assertThat(Times.remaining(Duration.ofSeconds(1))).isEqualTo("1 second");
    }

    @Test
    @DisplayName("nothing left reads as nothing left")
    void expiredDurationsSaySo() {
        assertThat(Times.remaining(Duration.ZERO)).isEqualTo("no time");
        assertThat(Times.remaining(Duration.ofSeconds(-30))).isEqualTo("no time");
        assertThat(Times.remaining(null)).isEqualTo("no time");
    }

    @Test
    @DisplayName("a missing instant does not render as null")
    void nullsAreHandled() {
        assertThat(Times.date(null)).isEqualTo("unknown");
        assertThat(Times.ago(null, NOW)).isEqualTo("unknown");
    }
}
