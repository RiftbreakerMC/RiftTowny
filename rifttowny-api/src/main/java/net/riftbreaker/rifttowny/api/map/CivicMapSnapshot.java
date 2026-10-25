package net.riftbreaker.rifttowny.api.map;

import java.util.List;
import java.util.Objects;

/**
 * Read-only public town and nation directory for map overlays.
 *
 * <p>Town and nation caches are maintained independently after transactions.
 * Their revisions are therefore independent, and a short-lived difference
 * during updates is possible. Clients should treat the directory as
 * eventually consistent, refresh if either revision changes, and avoid
 * interpreting a missing affiliation as an authorization decision.</p>
 *
 * <p>No player membership, bank balances, private preferences or internal
 * role/permission models are exported.</p>
 */
public record CivicMapSnapshot(long townRevision, long nationRevision,
                               List<MapTown> towns, List<MapNation> nations) {
    public CivicMapSnapshot {
        if (townRevision < 0 || nationRevision < 0) {
            throw new IllegalArgumentException("revisions must be nonnegative");
        }
        Objects.requireNonNull(towns, "towns");
        Objects.requireNonNull(nations, "nations");
        towns = List.copyOf(towns);
        nations = List.copyOf(nations);
    }
}
