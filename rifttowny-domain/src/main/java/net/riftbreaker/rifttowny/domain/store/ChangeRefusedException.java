package net.riftbreaker.rifttowny.domain.store;

import net.riftbreaker.rifttowny.domain.org.ChangeDenial;

import java.util.Objects;

/**
 * Thrown to abort a transaction when an aggregate refused a change.
 *
 * <p>A denial is not an error in the usual sense — it is an expected answer to a player's request —
 * but inside a transaction it has to become an exception, because that is the only way to roll back
 * writes already made. The service catches it at the boundary and turns it back into a message.</p>
 *
 * <p>The stack trace is suppressed: this is control flow across a transaction boundary, thrown
 * routinely whenever someone tries to join a town twice, and filling in a trace for each would cost
 * more than the whole operation.</p>
 */
public final class ChangeRefusedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient ChangeDenial denial;

    public ChangeRefusedException(final ChangeDenial denial) {
        super(Objects.requireNonNull(denial, "denial").name(), null, false, false);
        this.denial = denial;
    }

    public ChangeDenial denial() {
        return denial;
    }
}
