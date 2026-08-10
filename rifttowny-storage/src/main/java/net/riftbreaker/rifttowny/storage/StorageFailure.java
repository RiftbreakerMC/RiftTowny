package net.riftbreaker.rifttowny.storage;

import java.sql.SQLException;

/**
 * Turns a checked {@link SQLException} into an unchecked failure.
 *
 * <p>The transactional store interfaces are synchronous and unchecked so a service body reads as
 * domain logic rather than as plumbing. The exception still has to travel — a failed statement must
 * roll the transaction back — so it is wrapped here and unwrapped by the runner, which completes the
 * caller's future exceptionally with the original {@link SQLException} as the cause.</p>
 */
public final class StorageFailure extends RuntimeException {

    private static final long serialVersionUID = 1L;

    StorageFailure(final SQLException cause) {
        super(cause.getMessage(), cause);
    }

    /** Runs a JDBC call, wrapping any {@link SQLException}. */
    static <T> T wrapping(final SqlCall<T> call) {
        try {
            return call.get();
        } catch (final SQLException failure) {
            throw new StorageFailure(failure);
        }
    }

    /** A JDBC call that may fail. */
    @FunctionalInterface
    interface SqlCall<T> {
        T get() throws SQLException;
    }
}
