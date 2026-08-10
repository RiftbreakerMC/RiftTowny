package net.riftbreaker.rifttowny.domain.outbox;

/**
 * Outbox depth by status.
 *
 * <p>A rising {@code pending} count means delivery is falling behind; any {@code failed} at all
 * means something needs a human.</p>
 */
public record OutboxCounts(long pending, long claimed, long delivered, long failed) {

    public static final OutboxCounts EMPTY = new OutboxCounts(0L, 0L, 0L, 0L);

    public long total() {
        return pending + claimed + delivered + failed;
    }

    public boolean healthy() {
        return failed == 0L;
    }
}
