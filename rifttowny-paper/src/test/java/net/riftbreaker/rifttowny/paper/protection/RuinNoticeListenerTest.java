package net.riftbreaker.rifttowny.paper.protection;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one part of the ruin notice that is not Bukkit: how a remaining duration reads.
 *
 * <p>The listener itself needs a server to exercise, so it is covered by compilation and review.
 * This is the piece with a decision in it.
 */
class RuinNoticeListenerTest {

    @Test
    @DisplayName("hours are shown in hours")
    void hours() {
        assertThat(RuinNoticeListener.describe(Duration.ofHours(72))).isEqualTo("72h");
        assertThat(RuinNoticeListener.describe(Duration.ofMinutes(133))).isEqualTo("2h");
    }

    @Test
    @DisplayName("under an hour falls back to minutes rather than reading 0h")
    void minutes() {
        assertThat(RuinNoticeListener.describe(Duration.ofMinutes(59))).isEqualTo("59m");
        assertThat(RuinNoticeListener.describe(Duration.ofSeconds(90))).isEqualTo("1m");
    }

    @Test
    @DisplayName("the last minute says so in words, because '0m' reads as expired")
    void almostGone() {
        assertThat(RuinNoticeListener.describe(Duration.ofSeconds(30)))
                .isEqualTo("less than a minute");
        assertThat(RuinNoticeListener.describe(Duration.ZERO)).isEqualTo("less than a minute");
    }
}
