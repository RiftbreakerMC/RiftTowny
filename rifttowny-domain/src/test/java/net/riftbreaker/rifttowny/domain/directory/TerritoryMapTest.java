package net.riftbreaker.rifttowny.domain.directory;

import net.riftbreaker.rifttowny.api.ChunkKey;
import net.riftbreaker.rifttowny.domain.civic.CivicCache;
import net.riftbreaker.rifttowny.domain.civic.CivicFixture;
import net.riftbreaker.rifttowny.domain.naming.NamePolicy;
import net.riftbreaker.rifttowny.domain.org.NationId;
import net.riftbreaker.rifttowny.domain.org.ResidentId;
import net.riftbreaker.rifttowny.domain.org.Town;
import net.riftbreaker.rifttowny.domain.org.TownId;
import net.riftbreaker.rifttowny.domain.territory.Claim;
import net.riftbreaker.rifttowny.domain.territory.ClaimKind;
import net.riftbreaker.rifttowny.domain.territory.Ruin;
import net.riftbreaker.rifttowny.domain.territory.RuinIndex;
import net.riftbreaker.rifttowny.domain.territory.TerritoryIndex;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The chunk map.
 *
 * <p>Two things are worth testing here and neither is obvious from reading the code. The
 * <em>geometry</em>: north has to be up and east has to be right, or every player who uses the map
 * to navigate walks the wrong way. And the <em>viewer</em>: the same chunk is a different colour to
 * its owner, their ally and a stranger, and getting that backwards tells somebody they own land
 * they do not.</p>
 */
class TerritoryMapTest {

    private static final UUID WORLD = UUID.randomUUID();

    private TerritoryIndex claims;
    private RuinIndex ruins;
    private CivicCache towns;
    private TerritoryMap map;

    @BeforeEach
    void setUp() {
        claims = TerritoryIndex.empty();
        ruins = RuinIndex.empty();
        towns = CivicCache.empty();
        map = new TerritoryMap(claims, ruins, towns);
    }

    private static ChunkKey chunk(final int x, final int z) {
        return new ChunkKey(WORLD, x, z);
    }

    private Town town(final String name, final ResidentId mayor) {
        final Town town = Town.found(TownId.random(),
                NamePolicy.defaults().check(name).accepted().orElseThrow(),
                mayor, UUID.randomUUID(), CivicFixture.NOW);
        towns.remember(CivicFixture.facts(town));
        return town;
    }

    private void claim(final Town town, final ChunkKey where, final ClaimKind kind) {
        claims.put(Claim.of(where, town.id(), kind, CivicFixture.NOW));
    }

    @Test
    @DisplayName("the view is the size it was asked for, centred on the viewer")
    void viewIsCentredAndSized() {
        final TerritoryMap.MapView view = map.around(chunk(10, 10), null, 3, 2);

        assertThat(view.width()).isEqualTo(7);
        assertThat(view.height()).isEqualTo(5);
        assertThat(view.rows()).hasSize(5);
        assertThat(view.rows().getFirst()).hasSize(7);
        assertThat(view.here().chunk()).isEqualTo(chunk(10, 10));
        assertThat(view.here().isCentre()).isTrue();
    }

    @Test
    @DisplayName("north is up and east is right")
    void orientationMatchesTheGame() {
        // The whole reason the map is readable: -z is north in Minecraft, so the smallest z has to
        // be the top row, and +x is east, so the largest x has to be the rightmost column.
        final TerritoryMap.MapView view = map.around(chunk(0, 0), null, 1, 1);

        assertThat(view.rows().getFirst().getFirst().chunk()).isEqualTo(chunk(-1, -1));
        assertThat(view.rows().getFirst().getLast().chunk()).isEqualTo(chunk(1, -1));
        assertThat(view.rows().getLast().getFirst().chunk()).isEqualTo(chunk(-1, 1));
        assertThat(view.rows().getLast().getLast().chunk()).isEqualTo(chunk(1, 1));
    }

    @Test
    @DisplayName("exactly one square is marked as where you are standing")
    void onlyTheCentreIsMarked() {
        final TerritoryMap.MapView view = map.around(chunk(4, 4), null, 2, 2);

        final long marked = view.rows().stream().flatMap(List::stream)
                .filter(MapCell::isCentre).count();

        assertThat(marked).isEqualTo(1);
    }

    @Test
    @DisplayName("unclaimed ground reads as wilderness")
    void emptyGroundIsWilderness() {
        final TerritoryMap.MapView view = map.around(chunk(0, 0), null, 1, 1);

        assertThat(view.here().standing()).isEqualTo(MapCell.MapStanding.WILDERNESS);
        assertThat(view.here().isClaimed()).isFalse();
        assertThat(view.here().label()).isEmpty();
        assertThat(view.claimedSquares()).isZero();
    }

    @Test
    @DisplayName("your own town reads differently from somebody else's")
    void ownTownIsDistinctFromForeign() {
        final ResidentId me = CivicFixture.resident();
        final Town mine = town("Ashford", me);
        final Town theirs = town("Highholm", CivicFixture.resident());
        claim(mine, chunk(0, 0), ClaimKind.HOMEBLOCK);
        claim(theirs, chunk(1, 0), ClaimKind.ORDINARY);

        final TerritoryMap.MapView view = map.around(chunk(0, 0), me, 1, 0);

        assertThat(view.here().standing()).isEqualTo(MapCell.MapStanding.OWN_TOWN);
        assertThat(view.here().label()).isEqualTo("Ashford");
        assertThat(view.rows().getFirst().getLast().standing())
                .isEqualTo(MapCell.MapStanding.FOREIGN);
        assertThat(view.rows().getFirst().getLast().label()).isEqualTo("Highholm");
    }

    @Test
    @DisplayName("a town in your nation is neither yours nor a stranger's")
    void nationTownsAreTheirOwnStanding() {
        final ResidentId me = CivicFixture.resident();
        final NationId valen = NationId.random();
        final Town mine = Town.found(TownId.random(),
                NamePolicy.defaults().check("Ashford").accepted().orElseThrow(),
                me, UUID.randomUUID(), CivicFixture.NOW);
        final Town ally = Town.found(TownId.random(),
                NamePolicy.defaults().check("Highholm").accepted().orElseThrow(),
                CivicFixture.resident(), UUID.randomUUID(), CivicFixture.NOW);
        towns.remember(CivicFixture.facts(mine.joinNation(valen).orElseThrow()));
        towns.remember(CivicFixture.facts(ally.joinNation(valen).orElseThrow()));
        claim(mine, chunk(0, 0), ClaimKind.HOMEBLOCK);
        claim(ally, chunk(1, 0), ClaimKind.HOMEBLOCK);

        final TerritoryMap.MapView view = map.around(chunk(0, 0), me, 1, 0);

        assertThat(view.rows().getFirst().getLast().standing())
                .isEqualTo(MapCell.MapStanding.NATION);
    }

    @Test
    @DisplayName("a plot you hold outranks the town it is in")
    void ownPlotBeatsOwnTown() {
        // Ordered narrowest first, or a plot you hold would be swallowed by "your own town" and the
        // map would lose the distinction it exists to draw.
        final ResidentId me = CivicFixture.resident();
        final Town mine = town("Ashford", me);
        claims.put(Claim.of(chunk(0, 0), mine.id(), ClaimKind.ORDINARY, CivicFixture.NOW)
                .heldBy(me));

        assertThat(map.around(chunk(0, 0), me, 0, 0).here().standing())
                .isEqualTo(MapCell.MapStanding.OWN_PLOT);
    }

    @Test
    @DisplayName("somebody else's plot is still just their town to you")
    void otherPeoplesPlotsAreNotYours() {
        final ResidentId me = CivicFixture.resident();
        final Town mine = town("Ashford", me);
        claims.put(Claim.of(chunk(0, 0), mine.id(), ClaimKind.ORDINARY, CivicFixture.NOW)
                .heldBy(CivicFixture.resident()));

        assertThat(map.around(chunk(0, 0), me, 0, 0).here().standing())
                .isEqualTo(MapCell.MapStanding.OWN_TOWN);
    }

    @Test
    @DisplayName("the claim kind travels with the square, so a homeblock can be drawn as one")
    void claimKindIsCarried() {
        final ResidentId me = CivicFixture.resident();
        final Town mine = town("Ashford", me);
        claim(mine, chunk(0, 0), ClaimKind.HOMEBLOCK);
        claim(mine, chunk(1, 0), ClaimKind.OUTPOST);

        final TerritoryMap.MapView view = map.around(chunk(0, 0), me, 1, 0);

        assertThat(view.here().claimKind()).contains(ClaimKind.HOMEBLOCK);
        assertThat(view.rows().getFirst().getLast().claimKind()).contains(ClaimKind.OUTPOST);
    }

    @Test
    @DisplayName("a ruin is drawn as rubble, named after the town that fell")
    void ruinsAreDrawnAsRuins() {
        ruins.put(ruin("Ashford"), List.of(chunk(0, 0)));

        final MapCell here = map.around(chunk(0, 0), null, 0, 0).here();

        assertThat(here.standing()).isEqualTo(MapCell.MapStanding.RUIN);
        assertThat(here.label()).isEqualTo("Ashford");
        assertThat(here.isClaimed()).isTrue();
        assertThat(here.owner()).isEmpty();
    }

    @Test
    @DisplayName("a reclaimed ruin's land is drawn as the town that took it on")
    void claimsWinOverRuins() {
        // Asking the ruin index first would draw somebody's living town as rubble for as long as the
        // old ruin record survived.
        final ResidentId me = CivicFixture.resident();
        final Town mine = town("Highholm", me);
        ruins.put(ruin("Ashford"), List.of(chunk(0, 0)));
        claim(mine, chunk(0, 0), ClaimKind.HOMEBLOCK);

        final MapCell here = map.around(chunk(0, 0), me, 0, 0).here();

        assertThat(here.standing()).isEqualTo(MapCell.MapStanding.OWN_TOWN);
        assertThat(here.label()).isEqualTo("Highholm");
    }

    @Test
    @DisplayName("the console sees claimed land as somebody else's rather than as its own")
    void aViewerWithNoIdentitySeesEverythingAsForeign() {
        final Town theirs = town("Ashford", CivicFixture.resident());
        claim(theirs, chunk(0, 0), ClaimKind.HOMEBLOCK);

        assertThat(map.around(chunk(0, 0), null, 0, 0).here().standing())
                .isEqualTo(MapCell.MapStanding.FOREIGN);
    }

    @Test
    @DisplayName("an absurd radius is clamped rather than drawing a map nobody can read")
    void radiusIsClamped() {
        final TerritoryMap.MapView view = map.around(chunk(0, 0), null, 5_000, 5_000);

        assertThat(view.radiusX()).isEqualTo(TerritoryMap.MAXIMUM_RADIUS);
        assertThat(view.radiusZ()).isEqualTo(TerritoryMap.MAXIMUM_RADIUS);
    }

    @Test
    @DisplayName("claimed squares are counted for the summary line")
    void claimedSquaresAreCounted() {
        final ResidentId me = CivicFixture.resident();
        final Town mine = town("Ashford", me);
        claim(mine, chunk(0, 0), ClaimKind.HOMEBLOCK);
        claim(mine, chunk(1, 0), ClaimKind.ORDINARY);
        ruins.put(ruin("Highholm"), List.of(chunk(-1, 0)));

        assertThat(map.around(chunk(0, 0), me, 1, 0).claimedSquares()).isEqualTo(3);
    }

    private static Ruin ruin(final String name) {
        return new Ruin(UUID.randomUUID(), TownId.random(),
                NamePolicy.defaults().check(name).accepted().orElseThrow(),
                null, chunk(0, 0), CivicFixture.NOW,
                CivicFixture.NOW, CivicFixture.NOW.plusSeconds(86_400), null, null, null);
    }

    @Test
    @DisplayName("the map never reads across worlds")
    void otherWorldsAreNotDrawn() {
        // Every square in a view shares the centre's world, so a chunk with the same coordinates in
        // the nether can never appear on the overworld map.
        final Set<UUID> worlds = map.around(chunk(0, 0), null, 2, 2).rows().stream()
                .flatMap(List::stream)
                .map(cell -> cell.chunk().worldId())
                .collect(java.util.stream.Collectors.toSet());

        assertThat(worlds).containsExactly(WORLD);
    }
}
