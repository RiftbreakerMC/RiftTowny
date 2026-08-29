package net.riftbreaker.rifttowny.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Whether another plugin may talk to this one.
 *
 * <p>The answer decides whether a dependent plugin loads or refuses, and it is asked once at
 * startup where a wrong answer is either a plugin that will not run or — worse — one that runs
 * against a surface it was not compiled for.
 */
class ApiVersionTest {

    @Test
    @DisplayName("the same version supports itself")
    void sameVersion() {
        assertThat(new ApiVersion(1, 4).isSupportedBy(new ApiVersion(1, 4))).isTrue();
    }

    @Test
    @DisplayName("a newer minor still supports an older consumer, which is the point of minors")
    void newerMinorIsCompatible() {
        assertThat(new ApiVersion(1, 2).isSupportedBy(new ApiVersion(1, 7))).isTrue();
    }

    @Test
    @DisplayName("an older minor does not, since the consumer wants methods it lacks")
    void olderMinorIsNot() {
        assertThat(new ApiVersion(1, 7).isSupportedBy(new ApiVersion(1, 2))).isFalse();
    }

    @Test
    @DisplayName("a different major never matches, in either direction")
    void majorsDoNotMix() {
        // The whole meaning of a major: 2.0 is not a newer 1.x, it is a different surface, and
        // "at least as new" must not be allowed to bridge that.
        assertThat(new ApiVersion(1, 0).isSupportedBy(new ApiVersion(2, 0))).isFalse();
        assertThat(new ApiVersion(2, 0).isSupportedBy(new ApiVersion(1, 0))).isFalse();
    }

    @Test
    @DisplayName("nothing supports a missing version")
    void nullSupportsNothing() {
        assertThat(ApiVersion.CURRENT.isSupportedBy(null)).isFalse();
    }

    @Test
    @DisplayName("ordering runs by major first, then minor")
    void ordering() {
        assertThat(new ApiVersion(1, 9)).isLessThan(new ApiVersion(2, 0));
        assertThat(new ApiVersion(1, 2)).isLessThan(new ApiVersion(1, 10));
        assertThat(new ApiVersion(1, 2)).isEqualByComparingTo(new ApiVersion(1, 2));
    }

    @Test
    @DisplayName("the published version supports itself, which is the only self-evident case")
    void currentIsSelfCompatible() {
        // Reads as trivial and is not: CURRENT is what the plugin reports at runtime and what
        // consumers compile against, so a release that fails this is one nothing can talk to.
        assertThat(ApiVersion.CURRENT.isSupportedBy(ApiVersion.CURRENT)).isTrue();
    }
}
