package net.riftbreaker.rifttowny.domain.service;

import net.riftbreaker.rifttowny.api.map.CivicMapSnapshot;
import net.riftbreaker.rifttowny.api.map.MapClaim;
import net.riftbreaker.rifttowny.api.map.MapNation;
import net.riftbreaker.rifttowny.api.map.MapTown;
import net.riftbreaker.rifttowny.api.map.RiftTownyMapApi;
import net.riftbreaker.rifttowny.api.map.TerritoryMapSnapshot;
import net.riftbreaker.rifttowny.domain.civic.CivicCache;
import net.riftbreaker.rifttowny.domain.civic.NationCache;
import net.riftbreaker.rifttowny.domain.civic.NationFacts;
import net.riftbreaker.rifttowny.domain.civic.TownFacts;
import net.riftbreaker.rifttowny.domain.territory.TerritoryIndex;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Public map facade backed exclusively by existing in-memory RiftTowny caches.
 *
 * <p>RiftTowny remains the sole authority for changes. The facade only
 * copies the values that a territory map genuinely needs; it does not
 * return domain aggregates, database connections, residents or permissions.</p>
 *
 * <p>Snapshots are O(total claims/towns/nations) and may briefly hold cache
 * writer locks while copying. They are not intended for synchronous HTTP
 * handling or Minecraft ticks. Consumers should cache results by revision,
 * use bounded asynchronous refreshes and discard stale rendering results.</p>
 */
public final class ReadOnlyMapService implements RiftTownyMapApi {
    private final TerritoryIndex claims;
    private final CivicCache towns;
    private final NationCache nations;

    public ReadOnlyMapService(
            final TerritoryIndex claims,
            final CivicCache towns,
            final NationCache nations
    ) {
        this.claims = Objects.requireNonNull(claims, "claims");
        this.towns = Objects.requireNonNull(towns, "towns");
        this.nations = Objects.requireNonNull(nations, "nations");
    }

    /**
     * Captures the world in one index generation. Adjacent claim edits never
     * create a partially-copied bulk transfer because all index writers take
     * the same monitor as the copying operation.
     */
    @Override
    public TerritoryMapSnapshot claims(final UUID worldId) {
        final var snapshot = claims.snapshotForMap(worldId);
        final List<MapClaim> published = snapshot.claims().stream()
                .map(claim -> new MapClaim(claim.chunk(), claim.town().value()))
                .toList();
        return new TerritoryMapSnapshot(worldId, snapshot.revision(), published);
    }

    /**
     * Creates independent consistent copies of the town and nation caches.
     *
     * <p>These caches can refresh at slightly different times after a
     * transaction. Both revision numbers are included so a browser can
     * resynchronize without ever treating missing display metadata as a
     * wilderness/protection determination.</p>
     */
    @Override
    public CivicMapSnapshot civics() {
        final var townSnapshot = towns.snapshotForMap();
        final var nationSnapshot = nations.snapshotForMap();

        final List<MapTown> townData = townSnapshot.towns().stream()
                .sorted(Comparator.comparing(f -> f.id().value().toString()))
                .map(this::toTown)
                .toList();
        final List<MapNation> nationData = nationSnapshot.nations().stream()
                .sorted(Comparator.comparing(f -> f.id().value().toString()))
                .map(this::toNation)
                .toList();

        return new CivicMapSnapshot(townSnapshot.revision(), nationSnapshot.revision(),
                townData, nationData);
    }

    private MapTown toTown(final TownFacts facts) {
        return new MapTown(facts.id().value(), facts.displayName(),
                facts.nation().map(id -> id.value()));
    }

    private MapNation toNation(final NationFacts facts) {
        final var nation = facts.nation();
        return new MapNation(nation.id().value(), nation.name().display(),
                nation.capital().value());
    }
}
