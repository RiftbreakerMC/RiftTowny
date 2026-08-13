package net.riftbreaker.rifttowny.paper.protection;

import net.riftbreaker.rifttowny.api.ChunkKey;
import net.riftbreaker.rifttowny.domain.civic.CivicCache;
import net.riftbreaker.rifttowny.domain.civic.TownFacts;
import net.riftbreaker.rifttowny.domain.naming.NamePolicy;
import net.riftbreaker.rifttowny.domain.org.OrganisationScope;
import net.riftbreaker.rifttowny.domain.org.ResidentId;
import net.riftbreaker.rifttowny.domain.org.Town;
import net.riftbreaker.rifttowny.domain.org.TownId;
import net.riftbreaker.rifttowny.domain.role.RoleBook;
import net.riftbreaker.rifttowny.domain.territory.Claim;
import net.riftbreaker.rifttowny.domain.territory.ClaimKind;
import net.riftbreaker.rifttowny.domain.territory.Ruin;
import net.riftbreaker.rifttowny.domain.territory.RuinIndex;
import net.riftbreaker.rifttowny.domain.territory.TerritoryIndex;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * When a border crossing is worth mentioning.
 *
 * <p>The listener itself needs a server; this is the decision inside it, which is the part that
 * would be wrong in a way nobody notices until players complain about spam.
 */
class TerritoryNoticeTest {

    private static final Instant NOW = Instant.parse("2026-08-13T09:00:00Z");
    private static final UUID WORLD = UUID.randomUUID();
    private static final ChunkKey HOME = new ChunkKey(WORLD, 0, 0);
    private static final ChunkKey MARKET = new ChunkKey(WORLD, 1, 0);
    private static final ChunkKey OUTSIDE = new ChunkKey(WORLD, 40, 40);
    private static final ChunkKey RUINED = new ChunkKey(WORLD, 2, 0);

    private static final ResidentId MAYOR = resident();
    private static final ResidentId CITIZEN = resident();

    private final TerritoryIndex territory = TerritoryIndex.empty();
    private final RuinIndex ruins = RuinIndex.empty();
    private final CivicCache civic = CivicCache.empty();
    private final TerritoryNotice notice = new TerritoryNotice(territory, ruins, civic);

    private static ResidentId resident() {
        return ResidentId.of(UUID.randomUUID());
    }

    private static Town town(final String name, final ResidentId mayor, final ResidentId... extra) {
        Town town = Town.found(
                TownId.random(),
                NamePolicy.defaults().check(name).accepted().orElseThrow(),
                mayor,
                UUID.randomUUID(),
                NOW);
        for (final ResidentId who : extra) {
            town = town.admit(who).orElseThrow();
        }
        return town;
    }

    private void remember(final Town town) {
        civic.remember(TownFacts.of(town,
                RoleBook.defaultsFor(OrganisationScope.TOWN, town.id().value(), NOW)));
    }

    /** Riftholm holding two chunks, one of them a plot the citizen holds. */
    private Town riftholm() {
        final Town town = town("Riftholm", MAYOR, CITIZEN);
        remember(town);
        territory.put(Claim.of(HOME, town.id(), ClaimKind.HOMEBLOCK, NOW));
        territory.put(Claim.of(MARKET, town.id(), ClaimKind.ORDINARY, NOW).heldBy(CITIZEN));
        return town;
    }

    private void ruinAt(final ChunkKey chunk) {
        final Town fallen = town("Ashford", resident());
        ruins.put(
                Ruin.of(fallen.id(), fallen.name(), null, chunk, NOW,
                        Duration.ofDays(1), Duration.ofDays(3)),
                List.of(chunk));
    }

    @Nested
    @DisplayName("naming the ground")
    class Naming {

        @Test
        @DisplayName("a claimed chunk is its town")
        void towns() {
            final Town town = riftholm();

            final var here = notice.at(HOME, NOW);

            assertThat(here.kind()).isEqualTo(TerritoryNotice.Kind.TOWN);
            assertThat(here.displayName()).contains("Riftholm");
            assertThat(here.id()).isEqualTo(town.id().value().toString());
        }

        @Test
        @DisplayName("a held chunk names its holder")
        void plots() {
            riftholm();

            assertThat(notice.at(MARKET, NOW).holder()).isEqualTo(CITIZEN);
            assertThat(notice.at(HOME, NOW).holder()).isNull();
        }

        @Test
        @DisplayName("unclaimed ground is wilderness")
        void wilderness() {
            riftholm();

            assertThat(notice.at(OUTSIDE, NOW).kind())
                    .isEqualTo(TerritoryNotice.Kind.WILDERNESS);
        }

        @Test
        @DisplayName("a ruin is named, with what is left of it")
        void ruin() {
            ruinAt(RUINED);

            final var here = notice.at(RUINED, NOW);

            assertThat(here.kind()).isEqualTo(TerritoryNotice.Kind.RUIN);
            assertThat(here.displayName()).contains("Ashford");
            assertThat(here.remaining()).isEqualTo(Duration.ofDays(3));
        }

        @Test
        @DisplayName("a town the cache cannot describe has no name to announce")
        void unknownTown() {
            final Town town = town("Riftholm", MAYOR);
            territory.put(Claim.of(HOME, town.id(), ClaimKind.HOMEBLOCK, NOW));

            assertThat(notice.at(HOME, NOW).displayName())
                    .as("the protection check already refuses on this; a second message adds nothing")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("deciding whether to say anything")
    class Deciding {

        @Test
        @DisplayName("walking within one town says nothing")
        void withinATown() {
            riftholm();
            final var first = notice.at(HOME, NOW);

            assertThat(TerritoryNotice.worthAnnouncing(first, notice.at(HOME, NOW)))
                    .as("a town is many chunks and crossing its middle is not arriving anywhere")
                    .isFalse();
        }

        @Test
        @DisplayName("crossing a border is announced in both directions")
        void crossingABorder() {
            riftholm();
            final var inside = notice.at(HOME, NOW);
            final var outside = notice.at(OUTSIDE, NOW);

            assertThat(TerritoryNotice.worthAnnouncing(outside, inside)).isTrue();
            assertThat(TerritoryNotice.worthAnnouncing(inside, outside)).isTrue();
        }

        @Test
        @DisplayName("stepping onto somebody's plot inside the same town is announced")
        void changingPlot() {
            riftholm();

            assertThat(TerritoryNotice.worthAnnouncing(
                    notice.at(HOME, NOW), notice.at(MARKET, NOW)))
                    .as("it changes what you may do there, and being refused is a worse way to learn")
                    .isTrue();
            assertThat(TerritoryNotice.worthAnnouncing(
                    notice.at(MARKET, NOW), notice.at(HOME, NOW)))
                    .isTrue();
        }

        @Test
        @DisplayName("two towns with the same name are still two places")
        void sameNameDifferentTown() {
            final Town first = town("Riftholm", MAYOR);
            final Town second = town("Riftholm", resident());
            remember(first);
            remember(second);
            territory.put(Claim.of(HOME, first.id(), ClaimKind.HOMEBLOCK, NOW));
            territory.put(Claim.of(MARKET, second.id(), ClaimKind.HOMEBLOCK, NOW));

            assertThat(TerritoryNotice.worthAnnouncing(
                    notice.at(HOME, NOW), notice.at(MARKET, NOW)))
                    .as("compared by identity, not by the name shown")
                    .isTrue();
        }

        @Test
        @DisplayName("arriving for the first time is announced unless it is wilderness")
        void firstPosition() {
            riftholm();

            assertThat(TerritoryNotice.worthAnnouncing(null, notice.at(HOME, NOW))).isTrue();
            assertThat(TerritoryNotice.worthAnnouncing(null, notice.at(OUTSIDE, NOW)))
                    .as("wilderness is the default state of the world, not news")
                    .isFalse();
        }

        @Test
        @DisplayName("a ruin and the town it replaced are different ground")
        void ruinIsNotATown() {
            riftholm();
            ruinAt(RUINED);

            assertThat(TerritoryNotice.worthAnnouncing(
                    notice.at(HOME, NOW), notice.at(RUINED, NOW))).isTrue();
        }
    }

    @Test
    @DisplayName("a remaining duration reads in units a player can act on")
    void describingRemaining() {
        assertThat(TerritoryNoticeListener.describe(Duration.ofHours(70))).isEqualTo("70h");
        assertThat(TerritoryNoticeListener.describe(Duration.ofMinutes(45))).isEqualTo("45m");
        assertThat(TerritoryNoticeListener.describe(Duration.ofSeconds(20)))
                .isEqualTo("less than a minute");
        assertThat(TerritoryNoticeListener.describe(null)).isEmpty();
    }
}
