package net.riftbreaker.rifttowny.api.map;

import net.riftbreaker.rifttowny.api.ChunkKey;
import net.riftbreaker.rifttowny.api.ApiVersion;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Public contract tests, independent of the Minecraft server and domain caches. */
class MapSnapshotTest {
    private static final UUID WORLD = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID TOWN = UUID.fromString("00000000-0000-0000-0000-000000000011");

    @Test void contractRequiresMapApiVersion() {
        assertEquals(new ApiVersion(0, 4), ApiVersion.CURRENT);
        assertTrue(new ApiVersion(0, 3).isSupportedBy(ApiVersion.CURRENT));
        assertFalse(ApiVersion.CURRENT.isSupportedBy(new ApiVersion(0, 3)));
    }

    @Test void worldSnapshotCopiesInputAndRejectsMixedWorlds() {
        final MapClaim claim = new MapClaim(new ChunkKey(WORLD, -17, 2), TOWN);
        final ArrayList<MapClaim> mutable = new ArrayList<>(List.of(claim));
        final TerritoryMapSnapshot snapshot = new TerritoryMapSnapshot(WORLD, 5, mutable);
        mutable.clear();

        assertEquals(List.of(claim), snapshot.claims());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.claims().clear());
        assertThrows(IllegalArgumentException.class,
                () -> new TerritoryMapSnapshot(WORLD, 6,
                        List.of(new MapClaim(new ChunkKey(OTHER, 0, 0), TOWN))));
        assertThrows(IllegalArgumentException.class,
                () -> new TerritoryMapSnapshot(WORLD, -1, List.of()));
    }

    @Test void civicSnapshotCopiesListsAndContainsOnlyPublicMetadata() {
        final MapTown town = new MapTown(TOWN, "Riftholm", Optional.empty(), "#9e9e9e");
        final ArrayList<MapTown> mutable = new ArrayList<>(List.of(town));
        final CivicMapSnapshot snapshot = new CivicMapSnapshot(2, 3, mutable, List.of());
        mutable.clear();

        assertEquals(List.of(town), snapshot.towns());
        assertEquals(2, snapshot.townRevision());
        assertEquals(3, snapshot.nationRevision());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.towns().clear());
        assertThrows(NullPointerException.class, () -> new MapTown(TOWN, "Riftholm", null, "#9e9e9e"));
        assertThrows(IllegalArgumentException.class,
                () -> new MapNation(UUID.randomUUID(), "Empire", TOWN, "<script>"));
    }
}
