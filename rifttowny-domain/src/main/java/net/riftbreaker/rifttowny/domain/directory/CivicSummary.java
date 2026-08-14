package net.riftbreaker.rifttowny.domain.directory;

import java.time.Instant;

/**
 * What every listing shows about an organisation, whatever kind it is.
 *
 * <p>It exists so that sorting is written once. A town list and a nation list are the same screen
 * with different rows, and giving each its own comparator set would mean two places where "sort by
 * size" could come to mean different things.</p>
 */
public interface CivicSummary {

    /** The name as its founder typed it. */
    String name();

    /** How many people, counting through member towns for a nation. */
    int residents();

    /** How many chunks, counting through member towns for a nation. */
    int chunks();

    /** When it was founded. */
    Instant foundedAt();
}
