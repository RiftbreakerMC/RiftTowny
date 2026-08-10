package net.riftbreaker.rifttowny.domain.org;

/**
 * Why an aggregate refused a change.
 *
 * <p>An enum rather than a message so the reason can be localised at the edge and asserted in a
 * test. "That didn't work" with no reason is a support ticket.</p>
 */
public enum ChangeDenial {

    // --- town membership ---------------------------------------------------------------------

    /** A resident belongs to exactly one town, and already belongs to a different one. */
    ALREADY_IN_ANOTHER_TOWN,

    /** Already a resident of this town. */
    ALREADY_IN_THIS_TOWN,

    /** Not a resident of the town in question. */
    NOT_A_RESIDENT_OF_THIS_TOWN,

    /**
     * The last resident cannot simply walk out.
     *
     * <p>Leaving would strand the town with claims, a bank balance and nobody able to act. It must
     * be disbanded — which settles the bank and releases the claims — rather than abandoned.</p>
     */
    LAST_RESIDENT_MUST_DISBAND_INSTEAD,

    /**
     * The mayor cannot leave or be removed while anyone else remains.
     *
     * <p>Transferring first is the only safe order: a town with residents and no leader cannot
     * grant itself one.</p>
     */
    MAYOR_MUST_TRANSFER_FIRST,

    // --- leadership --------------------------------------------------------------------------

    /** A leader must already belong to the organisation they are to lead. */
    LEADER_MUST_BE_A_MEMBER,

    /** That person already leads this organisation. */
    ALREADY_THE_LEADER,

    // --- nation membership -------------------------------------------------------------------

    /** A town belongs to at most one nation, and already belongs to a different one. */
    TOWN_ALREADY_IN_ANOTHER_NATION,

    /** The town is already in this nation. */
    TOWN_ALREADY_IN_THIS_NATION,

    /** The town is not in this nation, or is in no nation at all. */
    TOWN_NOT_IN_THIS_NATION,

    /**
     * A nation's capital must be one of its member towns.
     *
     * <p>Otherwise a nation's spawn and identity live in territory it does not control, and it
     * breaks the moment that town leaves.</p>
     */
    CAPITAL_MUST_BE_A_MEMBER_TOWN,

    /** The capital cannot leave while other towns remain; the nation must move it first. */
    CAPITAL_MUST_MOVE_FIRST,

    /** Already the capital. */
    ALREADY_THE_CAPITAL,

    // --- trust -------------------------------------------------------------------------------

    /**
     * A resident cannot be "trusted" as an outsider.
     *
     * <p>Trust is the mechanism for granting an outsider narrow permissions. Applying it to a
     * member would create a second, weaker path to rights the member already has, and that is
     * exactly the kind of overlap that later gets mistaken for membership.</p>
     */
    CANNOT_TRUST_A_RESIDENT,

    /** Already trusted by this town. */
    ALREADY_TRUSTED,

    /** Not trusted by this town. */
    NOT_TRUSTED,

    // --- naming ------------------------------------------------------------------------------

    /** The new name is identical to the current one, so there is nothing to change. */
    NAME_UNCHANGED,

    /**
     * Another organisation already holds that name.
     *
     * <p>Checked inside the transaction rather than before it. Checking first would leave a window
     * in which two founders both saw the name free; the unique constraint on
     * {@code name_normalised} is the real guard, and this denial is how that guard is reported.</p>
     */
    NAME_TAKEN,

    // --- lookup ------------------------------------------------------------------------------

    /** No such town. */
    TOWN_NOT_FOUND,

    /** No such nation. */
    NATION_NOT_FOUND,

    /** No such resident. The player has never been seen by RiftTowny. */
    RESIDENT_NOT_FOUND
}
