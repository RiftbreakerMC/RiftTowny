package net.riftbreaker.rifttowny.paper.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.riftbreaker.rifttowny.api.ChunkKey;
import net.riftbreaker.rifttowny.domain.civic.CivicCache;
import net.riftbreaker.rifttowny.domain.directory.TerritoryMap;
import net.riftbreaker.rifttowny.domain.org.ResidentId;
import net.riftbreaker.rifttowny.domain.org.TownId;
import net.riftbreaker.rifttowny.domain.territory.Claim;
import net.riftbreaker.rifttowny.domain.territory.ClaimKind;
import net.riftbreaker.rifttowny.domain.territory.RuinIndex;
import net.riftbreaker.rifttowny.domain.territory.TerritoryIndex;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How the map reaches chat.
 *
 * <p>The geometry is tested in the domain; what is left here is the rendering contract, and the one
 * thing that would break it silently: a square that is not exactly two characters wide. One
 * odd-width glyph shifts every square to its right by one and the grid stops lining up, which reads
 * as a corrupted map rather than as a formatting bug.</p>
 */
class MapRendererTest {

    private static final UUID WORLD = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-08-12T09:00:00Z");

    private static ChunkKey chunk(final int x, final int z) {
        return new ChunkKey(WORLD, x, z);
    }

    @Test
    @DisplayName("every row is exactly two characters per square")
    void everyRowIsTheSameWidth() {
        final TerritoryIndex claims = TerritoryIndex.empty();
        final TownId town = TownId.random();
        claims.put(Claim.of(chunk(0, 0), town, ClaimKind.HOMEBLOCK, NOW));
        claims.put(Claim.of(chunk(1, 0), town, ClaimKind.OUTPOST, NOW));
        claims.put(Claim.of(chunk(-1, 0), town, ClaimKind.ORDINARY, NOW)
                .heldBy(ResidentId.of(UUID.randomUUID())));

        final TerritoryMap map =
                new TerritoryMap(claims, RuinIndex.empty(), CivicCache.empty());
        final TerritoryMap.MapView view = map.around(chunk(0, 0), null, 3, 2);

        final List<Component> rendered = MapRenderer.render(view);

        assertThat(rendered).hasSize(view.height());
        for (final Component row : rendered) {
            assertThat(PlainTextComponentSerializer.plainText().serialize(row))
                    .hasSize(view.width() * 2);
        }
    }

    @Test
    @DisplayName("wilderness renders as wilderness rather than as an empty square")
    void wildernessIsDrawn() {
        final TerritoryMap map = new TerritoryMap(
                TerritoryIndex.empty(), RuinIndex.empty(), CivicCache.empty());

        final String only = PlainTextComponentSerializer.plainText()
                .serialize(MapRenderer.render(map.around(chunk(0, 0), null, 0, 0)).getFirst());

        assertThat(only).isEqualTo("--");
    }

    @Test
    @DisplayName("a homeblock and an outpost do not draw the same")
    void shapeSaysWhatTheClaimIs() {
        final TerritoryIndex claims = TerritoryIndex.empty();
        final TownId town = TownId.random();
        claims.put(Claim.of(chunk(0, 0), town, ClaimKind.HOMEBLOCK, NOW));
        claims.put(Claim.of(chunk(1, 0), town, ClaimKind.OUTPOST, NOW));
        claims.put(Claim.of(chunk(2, 0), town, ClaimKind.ORDINARY, NOW));

        final TerritoryMap map =
                new TerritoryMap(claims, RuinIndex.empty(), CivicCache.empty());
        final String row = PlainTextComponentSerializer.plainText().serialize(
                MapRenderer.render(map.around(chunk(1, 0), null, 1, 0)).getFirst());

        assertThat(row).isEqualTo("{}()[]");
    }
}
