package net.riftbreaker.rifttowny.domain.flag;

/**
 * What kind of ground a position is, independent of who is standing on it.
 *
 * <p>Separate from {@link Relationship} because the two answer different questions and one value
 * cannot do both. A relationship says what standing the actor has; this says whose land it is. They
 * came apart the first time a world flag was resolved inside a town: the flag reports at
 * {@link Relationship#WILDERNESS} so a member standing nearby cannot widen it, and reading that as
 * "nobody owns this" made every explosion inside a claim legal.</p>
 */
public enum LandState {

    /** Nobody owns it. Behaves like vanilla.  */
    WILDERNESS,

    /** A town owns it. */
    CLAIMED,

    /**
     * A town owned it, and has fallen.
     *
     * <p>Its own state rather than either neighbour, because a ruin is neither. Treating it as
     * wilderness would let the first passer-by dismantle a town the moment it fell; treating it as
     * claimed would protect it as though somebody were still there to object.</p>
     */
    RUIN
}
