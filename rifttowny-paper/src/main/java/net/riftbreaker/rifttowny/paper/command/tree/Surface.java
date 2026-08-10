package net.riftbreaker.rifttowny.paper.command.tree;

/**
 * A way a player can reach an action.
 *
 * <p>Declared per action so the rule "no feature is GUI-only" is checkable rather than aspirational.
 * A GUI is the easiest surface to build and the easiest to leave as the only one, which locks out
 * anybody scripting, anybody on a screen reader, and — until the forms exist — every Bedrock
 * player.</p>
 */
public enum Surface {

    /** A command with full tab completion. The baseline every action must have. */
    COMMAND,

    /** A clickable Adventure component, so the action is reachable from a message. */
    CHAT,

    /** A Java inventory menu. */
    GUI,

    /** A native Floodgate form for Bedrock clients. */
    BEDROCK_FORM
}
