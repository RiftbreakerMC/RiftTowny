package net.riftbreaker.rifttowny.api.map;

/**
 * Cheap, read-only generation counters for the three RiftTowny map caches.
 *
 * <p>These counters are scoped to the currently running RiftTowny process and
 * are not persistent revisions. They may restart at zero after a reboot.
 * The territory counter applies to ALL worlds, so a mutation in the Nether
 * may invalidate a cached Overworld snapshot. Consumers should tolerate such
 * unnecessary refreshes rather than trying to infer changes from player data.</p>
 *
 * <p>A revision check is advisory. Each full snapshot includes its own
 * captured revision; use that value to publish consistent updates, not the
 * value returned by a later call to this record.</p>
 *
 * @param territory global territory-index generation
 * @param towns town-cache generation
 * @param nations nation-cache generation
 */
public record MapRevisions(long territory, long towns, long nations) {
    public MapRevisions {
        if (territory < 0 || towns < 0 || nations < 0) {
            throw new IllegalArgumentException("Map revisions must be nonnegative");
        }
    }
}
