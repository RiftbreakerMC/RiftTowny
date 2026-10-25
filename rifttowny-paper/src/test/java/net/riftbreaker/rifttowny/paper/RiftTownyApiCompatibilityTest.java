package net.riftbreaker.rifttowny.paper;

import net.riftbreaker.rifttowny.api.ApiVersion;
import net.riftbreaker.rifttowny.api.ChunkKey;
import net.riftbreaker.rifttowny.api.capability.CapabilityRegistry;
import net.riftbreaker.rifttowny.api.scheduler.RiftScheduler;
import net.riftbreaker.rifttowny.domain.civic.CivicCache;
import net.riftbreaker.rifttowny.domain.civic.NationCache;
import net.riftbreaker.rifttowny.domain.org.TownId;
import net.riftbreaker.rifttowny.domain.service.ReadOnlyMapService;
import net.riftbreaker.rifttowny.domain.territory.Claim;
import net.riftbreaker.rifttowny.domain.territory.ClaimKind;
import net.riftbreaker.rifttowny.domain.territory.TerritoryIndex;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration regression tests for the overlapping RiftEvents (0.3) and
 * RiftMap (0.4) public API surface.
 *
 * <p>The scheduler/registry are intentionally never called: these tests
 * verify that a single published API instance supports both features,
 * without starting Bukkit, Folia, MariaDB or the Minecraft server.</p>
 */
class RiftTownyApiCompatibilityTest {

    private static <T> T unusedDependency(final Class<T> type) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(),
                new Class<?>[]{type},
                (proxy, method, args) -> {
                    throw new AssertionError("Unexpected dependency access: " + method.getName());
                }));
    }

    private static RiftTownyApiImpl api(final TerritoryIndex index) {
        final CivicCache towns = CivicCache.empty();
        final NationCache nations = NationCache.empty();
        return new RiftTownyApiImpl(
                unusedDependency(CapabilityRegistry.class),
                unusedDependency(RiftScheduler.class),
                towns,
                index,
                new ReadOnlyMapService(index, towns, nations));
    }

    @Test
    void oldGuardsAndNewMapSnapshotsBothWork() throws Exception {
        final UUID world = UUID.randomUUID();
        final UUID resident = UUID.randomUUID();
        final ChunkKey key = new ChunkKey(world, -3, 17);
        final TerritoryIndex territory = TerritoryIndex.empty();
        final RiftTownyApiImpl api = api(territory);

        api.requireApiVersion(new ApiVersion(0, 3));
        api.requireApiVersion(new ApiVersion(0, 4));

        assertTrue(api.isWilderness(key));
        assertTrue(api.townOf(resident).isEmpty());

        final AutoCloseable registered =
                api.registerClaimGuard((who, chunk) -> who.equals(resident) && chunk.equals(key)
                        ? Optional.of("Blocked by RiftEvents")
                        : Optional.empty());
        assertEquals(Optional.of("Blocked by RiftEvents"), api.checkClaimGuards(resident, key));

        final long before = api.mapData().revisions().territory();
        final TownId owner = TownId.random();
        territory.put(Claim.of(key, owner, ClaimKind.ORDINARY, Instant.now()));

        assertFalse(api.isWilderness(key));
        assertEquals(owner.value(), api.mapData().claims(world).claims().getFirst().townId());
        assertTrue(api.mapData().revisions().territory() > before);
        // Map reads must not invoke or unregister claim guards.
        assertEquals(Optional.of("Blocked by RiftEvents"), api.checkClaimGuards(resident, key));

        registered.close();
        assertEquals(Optional.empty(), api.checkClaimGuards(resident, key));
        assertFalse(api.isWilderness(key));
    }

    @Test
    void nullRegistrationsAreRejectedWithoutMutatingGuards() {
        final RiftTownyApiImpl api = api(TerritoryIndex.empty());
        assertThrows(NullPointerException.class, () -> api.registerClaimGuard(null));
        assertEquals(Optional.empty(),
                api.checkClaimGuards(UUID.randomUUID(),
                        new ChunkKey(UUID.randomUUID(), 0, 0)));
    }
}
