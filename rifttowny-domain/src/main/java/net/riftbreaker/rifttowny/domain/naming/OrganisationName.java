package net.riftbreaker.rifttowny.domain.naming;

import java.util.Objects;

/**
 * A validated organisation name, carrying the three forms the system needs.
 *
 * <p>Keeping them together in one value is what stops the classic bug where a name is validated,
 * then stored with the wrong casing, then looked up by a differently-derived key and not found.</p>
 *
 * @param display what players see, with the founder's capitalisation intact
 * @param normalised the uniqueness key, stored in {@code name_normalised} and covered by a unique
 *        constraint. Case-folded only, so it round-trips predictably
 * @param skeleton a lookalike-folded form used <em>only</em> to detect impersonation attempts. Never
 *        stored as the uniqueness key: it is deliberately lossy, and two legitimately different
 *        names can share a skeleton
 */
public record OrganisationName(String display, String normalised, String skeleton) {

    public OrganisationName {
        Objects.requireNonNull(display, "display");
        Objects.requireNonNull(normalised, "normalised");
        Objects.requireNonNull(skeleton, "skeleton");
    }

    /** Whether two names would collide on the uniqueness key. */
    public boolean collidesWith(final OrganisationName other) {
        return other != null && normalised.equals(other.normalised);
    }

    /**
     * Whether this name is a plausible impersonation of another.
     *
     * <p>True when the skeletons match but the uniqueness keys do not — that is, the names are
     * genuinely different rows but would be hard to tell apart in chat. A collision on the
     * uniqueness key is a different problem, already refused by the database.</p>
     */
    public boolean looksLike(final OrganisationName other) {
        return other != null && skeleton.equals(other.skeleton) && !normalised.equals(other.normalised);
    }

    @Override
    public String toString() {
        return display;
    }
}
