package net.riftbreaker.rifttowny.domain.org;

/** Why a membership change was refused. */
public enum MembershipDenial {

    /** A resident may belong to exactly one town, and already belongs to a different one. */
    ALREADY_IN_ANOTHER_TOWN,

    /** Already a resident of this town. */
    ALREADY_IN_THIS_TOWN,

    /** Not a resident of the town they are trying to leave. */
    NOT_A_RESIDENT_OF_THIS_TOWN,

    /**
     * The last resident cannot simply walk out.
     *
     * <p>Leaving would strand the town with claims, a bank balance and no one able to act. The town
     * must be disbanded — which settles the bank and releases the claims — rather than abandoned.</p>
     */
    LAST_RESIDENT_MUST_DISBAND_INSTEAD,

    /**
     * The mayor cannot leave while anyone else remains.
     *
     * <p>Transferring the mayoralty first is the only safe order: a town with residents and no
     * leader cannot grant itself one.</p>
     */
    MAYOR_MUST_TRANSFER_FIRST,

    /** A new leader must already be a resident of the organisation they are to lead. */
    LEADER_MUST_BE_A_MEMBER,

    /** That person already leads this organisation. */
    ALREADY_THE_LEADER,

    /** A town may belong to exactly one nation, and already belongs to a different one. */
    TOWN_ALREADY_IN_ANOTHER_NATION,

    /** The town is already in this nation. */
    TOWN_ALREADY_IN_THIS_NATION,

    /** The town is not in this nation. */
    TOWN_NOT_IN_THIS_NATION,

    /**
     * A nation's capital must be one of its member towns.
     *
     * <p>Allowing otherwise would let a nation's spawn and identity live in territory it does not
     * control, which then breaks on the town leaving.</p>
     */
    CAPITAL_MUST_BE_A_MEMBER_TOWN,

    /**
     * The capital cannot leave while other towns remain.
     *
     * <p>The nation must move its capital first, for the same reason a mayor must transfer first.</p>
     */
    CAPITAL_MUST_MOVE_FIRST
}
