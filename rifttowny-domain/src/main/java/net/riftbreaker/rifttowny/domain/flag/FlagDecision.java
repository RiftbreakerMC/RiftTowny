package net.riftbreaker.rifttowny.domain.flag;

import java.util.Objects;

/**
 * The answer to one protection question, and where it came from.
 *
 * @param flag what was asked about
 * @param relationship the relationship it was resolved at, which for a world flag is always
 *        {@link Relationship#WILDERNESS} whatever the actor's actual standing
 * @param allowed the answer
 * @param source which layer decided
 */
public record FlagDecision(
        ProtectionFlag flag,
        Relationship relationship,
        boolean allowed,
        FlagSource source
) {

    public FlagDecision {
        Objects.requireNonNull(flag, "flag");
        Objects.requireNonNull(relationship, "relationship");
        Objects.requireNonNull(source, "source");
    }

    public boolean denied() {
        return !allowed;
    }

    /** A line an operator can act on: what, for whom, and which layer said so. */
    public String explain() {
        return flag + " for " + relationship + " is " + (allowed ? "allowed" : "denied")
                + " by " + source;
    }
}
