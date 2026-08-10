package net.riftbreaker.rifttowny.api;

/**
 * The version of the RiftTowny API a consumer compiled against.
 *
 * <p>Semantics are deliberately narrow so negotiation is a decision rather than a guess: a
 * consumer states the version it was built for and the running plugin answers whether it can
 * honour it. A consumer built for an older minor version keeps working; one built for a newer
 * minor version does not, because it may call methods this build does not have.</p>
 *
 * @param major incremented on a breaking change; never compatible across values
 * @param minor incremented on an additive change
 */
public record ApiVersion(int major, int minor) implements Comparable<ApiVersion> {

    /** The version implemented by this artifact. */
    public static final ApiVersion CURRENT = new ApiVersion(0, 1);

    public ApiVersion {
        if (major < 0 || minor < 0) {
            throw new IllegalArgumentException("API version components cannot be negative: " + major + '.' + minor);
        }
    }

    /**
     * Whether a consumer compiled against {@code this} can run against {@code running}.
     *
     * <p>Same major, and the running build is at least as new.</p>
     */
    public boolean isSupportedBy(final ApiVersion running) {
        return running != null && running.major == major && running.minor >= minor;
    }

    @Override
    public int compareTo(final ApiVersion other) {
        final int byMajor = Integer.compare(major, other.major);
        return byMajor != 0 ? byMajor : Integer.compare(minor, other.minor);
    }

    @Override
    public String toString() {
        return major + "." + minor;
    }
}
