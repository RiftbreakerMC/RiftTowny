package net.riftbreaker.rifttowny.api.scheduler;

/**
 * A handle on scheduled work.
 *
 * <p>Paper and Folia return different task types with different cancellation semantics; this is the
 * single shape RiftTowny code sees. {@link #cancel()} is idempotent on both platforms.</p>
 */
public interface RiftTask {

    /** Cancels the task if it has not already run or been cancelled. Safe to call more than once. */
    void cancel();

    /** Whether {@link #cancel()} has been called, or the platform has reported the task cancelled. */
    boolean isCancelled();

    /** A handle for work that was executed immediately and can no longer be cancelled. */
    RiftTask COMPLETED = new RiftTask() {
        @Override
        public void cancel() {
            // Already finished; nothing to cancel.
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public String toString() {
            return "RiftTask.COMPLETED";
        }
    };
}
