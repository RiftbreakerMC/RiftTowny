package net.riftbreaker.rifttowny.domain.migration;

/**
 * Somewhere a {@link MigrationPlan} can be read from.
 *
 * <p>An interface with one method, and the whole point of it is that the importer never learns
 * where the data came from. The hard half of a migration is the writing — ordering, collisions,
 * half-finished runs — and none of that should have to be reasoned about again per source.</p>
 *
 * <p><strong>The constraint that decides every implementation of this.</strong> RiftTowny calls
 * {@code disablePlugin} on itself when Towny is present, because the command tree and the
 * {@code %townyadvanced_*%} namespace both collide. So the two plugins are never running at the
 * same time, and no source can read Towny through its API — there is no live Towny to ask. Every
 * source is necessarily <em>offline</em>: it reads what the other plugin left behind, on disk or in
 * its own database, while it is not running.</p>
 *
 * <p>Reading is separated from writing for one more reason: a source can be exercised against a
 * fixture. A reader that wrote as it went could only be tested against a real server.</p>
 */
@FunctionalInterface
public interface MigrationSource {

    /**
     * Reads everything, without writing anything.
     *
     * @throws MigrationException when the source cannot be read at all — a missing file, an
     *         unreadable database, a format that is not what it claimed. A source that is
     *         <em>partly</em> readable should return what it has and let the importer report the
     *         gaps, because half a town is a decision for the operator rather than for the reader
     */
    MigrationPlan read() throws MigrationException;

    /** Thrown when a source cannot be read. Carries a message an operator can act on. */
    class MigrationException extends Exception {

        private static final long serialVersionUID = 1L;

        public MigrationException(final String message) {
            super(message);
        }

        public MigrationException(final String message, final Throwable cause) {
            super(message, cause);
        }
    }
}
