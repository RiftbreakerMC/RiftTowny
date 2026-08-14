package net.riftbreaker.rifttowny.domain.directory;

import net.riftbreaker.rifttowny.api.ChunkKey;
import net.riftbreaker.rifttowny.domain.bank.CivicPrices;
import net.riftbreaker.rifttowny.domain.bank.TaxPolicy;
import net.riftbreaker.rifttowny.domain.civic.CivicCache;
import net.riftbreaker.rifttowny.domain.civic.CivicFixture;
import net.riftbreaker.rifttowny.domain.civic.NationCache;
import net.riftbreaker.rifttowny.domain.civic.ResidentNames;
import net.riftbreaker.rifttowny.domain.naming.NamePolicy;
import net.riftbreaker.rifttowny.domain.org.MapColour;
import net.riftbreaker.rifttowny.domain.org.Nation;
import net.riftbreaker.rifttowny.domain.org.NationId;
import net.riftbreaker.rifttowny.domain.org.NationProfile;
import net.riftbreaker.rifttowny.domain.org.ResidentId;
import net.riftbreaker.rifttowny.domain.org.Town;
import net.riftbreaker.rifttowny.domain.org.TownId;
import net.riftbreaker.rifttowny.domain.org.TownProfile;
import net.riftbreaker.rifttowny.domain.territory.Claim;
import net.riftbreaker.rifttowny.domain.territory.ClaimKind;
import net.riftbreaker.rifttowny.domain.territory.RuinIndex;
import net.riftbreaker.rifttowny.domain.territory.TerritoryIndex;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The placeholder manifest, and the guarantee that matters.
 *
 * <p><strong>Every name in the manifest must return a string.</strong> Returning null tells
 * PlaceholderAPI "not mine", and it leaves the literal {@code %townyadvanced_whatever%} on the
 * player's screen. So an unimplemented placeholder either renders as a blank — fine, and what
 * Towny does — or as raw markup in the middle of a scoreboard, which is a bug report. There is no
 * third option, and this test is what stands between the two.</p>
 */
class TownyPlaceholderManifestTest {

    private static final UUID WORLD = UUID.randomUUID();
    private static final Clock CLOCK = Clock.fixed(CivicFixture.NOW, ZoneOffset.UTC);

    private final CivicCache towns = CivicCache.empty();
    private final NationCache nations = NationCache.empty();
    private final TerritoryIndex claims = TerritoryIndex.empty();
    private final RuinIndex ruins = RuinIndex.empty();
    private final LastKnownChunk positions = LastKnownChunk.empty();
    private final ResidentNames names = ResidentNames.empty();

    private ResidentId mayor;
    private TownyPlaceholders placeholders;

    /** Every name in the shipped manifest, {@code <n>} entries included. */
    static List<String> manifest() throws IOException {
        final List<String> entries = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                TownyPlaceholders.class.getResourceAsStream("/placeholders/townyadvanced.txt"),
                StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                final String trimmed = line.trim();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                    entries.add(trimmed);
                }
            }
        }
        return List.copyOf(entries);
    }

    /** Parameterised names are tested at several ranks, including one past the end. */
    private static List<String> expand(final String entry) {
        if (!entry.endsWith("<n>")) {
            return List.of(entry);
        }
        final String stem = entry.substring(0, entry.length() - 3);
        return List.of(stem + '1', stem + '2', stem + "99");
    }

    @BeforeEach
    void buildAWorld() {
        mayor = CivicFixture.resident();
        final ResidentId citizen = CivicFixture.resident();
        names.remember(mayor, "Bede");
        names.remember(citizen, "Ada");

        final NationId valenId = NationId.random();
        final Town ashford = Town.found(TownId.random(), CivicFixture.name("Ashford"), mayor,
                        UUID.randomUUID(), CivicFixture.NOW)
                .admit(citizen).orElseThrow()
                .joinNation(valenId).orElseThrow()
                .withProfile(TownProfile.empty()
                        .withBoard("Welcome to Ashford")
                        .withTag("ASH")
                        .withColour(MapColour.parse("#112233").orElseThrow())
                        .withOpen(true)
                        .withNeutral(true)
                        .withPublicSpawn(true))
                .orElseThrow();
        towns.remember(CivicFixture.facts(ashford));

        final Nation valen = Nation.restore(valenId, CivicFixture.name("Valen"), mayor,
                ashford.id(), UUID.randomUUID(), java.util.Set.of(ashford.id()),
                NationProfile.empty().withBoard("For Valen").withTag("VAL")
                        .withColour(MapColour.parse("#445566").orElseThrow()).withNeutral(true),
                CivicFixture.NOW);
        nations.remember(valen);

        claims.put(Claim.of(new ChunkKey(WORLD, 0, 0), ashford.id(),
                ClaimKind.HOMEBLOCK, CivicFixture.NOW));
        claims.put(Claim.of(new ChunkKey(WORLD, 5, 5), ashford.id(),
                ClaimKind.OUTPOST, CivicFixture.NOW));
        positions.record(mayor.value(), new ChunkKey(WORLD, 0, 0));

        placeholders = placeholdersWith(
                new CivicPrices(BigDecimal.valueOf(100), BigDecimal.TEN, BigDecimal.valueOf(5),
                        BigDecimal.valueOf(25), BigDecimal.valueOf(50), BigDecimal.ONE),
                new TaxPolicy(true, Duration.ofHours(24), BigDecimal.ONE, BigDecimal.valueOf(2),
                        BigDecimal.valueOf(3), Duration.ofHours(72)));
    }

    private TownyPlaceholders placeholdersWith(final CivicPrices prices, final TaxPolicy taxes) {
        return new TownyPlaceholders(
                new CivicDirectory(towns, claims, nations),
                towns, nations, claims, ruins, positions, names, prices, taxes,
                who -> who.equals(mayor),
                TownyPlaceholders.Truth.defaults(),
                CLOCK);
    }

    @Nested
    @DisplayName("coverage")
    class Coverage {

        @Test
        @DisplayName("every manifest placeholder answers for a player in a town and a nation")
        void everyPlaceholderAnswersForAResident() throws IOException {
            final List<String> unanswered = new ArrayList<>();

            for (final String entry : manifest()) {
                for (final String name : expand(entry)) {
                    final Optional<String> answer = placeholders.resolve(mayor.value(), name);
                    if (answer.isEmpty() || answer.get() == null) {
                        unanswered.add(name);
                    }
                }
            }

            assertThat(unanswered)
                    .as("a manifest placeholder returning null reaches the player as %%townyadvanced_...%%")
                    .isEmpty();
        }

        @Test
        @DisplayName("every manifest placeholder answers for a player in no town at all")
        void everyPlaceholderAnswersForANobody() throws IOException {
            // The commonest case on any server, and the one where a naive orElseThrow lurks.
            final UUID wanderer = UUID.randomUUID();
            final List<String> unanswered = new ArrayList<>();

            for (final String entry : manifest()) {
                for (final String name : expand(entry)) {
                    final Optional<String> answer = placeholders.resolve(wanderer, name);
                    if (answer.isEmpty() || answer.get() == null) {
                        unanswered.add(name);
                    }
                }
            }

            assertThat(unanswered).isEmpty();
        }

        @Test
        @DisplayName("every manifest placeholder answers with no player at all")
        void everyPlaceholderAnswersForTheConsole() throws IOException {
            // A scoreboard is per-player, but a Discord relay or a web panel renders with nobody.
            final List<String> unanswered = new ArrayList<>();

            for (final String entry : manifest()) {
                for (final String name : expand(entry)) {
                    final Optional<String> answer = placeholders.resolve(null, name);
                    if (answer.isEmpty() || answer.get() == null) {
                        unanswered.add(name);
                    }
                }
            }

            assertThat(unanswered).isEmpty();
        }

        @Test
        @DisplayName("a name outside the manifest is disowned rather than answered blank")
        void unknownNamesAreDisowned() {
            // The other half of the contract: claiming a placeholder we do not serve would render
            // somebody else's expansion as an empty string.
            assertThat(placeholders.resolve(mayor.value(), "not_a_towny_placeholder")).isEmpty();
            assertThat(placeholders.resolve(mayor.value(), "")).isEmpty();
            assertThat(placeholders.resolve(mayor.value(), null)).isEmpty();
        }

        @Test
        @DisplayName("the manifest is the size the compatibility matrix says it is")
        void manifestIsComplete() throws IOException {
            // 150 in the matrix counts the location group as 34; it lists 33. The count is asserted
            // so a name deleted by accident shows up as a failure rather than as quiet regression.
            assertThat(manifest()).hasSize(143);
        }
    }

    @Nested
    @DisplayName("what it actually says")
    class Values {

        @Test
        @DisplayName("names, tags and boards come out as set")
        void identityIsReported() {
            assertThat(answer("town")).isEqualTo("Ashford");
            assertThat(answer("nation")).isEqualTo("Valen");
            assertThat(answer("town_tag")).isEqualTo("ASH");
            assertThat(answer("nation_tag")).isEqualTo("VAL");
            assertThat(answer("town_board")).isEqualTo("Welcome to Ashford");
            assertThat(answer("nation_board")).isEqualTo("For Valen");
            assertThat(answer("town_mayor")).isEqualTo("Bede");
            assertThat(answer("nation_king")).isEqualTo("Bede");
            assertThat(answer("nation_capital")).isEqualTo("Ashford");
        }

        @Test
        @DisplayName("the towny_ forms prefer the nation, and fall back to the town")
        void townyFormsPreferTheNation() {
            assertThat(answer("towny_tag")).isEqualTo("VAL");
            assertThat(answer("nation_or_town_name")).isEqualTo("Valen");
        }

        @Test
        @DisplayName("an override form falls back to the full name when no tag is set")
        void overridesFallBackToTheName() {
            final Town untagged = Town.found(TownId.random(), CivicFixture.name("Highholm"),
                    CivicFixture.resident(), UUID.randomUUID(), CivicFixture.NOW);
            towns.remember(CivicFixture.facts(untagged));

            assertThat(placeholders.resolve(untagged.mayor().value(), "town_tag_override"))
                    .contains("Highholm");
            assertThat(placeholders.resolve(untagged.mayor().value(), "town_tag")).contains("");
        }

        @Test
        @DisplayName("counts are counted, not guessed")
        void countsAreReal() {
            assertThat(answer("town_residents_amount")).isEqualTo("2");
            assertThat(answer("town_residents_online")).isEqualTo("1");
            assertThat(answer("town_townblocks_used")).isEqualTo("2");
            assertThat(answer("town_outposts_claimed")).isEqualTo("1");
            assertThat(answer("nation_residents_amount")).isEqualTo("2");
            assertThat(answer("number_of_towns_in_server")).isEqualTo("1");
            assertThat(answer("number_of_neutral_towns_in_server")).isEqualTo("1");
        }

        @Test
        @DisplayName("booleans use the configured words")
        void booleansAreConfigurable() {
            assertThat(answer("has_town")).isEqualTo("true");
            assertThat(answer("is_town_open")).isEqualTo("true");
            assertThat(answer("is_town_public")).isEqualTo("true");
            assertThat(answer("is_town_peaceful")).isEqualTo("true");
            // A server that renamed them has scoreboards written against the new words.
            final TownyPlaceholders yesNo = new TownyPlaceholders(
                    new CivicDirectory(towns, claims, nations), towns, nations, claims, ruins,
                    positions, names, CivicPrices.free(), TaxPolicy.off(),
                    who -> false, new TownyPlaceholders.Truth("yes", "no"), CLOCK);
            assertThat(yesNo.resolve(mayor.value(), "has_town")).contains("yes");
            assertThat(yesNo.resolve(mayor.value(), "has_nation")).contains("yes");
        }

        @Test
        @DisplayName("a jailed check answers 'not jailed' rather than blank")
        void jailAnswersNotJailed() {
            // COMPATIBILITY_MATRIX says so explicitly: the justice module is unbuilt, and until it
            // lands the honest answer is that nobody is in jail - not that the question is unknown.
            assertThat(answer("player_jailed")).isEqualTo("false");
        }

        @Test
        @DisplayName("colours render in both the forms Towny offers")
        void coloursRenderBothWays() {
            assertThat(answer("town_map_color_hex")).isEqualTo("#112233");
            assertThat(answer("town_map_color_minimessage_hex")).isEqualTo("<#112233>");
            assertThat(answer("nation_map_color_hex")).isEqualTo("#445566");
        }

        @Test
        @DisplayName("location reads the last known chunk, never a live lookup")
        void locationComesFromTheRecord() {
            assertThat(answer("player_location_town_or_wildname")).isEqualTo("Ashford");
            assertThat(answer("player_location_town_mayor_name")).isEqualTo("Bede");
            assertThat(answer("player_location_town_nation_name")).isEqualTo("Valen");
            assertThat(answer("player_location_in_homeblock")).isEqualTo("true");
            assertThat(answer("player_location_town_board")).isEqualTo("Welcome to Ashford");
        }

        @Test
        @DisplayName("somebody whose position was never recorded gets blank, not wilderness")
        void unknownPositionIsBlankNotWilderness() {
            // "Not standing anywhere" and "standing in the wild" are different facts, and reading
            // one as the other puts an offline player's last position on a live scoreboard.
            final UUID offline = UUID.randomUUID();

            assertThat(placeholders.resolve(offline, "player_location_town_or_wildname"))
                    .contains("");
        }

        @Test
        @DisplayName("standing in the wild says so")
        void wildernessSaysWilderness() {
            positions.record(mayor.value(), new ChunkKey(WORLD, 900, 900));

            assertThat(answer("player_location_town_or_wildname")).isEqualTo("Wilderness");
        }

        @Test
        @DisplayName("the leaderboard agrees with the listing it is drawn from")
        void leaderboardMatchesTheListing() {
            towns.remember(CivicFixture.facts(Town.found(TownId.random(),
                    CivicFixture.name("Highholm"), CivicFixture.resident(),
                    UUID.randomUUID(), CivicFixture.NOW)));

            // Ashford has two residents, Highholm one.
            assertThat(answer("top_town_residents_1")).isEqualTo("Ashford");
            assertThat(answer("top_town_residents_2")).isEqualTo("Highholm");
            // Past the end is blank rather than an error or a wrapped-around first entry.
            assertThat(answer("top_town_residents_99")).isEmpty();
        }

        @Test
        @DisplayName("the open-only leaderboard leaves closed towns out")
        void openLeaderboardFilters() {
            towns.remember(CivicFixture.facts(Town.found(TownId.random(),
                    CivicFixture.name("Highholm"), CivicFixture.resident(),
                    UUID.randomUUID(), CivicFixture.NOW)));

            assertThat(answer("top_town_residents_and_open_1")).isEqualTo("Ashford");
            assertThat(answer("top_town_residents_and_open_2")).isEmpty();
        }

        @Test
        @DisplayName("prices and tax rates come from the configuration, trimmed")
        void pricesAreReported() {
            assertThat(answer("town_creation_cost")).isEqualTo("100");
            assertThat(answer("townblock_claim_price")).isEqualTo("10");
            assertThat(answer("daily_resident_tax")).isEqualTo("1");
            assertThat(answer("daily_town_per_plot_upkeep")).isEqualTo("2");
            // Two chunks at 2 each.
            assertThat(answer("daily_town_upkeep")).isEqualTo("4");
        }

        @Test
        @DisplayName("a balance is blank rather than zero, because zero reads as a real balance")
        void balancesAreBlank() {
            assertThat(answer("town_balance")).isEmpty();
            assertThat(answer("nation_balance")).isEmpty();
            assertThat(answer("top_town_balance_1")).isEmpty();
        }

        @Test
        @DisplayName("the new-day countdown is blank when taxes are off")
        void countdownIsBlankWhenTaxesAreOff() {
            // A countdown to nothing is worse than no countdown.
            final TownyPlaceholders untaxed = placeholdersWith(
                    CivicPrices.free(), TaxPolicy.off());

            assertThat(untaxed.resolve(mayor.value(), "time_until_new_day_formatted")).contains("");
            assertThat(untaxed.resolve(mayor.value(), "time_until_new_day_hours_raw")).contains("");
        }

        @Test
        @DisplayName("the countdown runs when taxes are on")
        void countdownRunsWhenTaxesAreOn() {
            assertThat(answer("time_until_new_day_formatted")).matches("\\d{2}:\\d{2}:\\d{2}");
            assertThat(answer("time_until_new_day_hours_formatted")).endsWith("h");
        }

        private String answer(final String name) {
            return placeholders.resolve(mayor.value(), name).orElseThrow();
        }
    }
}
