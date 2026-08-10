package net.riftbreaker.rifttowny.domain.flag;

/**
 * How the actor stands to the land they are acting on.
 *
 * <p>Ordered from least to most entitled. The ordering is not decoration: a configuration that
 * lets a visitor do something a trusted outsider cannot is almost always a mistake, and
 * {@link FlagSettings#firstNonMonotonic} uses this order to find those.</p>
 */
public enum Relationship {

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
}
