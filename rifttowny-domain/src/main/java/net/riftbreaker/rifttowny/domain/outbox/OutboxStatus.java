package net.riftbreaker.rifttowny.domain.outbox;

/** Where an outbox row is in its delivery lifecycle. */
public enum OutboxStatus {

    /** Written, not yet picked up. */
    PENDING,

    /** Claimed by one backend server. Another server must not touch it while the claim is fresh. */
    CLAIMED,

    /** Delivered successfully. Kept for the deduplication window, then pruned. */
    DELIVERED,

    /**
     * Delivery failed permanently after the retry budget.
     *
     * <p>Never deleted automatically: a war declaration that never reached Discord is something an
     * operator needs to be able to find.</p>
     */
    FAILED
}
