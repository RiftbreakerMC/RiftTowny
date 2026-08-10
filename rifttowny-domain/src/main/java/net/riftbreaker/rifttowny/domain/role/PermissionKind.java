package net.riftbreaker.rifttowny.domain.role;

/**
 * The two halves of the permission model.
 *
 * <p>Kept apart because they are granted independently and confusing them is how a town ends up
 * with a "builder" role that can also rewrite who is allowed to build. Being able to do a thing and
 * being able to change who may do it are different powers, and a role may hold either without the
 * other.</p>
 */
public enum PermissionKind {

    /** May I do this — break a block, open a chest, use the town spawn. */
    ACTION,

    /** May I change who does this — edit roles, claim land, set flags, spend the treasury. */
    MANAGEMENT
}
