package net.riftbreaker.rifttowny.domain.flag;

/**
 * How the actor stands to the land they are acting on.
 *
 * <p>Ordered from least to most entitled. The ordering is not decoration: a configuration that
 * lets a visitor do something a trusted outsider cannot is almost always a mistake, and
 * {@link FlagSettings#firstNonMonotonic} uses this order to find those.</p>
 */
public enum Relationship {

    /**
     * Declared unwelcome by the town whose land this is.
     *
     * <p>Below every other rung, so the defaults already answer correctly for it without a line
     * changing: {@link #isMember} is false and {@link #isAtLeast} fails against every rung, which
     * leaves an outlaw able to walk through a town and do nothing else. A town that wants to soften
     * that configures it like any other relationship — which is the reason this is a rung at all
     * rather than a boolean short-circuit bolted on outside the flag system.</p>
     *
     * <p>Ranked below zero rather than renumbering everything above it. The only comparison that
     * has to hold is that an outlaw ranks under a visitor, and keeping the other seven numbers
     * where they were means adding this constant cannot have changed an answer anywhere else.</p>
     */
    OUTLAW(-1),

    /** Unclaimed land. Nobody owns it, so almost everything is allowed. */
    WILDERNESS(0),

    /** In somebody's claim, with no connection to them. */
    VISITOR(1),

    /** Trusted by the owning town. Narrow permissions, never membership. */
    TRUSTED(2),

    /** A member of a town allied to the owning town. */
    ALLY(3),

    /** A member of another town in the same nation. */
    NATION(4),

    /** A member of the owning town. */
    TOWN(5),

    /**
     * The resident who owns this particular plot.
     *
     * <p>Distinct from {@link #TOWN} so a town can let a member do things on their own plot that
     * they may not do on somebody else's.</p>
     */
    RESIDENT(6);

    private final int rank;

    Relationship(final int rank) {
        this.rank = rank;
    }

    /** Higher means more entitled. Only meaningful relative to another relationship. */
    public int rank() {
        return rank;
    }

    public boolean isAtLeast(final Relationship other) {
        return other != null && rank >= other.rank;
    }

    /** Whether this is a member of the owning organisation, rather than an outsider. */
    public boolean isMember() {
        return this == TOWN || this == RESIDENT;
    }

    /**
     * Parses a stored or player-supplied value.
     *
     * <p>Empty rather than throwing: a typo in a command is a message, not a crash, and a value
     * removed in a later version must not stop a stored override loading.</p>
     */
    public static java.util.Optional<Relationship> parse(final String raw) {
        if (raw == null || raw.isBlank()) {
            return java.util.Optional.empty();
        }
        final String normalised =
                raw.trim().toUpperCase(java.util.Locale.ROOT).replace('-', '_');
        for (final Relationship relationship : values()) {
            if (relationship.name().equals(normalised)) {
                return java.util.Optional.of(relationship);
            }
        }
        return java.util.Optional.empty();
    }
}
