package net.riftbreaker.rifttowny.api.map;

import java.util.UUID;

/**
 * Read-only, server-side map capability for consumers such as RiftMap.
 *
 * <p>These methods return immutable public value types: no Bukkit objects,
 * no database handles, and no mutable internal RiftTowny aggregates.
 * Access is intentionally not a network endpoint. RiftMap is responsible
 * for publishing authorized, sanitized output through its own web layer.</p>
 *
 * <p>The snapshot methods scan in-memory caches. Call them from a bounded,
 * rate-limited asynchronous job; never from a hot protection listener, a
 * region tick, or synchronously for every HTTP request. Later delta events
 * can be added without removing the polling/revision fallback.</p>
 */
public interface RiftTownyMapApi {
    /**
     * Read three atomic counters without enumerating any claims or civic rows.
     * Poll this first; build a full snapshot only when a relevant counter changes.
     * Each counter is independent and resets when the plugin restarts.
     */
    MapRevisions revisions();

    /** Complete, atomically copied claimed chunks for a single world. */
    TerritoryMapSnapshot claims(UUID worldId);

    /** Public town and nation facts; each cache has its own revision. */
    CivicMapSnapshot civics();
}
