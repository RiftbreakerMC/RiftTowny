package net.riftbreaker.rifttowny.domain.flag;

import net.riftbreaker.rifttowny.domain.org.ResidentId;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * One stored opinion: this flag, for this relationship, at this target, is allowed or denied.
 *
 * <p>Who set it and when are carried because a flag change is the answer to "why can visitors
 * suddenly build here" and neither the resolver nor the audit log can reconstruct it afterwards.</p>
 *
 * @param setBy the resident who set it, or null for one applied by configuration or by an operator
 *        through the console
 */
public record FlagOverride(
        FlagTarget target,
        ProtectionFlag flag,
        Relationship relationship,
        boolean allowed,
        ResidentId setBy,
        Instant setAt
) {

    public FlagOverride {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(flag, "flag");
        Objects.requireNonNull(relationship, "relationship");
        Objects.requireNonNull(setAt, "setAt");
        if (!target.source().isConfigurable()) {
            // A war layer is supplied for as long as the war lasts and the built-in default is
            // code. Persisting either would give two places to change one answer.
            throw new IllegalArgumentException(
                    "Flag overrides cannot be stored at " + target.source());
        }
    }

    public static FlagOverride of(
            final FlagTarget target,
            final ProtectionFlag flag,
            final Relationship relationship,
            final boolean allowed,
            final ResidentId setBy,
            final Instant now
    ) {
        return new FlagOverride(target, flag, relationship, allowed, setBy, now);
    }

    public Optional<ResidentId> author() {
        return Optional.ofNullable(setBy);
    }

    public FlagSource source() {
        return target.source();
    }

    /** A line for a listing or an audit entry. */
    public String describe() {
        return flag + " for " + relationship + " is " + (allowed ? "allowed" : "denied")
                + " by " + target.source();
    }
}
