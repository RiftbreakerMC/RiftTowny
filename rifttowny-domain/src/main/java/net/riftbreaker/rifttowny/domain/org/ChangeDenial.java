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

    /** The town belongs to no nation, so there is nothing to leave. */
    TOWN_NOT_IN_A_NATION,

    /**
     * That person is not a resident of any of this nation's towns.
     *
     * <p>Handing a nation role to somebody outside it would give a stranger authority over towns
     * they do not belong to.</p>
     */
    NOT_A_CITIZEN_OF_THIS_NATION,

    /**
     * No outstanding invitation.
     *
     * <p>Joining takes two consents. A nation that could admit a town unilaterally would move that
     * town's protection relationship without asking it, and a town that could attach itself to any
     * nation would walk into every member town's territory as a citizen.</p>
     */
    NO_INVITATION,

    /** The invitation lapsed. A nation that invited a town a month ago has not agreed to today's. */
    INVITATION_EXPIRED,

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

    /**
     * Outlawing a resident of the declaring town.
     *
     * <p>Refused rather than silently ignored. Membership already supersedes outlawry when the
     * relationship is resolved, so an outlawed member would have been harmless — but a mayor who
     * typed it and saw nothing happen would reasonably conclude the feature was broken.</p>
     */
    CANNOT_OUTLAW_A_RESIDENT,

    /** Already outlawed by this town. */
    ALREADY_OUTLAWED,

    /** Not outlawed by this town. */
    NOT_OUTLAWED,

    /**
     * Merging two towns that do not share a nation.
     *
     * <p>Refused rather than resolved, and the reason is whose decision it is. Allowing it would
     * either enrol a townful of strangers in a nation that never invited them, or take a member town
     * out of a nation on two town mayors' word. Both belong to the nation, and both already have a
     * command. Refusing costs the mayors one they already know.</p>
     */
    MERGE_REQUIRES_THE_SAME_NATION,

    /** A town cannot merge with itself. */
    CANNOT_MERGE_WITH_SELF,

    // --- presentation ---------------------------------------------------------------------------

    /**
     * The setting already holds that value.
     *
     * <p>Refused rather than accepted quietly: applying it would be a transaction, an event, an
     * outbox row and a cache refresh to store what was already stored.</p>
     */
    NOTHING_TO_CHANGE,

    /** The colour was not six hex digits. */
    NOT_A_COLOUR,

    // --- diplomacy ------------------------------------------------------------------------------

    /** A nation cannot declare a relation with itself. */
    CANNOT_DECLARE_ON_SELF,

    /** No such declaration stands, so there is nothing to withdraw. */
    NO_SUCH_DECLARATION,

    /** The town has not declared itself open, so joining it needs an invitation. */
    TOWN_IS_NOT_OPEN,

    /** The town's spawn is not public, so only its own residents may travel to it. */
    TOWN_SPAWN_IS_NOT_PUBLIC,

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

    // --- roles -------------------------------------------------------------------------------

    /** No such role in this organisation. */
    ROLE_NOT_FOUND,

    /** Another role in this organisation already has that name. */
    ROLE_NAME_TAKEN,

    /**
     * A system role cannot be deleted.
     *
     * <p>Leader, member and visitor are the roles every other definition is expressed in terms of.
     * A town that deleted its leader role would have no way to grant itself another.</p>
     */
    SYSTEM_ROLE_CANNOT_BE_DELETED,

    /** A system role's priority is fixed, so configurable roles can be placed relative to it. */
    SYSTEM_ROLE_PRIORITY_IS_FIXED,

    /**
     * The leader role holds every permission and cannot be edited.
     *
     * <p>A leader stripped of the permission to assign roles could not undo the change, turning a
     * misconfiguration into a permanently broken organisation.</p>
     */
    LEADER_PERMISSIONS_ARE_FIXED,

    /**
     * The leader role is granted by transferring leadership, not by assigning a role.
     *
     * <p>Two paths to the same authority would let a town acquire a second leader without the
     * atomic bank and capital updates a real transfer performs.</p>
     */
    LEADER_ROLE_IS_NOT_ASSIGNABLE,

    /** Membership itself grants the member role; it is never assigned by hand. */
    BASELINE_ROLE_IS_NOT_ASSIGNABLE,

    /** Another role already sits at that priority. */
    PRIORITY_ALREADY_USED,

    /** Nothing may sit at or above the leader's priority. */
    PRIORITY_RESERVED_FOR_LEADER,

    /**
     * The actor's highest role does not outrank the role they are trying to manage.
     *
     * <p>Strictly outrank: equal priority is refused, or two officers of the same rank could
     * demote each other in a loop.</p>
     */
    INSUFFICIENT_ROLE_PRIORITY,

    /** The actor lacks the permission this operation needs. */
    MISSING_PERMISSION,

    /**
     * The actor tried to put a permission into a role that they do not hold themselves.
     *
     * <p>Without this an officer with only {@code MANAGE_ROLES} could write {@code DISBAND} into a
     * role and assign it to themselves. Role editing has to be bounded by what the editor already
     * has, or it is a way of granting yourself anything.</p>
     */
    CANNOT_GRANT_UNHELD_PERMISSION,

    /**
     * The actor tried to create or move a role to a rank at or above their own.
     *
     * <p>Same escalation in the other direction: a role above the actor is one they could not
     * subsequently manage, and one they could assign to themselves to outrank their superiors.</p>
     */
    CANNOT_CREATE_ROLE_ABOVE_SELF,

    /** That permission is locked by the server administrator and no configurable role may hold it. */
    PERMISSION_LOCKED_BY_ADMIN,

    /** The resident already holds that role. */
    ALREADY_HAS_ROLE,

    /** The resident does not hold that role. */
    DOES_NOT_HAVE_ROLE,

    /** The role already grants that permission. */
    PERMISSION_ALREADY_GRANTED,

    /** The role does not grant that permission. */
    PERMISSION_NOT_GRANTED,

    // --- territory ---------------------------------------------------------------------------

    /** This town already owns that chunk. */
    CHUNK_ALREADY_CLAIMED,

    /**
     * Another town owns that chunk.
     *
     * <p>Checked inside the transaction, not before it. The unique constraint on
     * {@code (world_id, chunk_x, chunk_z)} is the real guard against two towns claiming the same
     * chunk at once; this denial is how that guard becomes a sentence.</p>
     */
    CHUNK_OWNED_BY_ANOTHER_TOWN,

    /** That chunk is not one of this town's claims. */
    CHUNK_NOT_CLAIMED,

    /** The town has no spawn set. */
    NO_TOWN_SPAWN,

    // --- money -------------------------------------------------------------------------------

    /**
     * No economy plugin is installed.
     *
     * <p>The civic ledger works regardless — a town has a balance and a history — but money cannot
     * cross between a player and an organisation when there is nothing on the player's side.</p>
     */
    NO_ECONOMY,

    /** Zero is not an amount. */
    AMOUNT_MUST_BE_POSITIVE,

    /** The player does not have that much. */
    INSUFFICIENT_FUNDS,

    /** The organisation does not have that much. */
    INSUFFICIENT_CIVIC_FUNDS,

    /** The economy plugin declined, and the money has been put back. */
    ECONOMY_REFUSED,

    // --- plots -------------------------------------------------------------------------------

    /** Somebody else already holds that plot. */
    PLOT_ALREADY_HELD,

    /** You already hold that plot. */
    ALREADY_HOLD_THIS_PLOT,

    /** Nobody holds that plot, so there is nothing to give back. */
    PLOT_NOT_HELD,

    /** The plot is already used for that. */
    PLOT_ALREADY_THAT_TYPE,

    // --- ruins -------------------------------------------------------------------------------

    /** There is no fallen town here to take on. */
    NOT_A_RUIN,

    /**
     * The ruin's window has closed.
     *
     * <p>Separate from {@link #NOT_A_RUIN} because the two want different answers: one means "look
     * elsewhere", the other means "you were too late", and telling a player the ruin they are
     * standing in does not exist would read as a bug.</p>
     */
    RUIN_HAS_LAPSED,

    /**
     * The ruin has not lain open long enough to be rebuilt.
     *
     * <p>The delay is what stops a mayor disbanding and immediately re-founding on the same ground,
     * shedding whatever the town had accumulated while keeping its land.</p>
     */
    RUIN_NOT_YET_RECLAIMABLE,

    // --- flags -------------------------------------------------------------------------------

    /**
     * The flag was already at that setting, or there was nothing to clear.
     *
     * <p>Refused rather than accepted quietly so a mayor is told their command did nothing, instead
     * of believing they changed something they did not.</p>
     */
    FLAG_NOT_SET,

    /**
     * That land or organisation is not yours to configure.
     *
     * <p>The resolver reads a claim's overrides without asking who wrote them, so a town that could
     * write one on somebody else's chunk could open their doors from outside.</p>
     */
    FLAG_TARGET_NOT_YOURS,

    /**
     * An ordinary claim must touch the town's existing territory.
     *
     * <p>Without it a town is a scatter of disconnected squares, which makes borders meaningless
     * and lets a player fence off distant land for the price of one chunk.</p>
     */
    CLAIM_MUST_TOUCH_TOWN,

    /**
     * An outpost must not touch the town.
     *
     * <p>An outpost adjacent to the town is an ordinary claim wearing a costume, and would let a
     * player pay outpost prices for normal expansion.</p>
     */
    OUTPOST_MUST_NOT_TOUCH_TOWN,

    /**
     * Unclaiming that chunk would strand others.
     *
     * <p>Refused rather than silently converting the stranded part into outposts: which half of a
     * severed town is the "real" one is a decision only the mayor can make.</p>
     */
    UNCLAIM_WOULD_DISCONNECT,

    /** The homeblock is the last chunk that may be unclaimed. */
    HOMEBLOCK_MUST_BE_UNCLAIMED_LAST,

    /** The first chunk a town claims becomes its homeblock; it cannot be anything else. */
    FIRST_CLAIM_MUST_BE_THE_HOMEBLOCK,

    /** That chunk is already the homeblock. */
    ALREADY_THE_HOMEBLOCK,

    // --- lookup ------------------------------------------------------------------------------

    /** No such town. */
    TOWN_NOT_FOUND,

    /** No such nation. */
    NATION_NOT_FOUND,

    /** No such resident. The player has never been seen by RiftTowny. */
    RESIDENT_NOT_FOUND
}
