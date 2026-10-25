package net.riftbreaker.rifttowny.domain.service;

import net.riftbreaker.rifttowny.api.ChunkKey;
import net.riftbreaker.rifttowny.domain.civic.CivicCache;
import net.riftbreaker.rifttowny.domain.civic.CivicFixture;
import net.riftbreaker.rifttowny.domain.civic.NationCache;
import net.riftbreaker.rifttowny.domain.org.Nation;
import net.riftbreaker.rifttowny.domain.org.NationId;
import net.riftbreaker.rifttowny.domain.org.ResidentId;
import net.riftbreaker.rifttowny.domain.org.Town;
import net.riftbreaker.rifttowny.domain.org.TownId;
import net.riftbreaker.rifttowny.domain.territory.Claim;
import net.riftbreaker.rifttowny.domain.territory.ClaimKind;
import net.riftbreaker.rifttowny.domain.territory.TerritoryIndex;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the actual cache-backed map API rather than mocking the API object.
 * No real database, Paper server or BlueMap installation is required.
 */
class ReadOnlyMapServiceTest {
    private static final UUID WORLD = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID NETHER = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test void claimSnapshotsAreWorldScopedImmutableAndVersioned() {
        final TerritoryIndex index = TerritoryIndex.empty();
        final TownId owner = TownId.random();
        final ReadOnlyMapService maps = new ReadOnlyMapService(
                index, CivicCache.empty(), NationCache.empty());

        assertEquals(0, maps.claims(WORLD).claims().size());
        index.put(Claim.of(new ChunkKey(WORLD, -1, 3), owner,
                ClaimKind.ORDINARY, CivicFixture.NOW));
        index.put(Claim.of(new ChunkKey(NETHER, -1, 3), owner,
                ClaimKind.ORDINARY, CivicFixture.NOW));

        final var oldSnapshot = maps.claims(WORLD);
        assertEquals(1, oldSnapshot.claims().size());
        assertEquals(owner.value(), oldSnapshot.claims().getFirst().townId());
        assertEquals(new ChunkKey(WORLD, -1, 3), oldSnapshot.claims().getFirst().chunk());

        index.remove(new ChunkKey(WORLD, -1, 3));
        final var newSnapshot = maps.claims(WORLD);
        assertEquals(1, oldSnapshot.claims().size()); // old published data stays immutable
        assertTrue(newSnapshot.claims().isEmpty());
        assertTrue(newSnapshot.revision() > oldSnapshot.revision());
    }

    @Test void townAndNationMetadataUsesStableIdsNotPrivateRecords() {
        final TerritoryIndex index = TerritoryIndex.empty();
        final CivicCache towns = CivicCache.empty();
        final NationCache nations = NationCache.empty();
        final ResidentId mayor = CivicFixture.resident();
        final Town original = CivicFixture.town("Riftholm", mayor);
        final Nation nation = Nation.found(NationId.random(), CivicFixture.name("RiftEmpire"),
                mayor, original.id(), UUID.randomUUID(), CivicFixture.NOW);
        final Town joined = original.joinNation(nation.id()).orElseThrow();

        towns.remember(CivicFixture.facts(joined));
        nations.remember(CivicFixture.facts(nation));

        final ReadOnlyMapService mapApi = new ReadOnlyMapService(index, towns, nations);
        final var snapshot = mapApi.civics();

        assertEquals(1, snapshot.towns().size());
        assertEquals(1, snapshot.nations().size());
        assertEquals(original.id().value(), snapshot.towns().getFirst().id());
        assertEquals("Riftholm", snapshot.towns().getFirst().name());
        assertEquals(nation.id().value(), snapshot.towns().getFirst().nationId().orElseThrow());
        assertEquals(original.id().value(), snapshot.nations().getFirst().capitalTownId());
        assertEquals("RiftEmpire", snapshot.nations().getFirst().name());

        towns.forget(joined.id());
        assertEquals(1, snapshot.towns().size()); // snapshot cannot mutate after capture
        assertTrue(mapApi.civics().towns().isEmpty());
        assertTrue(mapApi.civics().townRevision() > snapshot.townRevision());
    }

    @Test void aClaimTransferIncrementsGenerationWithoutDatabaseAccess() {
        final TerritoryIndex index = TerritoryIndex.empty();
        final TownId first = TownId.random();
        final TownId second = TownId.random();
        index.put(Claim.of(new ChunkKey(WORLD, 0, 0), first,
                ClaimKind.ORDINARY, CivicFixture.NOW));
        index.put(Claim.of(new ChunkKey(WORLD, 1, 0), first,
                ClaimKind.ORDINARY, CivicFixture.NOW));
        final ReadOnlyMapService mapApi = new ReadOnlyMapService(
                index, CivicCache.empty(), NationCache.empty());
        final var before = mapApi.claims(WORLD);
        assertEquals(2, index.reassignAllOf(first, second));
        final var after = mapApi.claims(WORLD);

        assertTrue(after.revision() > before.revision());
        assertEquals(2, after.claims().size());
        assertTrue(after.claims().stream().allMatch(c -> c.townId().equals(second.value())));
        assertTrue(before.claims().stream().allMatch(c -> c.townId().equals(first.value())));
    }

    @Test void emptySnapshotCanBeUsedWithoutAnyServerWorldLoaded() {
        final ReadOnlyMapService mapApi = new ReadOnlyMapService(
                TerritoryIndex.empty(), CivicCache.empty(), NationCache.empty());
        assertEquals(List.of(), mapApi.claims(WORLD).claims());
        assertEquals(List.of(), mapApi.civics().towns());
        assertEquals(List.of(), mapApi.civics().nations());
    }
}
