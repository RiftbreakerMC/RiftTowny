package net.riftbreaker.rifttowny.paper;

import net.riftbreaker.rifttowny.api.ClaimGuard;
import net.riftbreaker.rifttowny.api.ChunkKey;
import net.riftbreaker.rifttowny.api.ApiVersion;
import net.riftbreaker.rifttowny.api.RiftTownyApi;
import net.riftbreaker.rifttowny.api.TownInfo;
import net.riftbreaker.rifttowny.api.capability.CapabilityRegistry;
import net.riftbreaker.rifttowny.api.scheduler.RiftScheduler;
import net.riftbreaker.rifttowny.api.map.RiftTownyMapApi;
import net.riftbreaker.rifttowny.domain.civic.CivicCache;
import net.riftbreaker.rifttowny.domain.org.ResidentId;
import net.riftbreaker.rifttowny.domain.territory.TerritoryIndex;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.Objects;

/** Read-only sibling-plugin API with a small, fast extension point for claim vetoes. */
final class RiftTownyApiImpl implements RiftTownyApi {

    private final CapabilityRegistry capabilities;
    private final RiftScheduler scheduler;
    private final CivicCache civicCache;
    private final TerritoryIndex territoryIndex;
    private final RiftTownyMapApi mapApi;
    private final List<ClaimGuard> claimGuards = new CopyOnWriteArrayList<>();

    RiftTownyApiImpl(
            final CapabilityRegistry capabilities,
            final RiftScheduler scheduler,
            final CivicCache civicCache,
            final TerritoryIndex territoryIndex,
            final RiftTownyMapApi mapApi
    ) {
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.civicCache = Objects.requireNonNull(civicCache, "civicCache");
        this.territoryIndex = Objects.requireNonNull(territoryIndex, "territoryIndex");
        this.mapApi = Objects.requireNonNull(mapApi, "mapApi");
    }

    @Override
    public ApiVersion apiVersion() {
        return ApiVersion.CURRENT;
    }

    @Override
    public CapabilityRegistry capabilities() {
        return capabilities;
    }

    @Override
    public RiftScheduler scheduler() {
        return scheduler;
    }

    /**
     * Public map snapshots are a separate, read-only capability.
     *
     * <p>The same instance also serves RiftEvents' fast claim guards, but
     * no map call invokes, blocks or changes claim protection decisions.</p>
     */
    @Override
    public RiftTownyMapApi mapData() {
        return mapApi;
    }

    @Override
    public boolean isWilderness(final ChunkKey chunk) {
        return territoryIndex.isWilderness(chunk);
    }

    @Override
    public Optional<TownInfo> townOf(final UUID residentId) {
        if (residentId == null) {
            return Optional.empty();
        }
        return civicCache.townOf(ResidentId.of(residentId))
                .flatMap(civicCache::town)
                .map(facts -> new TownInfo(facts.id().value(), facts.displayName()));
    }

    @Override
    public AutoCloseable registerClaimGuard(final ClaimGuard guard) {
        final ClaimGuard registered = Objects.requireNonNull(guard, "guard");
        claimGuards.add(registered);
        return () -> claimGuards.remove(registered);
    }

    @Override
    public Optional<String> checkClaimGuards(final UUID residentId, final ChunkKey chunk) {
        for (final ClaimGuard guard : claimGuards) {
            final Optional<String> denial = guard.denyReason(residentId, chunk);
            if (denial != null && denial.isPresent()) {
                return denial;
            }
        }
        return Optional.empty();
    }
}
