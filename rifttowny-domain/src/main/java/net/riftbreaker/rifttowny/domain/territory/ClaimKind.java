package net.riftbreaker.rifttowny.domain.territory;

import java.util.Locale;

/**
 * What a claimed chunk is structurally.
 *
 * <p>Only the kinds that change the <em>connectivity</em> rules live here. What a chunk is
 * <em>used</em> for — farm, market, arena — is a plot type, which is a property of the land rather
 * than of the town's shape, and lands with {@code RT-CORE-AREA}. Mixing the two would mean marking
 * a chunk as a market could accidentally change whether the town is still connected.</p>
 */
public enum ClaimKind {

    /**
     * The town's origin. Exactly one, and the last chunk that may be unclaimed.
     *
     * <p>It anchors connectivity: every ordinary claim must trace back to it or to an outpost.</p>
     */
    HOMEBLOCK,

    /** A normal claim. Must touch the town's existing territory. */
    ORDINARY,

    /**
     * A deliberately detached claim, and an anchor in its own right.
     *
     * <p>Outposts exist so a town can hold land it cannot walk to. That is exactly why they must
     * <em>not</em> touch the town when created: an outpost adjacent to the town is an ordinary
     * claim wearing a costume, and it would let a player pay outpost prices for normal expansion.</p>
     */
    OUTPOST,

    /** A claim inside another town's territory, held by this one. Never an anchor. */
    EMBASSY;

    /** Whether other claims may trace their connectivity back to this one. */
    public boolean anchorsConnectivity() {
        return this == HOMEBLOCK || this == OUTPOST;
    }

    /** The stored form. Explicit so a rename is a visible migration rather than silent corruption. */
    public String storageValue() {
        return name();
    }

    public static ClaimKind fromStorage(final String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("claim kind must not be null");
        }
        return switch (raw.toUpperCase(Locale.ROOT)) {
            case "HOMEBLOCK" -> HOMEBLOCK;
            case "ORDINARY" -> ORDINARY;
            case "OUTPOST" -> OUTPOST;
            case "EMBASSY" -> EMBASSY;
            default -> throw new IllegalArgumentException("Unknown claim kind: " + raw);
        };
    }
}
