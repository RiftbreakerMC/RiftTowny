package net.riftbreaker.rifttowny.domain.naming;

/**
 * Why a proposed name was refused.
 *
 * <p>An enum rather than a string so the message can be localised at the edge and the reason can be
 * asserted in a test. "Invalid name" with no reason is a support ticket.</p>
 */
public enum NameProblem {

    /** Nothing but whitespace, or nothing at all. */
    EMPTY,

    /** Shorter than the configured minimum. */
    TOO_SHORT,

    /** Longer than the configured maximum. */
    TOO_LONG,

    /** Contains a character outside letters, digits, hyphen and underscore. */
    ILLEGAL_CHARACTER,

    /** Contains whitespace. Separate from {@link #ILLEGAL_CHARACTER} because it is the common slip. */
    CONTAINS_WHITESPACE,

    /** Does not start with a letter. Leading digits and punctuation sort badly and read as ids. */
    MUST_START_WITH_LETTER,

    /** Matches a word reserved for the server, such as {@code admin} or {@code wilderness}. */
    RESERVED_WORD
}
