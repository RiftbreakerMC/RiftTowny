package net.riftbreaker.rifttowny.domain.directory;

import net.riftbreaker.rifttowny.api.ChunkKey;
import net.riftbreaker.rifttowny.domain.bank.CivicPrices;
import net.riftbreaker.rifttowny.domain.bank.TaxPolicy;
import net.riftbreaker.rifttowny.domain.civic.CivicCache;
import net.riftbreaker.rifttowny.domain.civic.NationCache;
import net.riftbreaker.rifttowny.domain.civic.ResidentNames;
import net.riftbreaker.rifttowny.domain.civic.TownFacts;
import net.riftbreaker.rifttowny.domain.org.MapColour;
import net.riftbreaker.rifttowny.domain.org.Nation;
import net.riftbreaker.rifttowny.domain.org.ResidentId;
import net.riftbreaker.rifttowny.domain.org.Town;
import net.riftbreaker.rifttowny.domain.org.TownId;
import net.riftbreaker.rifttowny.domain.role.Role;
import net.riftbreaker.rifttowny.domain.territory.Claim;
import net.riftbreaker.rifttowny.domain.territory.ClaimKind;
import net.riftbreaker.rifttowny.domain.territory.RuinIndex;
import net.riftbreaker.rifttowny.domain.territory.TerritoryIndex;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Answers the {@code %townyadvanced_*%} manifest, entirely from memory.
 *
 * <p>Bukkit-free on purpose — the architecture test enforces it — so every one of these can be
 * exercised without a server. That matters more here than anywhere else in the codebase: a hundred
 * and forty small string expressions is exactly the shape of code that is never tested and is
 * always slightly wrong.</p>
 *
 * <h2>The three rules</h2>
 *
 * <p><strong>It never blocks.</strong> Everything here is a map lookup against a cache the
 * protection path already maintains. A placeholder is resolved by whatever plugin wants it — a
 * scoreboard on a timer, a chat formatter on an async thread — so a query here would be a query on
 * an unknown thread at an unknown rate.</p>
 *
 * <p><strong>Empty and absent are different.</strong> {@link Optional#empty()} means "not a
 * placeholder of mine", and the adapter turns it into {@code null}, which makes PlaceholderAPI
 * leave the literal {@code %townyadvanced_whatever%} on screen. A present-but-blank string means
 * "mine, and the answer is nothing". Every name in the manifest must therefore return a string —
 * that is what {@code TownyPlaceholderManifestTest} exists to prove, and it is the difference
 * between an unimplemented placeholder rendering as blank and rendering as raw markup.</p>
 *
 * <p><strong>Blank is exactly blank.</strong> Where Towny returns an empty string, so does this —
 * not {@code none}, not {@code null}, not {@code 0}. A zero where a balance should be reads as a
 * real balance of nothing, which is a worse lie than saying nothing at all.</p>
 */
public final class TownyPlaceholders {

    /** The manifest version this was written against. */
    public static final String MANIFEST_VERSION = "towny-papi/2026-08-09";

    private static final String BLANK = "";

    private final CivicDirectory directory;
    private final CivicCache towns;
    private final NationCache nations;
    private final TerritoryIndex claims;
    private final RuinIndex ruins;
    private final LastKnownChunk positions;
    private final ResidentNames names;
    private final CivicPrices prices;
    private final TaxPolicy taxes;
    private final Presence presence;
    private final Truth truth;
    private final Clock clock;

    public TownyPlaceholders(
            final CivicDirectory directory,
            final CivicCache towns,
            final NationCache nations,
            final TerritoryIndex claims,
            final RuinIndex ruins,
            final LastKnownChunk positions,
            final ResidentNames names,
            final CivicPrices prices,
            final TaxPolicy taxes,
            final Presence presence,
            final Truth truth,
            final Clock clock
    ) {
        this.directory = Objects.requireNonNull(directory, "directory");
        this.towns = Objects.requireNonNull(towns, "towns");
        this.nations = Objects.requireNonNull(nations, "nations");
        this.claims = Objects.requireNonNull(claims, "claims");
        this.ruins = Objects.requireNonNull(ruins, "ruins");
        this.positions = Objects.requireNonNull(positions, "positions");
        this.names = Objects.requireNonNull(names, "names");
        this.prices = Objects.requireNonNull(prices, "prices");
        this.taxes = Objects.requireNonNull(taxes, "taxes");
        this.presence = Objects.requireNonNull(presence, "presence");
        this.truth = Objects.requireNonNull(truth, "truth");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Who is on the server. The one fact here that only the platform knows. */
    @FunctionalInterface
    public interface Presence {
        boolean isOnline(ResidentId who);

        static Presence nobody() {
            return who -> false;
        }
    }

    /**
     * The words for yes and no.
     *
     * <p>Configurable because they are configurable in Towny, and a server that has customised them
     * has scoreboards written against the customised words.</p>
     */
    public record Truth(String yes, String no) {

        public static Truth defaults() {
            return new Truth("true", "false");
        }

        String of(final boolean value) {
            return value ? yes : no;
        }
    }

    /**
     * Answers one placeholder.
     *
     * @param player whoever the placeholder is being resolved for, or null for a server-wide
     *        rendering with no subject. A null player still answers the server-wide counts, and
     *        answers everything about "their town" as blank
     * @param key the identifier after {@code %townyadvanced_}, lower-cased by the caller
     * @return empty for a name this does not serve. Never null, and never a null string
     */
    public Optional<String> resolve(final UUID player, final String key) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        final String name = key.toLowerCase(Locale.ROOT);
        final ResidentId who = player == null ? null : ResidentId.of(player);

        final Optional<String> parameterised = parameterised(name);
        if (parameterised.isPresent()) {
            return parameterised;
        }
        return Optional.ofNullable(fixed(who, name));
    }

    // --- the fixed names -------------------------------------------------------------------------

    private String fixed(final ResidentId who, final String name) {
        final Optional<TownFacts> facts = who == null ? Optional.empty() : towns.townFactsOf(who);
        final Optional<Town> town = facts.map(TownFacts::town);
        final Optional<Nation> nation = town.flatMap(Town::nation).flatMap(nations::nation);

        return switch (name) {
            // --- 3.1 prefixes and tags ---------------------------------------------------------
            case "town", "town_unformatted", "town_formatted" ->
                    town.map(found -> found.name().display()).orElse(BLANK);
            case "town_formatted_with_town_minimessage_colour" ->
                    town.map(found -> found.profile().colourOrDefault().miniMessage()
                            + found.name().display()).orElse(BLANK);
            case "town_tag", "town_tag_unformatted" ->
                    town.map(found -> found.profile().tag()).orElse(BLANK);
            // Towny's "override" forms fall back to the full name when no tag is set, which is what
            // makes them usable as the only thing on a scoreboard line.
            case "town_tag_override", "town_tag_override_unformatted" -> town
                    .map(found -> found.profile().hasTag()
                            ? found.profile().tag()
                            : found.name().display())
                    .orElse(BLANK);
            case "nation", "nation_unformatted", "nation_formatted" ->
                    nation.map(found -> found.name().display()).orElse(BLANK);
            case "nation_formatted_with_nation_minimessage_colour" ->
                    nation.map(found -> found.profile().colourOrDefault().miniMessage()
                            + found.name().display()).orElse(BLANK);
            case "nation_tag", "nation_tag_unformatted" ->
                    nation.map(found -> found.profile().tag()).orElse(BLANK);
            case "nation_tag_override", "nation_tag_override_unformatted" -> nation
                    .map(found -> found.profile().hasTag()
                            ? found.profile().tag()
                            : found.name().display())
                    .orElse(BLANK);
            case "nation_tag_town_name", "nation_tag_town_formatted" ->
                    town.map(found -> found.name().display()).orElse(BLANK);
            case "nation_or_town_name" -> nation
                    .map(found -> found.name().display())
                    .or(() -> town.map(found -> found.name().display()))
                    .orElse(BLANK);
            // The "towny" forms are the nation's if there is one and the town's otherwise: one line
            // on a scoreboard that says whichever is the larger allegiance.
            case "towny_tag", "towny_tag_formatted" -> tagOf(nation, town);
            case "towny_tag_override" -> {
                final String tag = tagOf(nation, town);
                yield tag.isEmpty()
                        ? nation.map(found -> found.name().display())
                                .or(() -> town.map(found -> found.name().display()))
                                .orElse(BLANK)
                        : tag;
            }
            case "towny_tag_override_with_minimessage_colour" -> {
                final String tag = tagOf(nation, town);
                yield tag.isEmpty() ? BLANK : colourOf(nation, town) + tag;
            }

            // --- 3.2 resident ------------------------------------------------------------------
            case "town_ranks" -> rolesOf(facts, who);
            case "nation_ranks" -> BLANK;
            case "resident_primary_rank" -> primaryRole(facts, who);
            case "resident_primary_rank_spaced" -> {
                final String role = primaryRole(facts, who);
                yield role.isEmpty() ? BLANK : role + ' ';
            }
            case "has_town" -> truth.of(town.isPresent());
            case "has_nation" -> truth.of(nation.isPresent());
            case "towny_colour" -> colourOf(nation, town);
            case "player_status" -> who == null ? BLANK : truth.of(presence.isOnline(who));
            // Recorded in the schema and not yet carried on the Resident aggregate.
            case "title", "surname", "towny_name_prefix", "towny_name_postfix",
                 "towny_prefix", "towny_postfix" -> BLANK;
            // The justice module owns this. COMPATIBILITY_MATRIX says it returns the "not jailed"
            // value until then, which is exactly what this is - not a blank.
            case "player_jailed" -> truth.of(false);
            case "resident_friends_amount" -> "0";
            case "resident_join_date_unformatted", "resident_join_date_formatted" -> BLANK;

            // --- 3.3 town ----------------------------------------------------------------------
            case "town_residents_amount" ->
                    town.map(found -> String.valueOf(found.residentCount())).orElse(BLANK);
            case "town_residents_online" -> town.map(this::onlineIn).orElse(BLANK);
            case "town_townblocks_used" -> town
                    .map(found -> String.valueOf(
                            directory.town(found.id()).map(TownSummary::chunks).orElse(0)))
                    .orElse(BLANK);
            case "town_outposts_claimed" ->
                    town.map(found -> String.valueOf(countKind(found.id(), ClaimKind.OUTPOST)))
                            .orElse(BLANK);
            case "town_mayor" -> town.map(found -> names.describe(found.mayor())).orElse(BLANK);
            case "town_board" -> town.map(found -> found.profile().board()).orElse(BLANK);
            case "is_town_peaceful" -> town.map(found -> truth.of(found.profile().neutral()))
                    .orElse(BLANK);
            case "is_town_public" -> town.map(found -> truth.of(found.profile().publicSpawn()))
                    .orElse(BLANK);
            case "is_town_open" -> town.map(found -> truth.of(found.profile().open())).orElse(BLANK);
            case "town_map_color_hex" ->
                    town.map(found -> found.profile().colourOrDefault().hashHex()).orElse(BLANK);
            case "town_map_color_minimessage_hex" ->
                    town.map(found -> found.profile().colourOrDefault().miniMessage()).orElse(BLANK);
            // Claim allowances belong to RT-MOD-PROGRESSION, which is unbuilt. Blank rather than a
            // number, because any number here would be one a town could plan against and lose.
            case "town_townblocks_bought", "town_townblocks_bonus",
                 "town_townblocks_maximum", "town_townblocks_natural_maximum" -> BLANK;
            case "town_prefix", "town_postfix", "nation_prefix", "nation_postfix" -> BLANK;

            // --- 3.4 nation --------------------------------------------------------------------
            case "nation_residents_amount" ->
                    nation.map(found -> String.valueOf(residentsIn(found))).orElse(BLANK);
            case "nation_residents_online" -> nation.map(this::onlineIn).orElse(BLANK);
            case "nation_king" -> nation.map(found -> names.describe(found.leader())).orElse(BLANK);
            case "nation_capital" -> nation
                    .flatMap(found -> towns.town(found.capital()))
                    .map(TownFacts::displayName)
                    .orElse(BLANK);
            case "nation_board" -> nation.map(found -> found.profile().board()).orElse(BLANK);
            case "is_nation_peaceful" ->
                    nation.map(found -> truth.of(found.profile().neutral())).orElse(BLANK);
            case "nation_map_color_hex" ->
                    nation.map(found -> found.profile().colourOrDefault().hashHex()).orElse(BLANK);
            case "nation_map_color_minimessage_hex" ->
                    nation.map(found -> found.profile().colourOrDefault().miniMessage()).orElse(BLANK);

            // --- 3.5 new-day timers ------------------------------------------------------------
            case "time_until_new_day_hours_raw" -> untilNewDay(Duration::toHours);
            case "time_until_new_day_minutes_raw" -> untilNewDay(left -> left.toMinutes() % 60L);
            case "time_until_new_day_seconds_raw" -> untilNewDay(left -> left.toSeconds() % 60L);
            case "time_until_new_day_formatted" -> untilNewDayFormatted();
            case "time_until_new_day_hours_formatted" -> untilNewDay(Duration::toHours, "h");
            case "time_until_new_day_minutes_formatted" ->
                    untilNewDay(left -> left.toMinutes() % 60L, "m");
            case "time_until_new_day_seconds_formatted" ->
                    untilNewDay(left -> left.toSeconds() % 60L, "s");

            // --- 3.6 money ---------------------------------------------------------------------
            // Balances are the one civic fact with no snapshot: BankService reads them
            // asynchronously and a placeholder cannot wait. Blank rather than a stale or zero
            // figure, which would read as a real balance.
            case "town_balance", "town_balance_unformatted",
                 "nation_balance", "nation_balance_unformatted" -> BLANK;
            case "daily_town_upkeep", "daily_town_upkeep_unformatted" -> upkeepOf(town);
            case "daily_town_per_plot_upkeep" -> money(taxes.upkeepPerChunk());
            case "daily_nation_per_town_upkeep", "daily_nation_tax" ->
                    money(taxes.nationTaxPerTown());
            case "daily_town_tax", "daily_resident_tax", "daily_resident_tax_unformatted" ->
                    money(taxes.residentTax());
            case "daily_nation_upkeep", "daily_nation_upkeep_unformatted" -> nationUpkeep(nation);
            case "town_creation_cost" -> money(prices.townFounding());
            case "nation_creation_cost" -> BLANK;
            case "townblock_claim_price", "townblock_next_claim_price" -> money(prices.claim());
            case "townblock_unclaim_price" -> money(prices.claimRefund());
            // Deliberately the same as a claim: an outpost costs what land costs, and there is no
            // separate outpost price to report.
            case "outpost_claim_price" -> money(prices.claim());
            case "town_reclaim_cost" -> money(prices.reclaim());
            case "town_reclaim_max_duration_hours", "town_reclaim_min_duration_hours" -> BLANK;
            case "townblock_buy_bonus_price", "town_merge_cost", "town_merge_per_plot_percentage",
                 "daily_town_overclaimed_per_plot_upkeep_penalty",
                 "daily_town_upkeep_reduction_from_town_level",
                 "daily_town_upkeep_reduction_from_nation_level",
                 "daily_nation_upkeep_reduction_from_nation_level" -> BLANK;

            // --- 3.8 location ------------------------------------------------------------------
            case "number_of_towns_in_server", "number_of_towns_in_world" ->
                    String.valueOf(towns.cachedTowns());
            case "number_of_neutral_towns_in_server", "number_of_neutral_towns_in_world" ->
                    String.valueOf(neutralTowns());
            default -> location(who, name);
        };
    }

    // --- location --------------------------------------------------------------------------------

    /**
     * The land the player is standing on, from the record the movement listener keeps.
     *
     * <p>Returns {@code null} — meaning "not one of mine" — for anything not in this group, which is
     * what makes {@link #fixed} exhaustive without a hundred-arm switch having a default that
     * silently swallows typos.</p>
     */
    private String location(final ResidentId who, final String name) {
        if (!name.startsWith("player_location_") && !name.startsWith("player_plot_")
                && !name.equals("player_town_is_trusted") && !name.equals("rel_color")) {
            return null;
        }
        final Optional<ChunkKey> where =
                who == null ? Optional.empty() : positions.of(who.value());
        final Optional<Claim> claim = where.flatMap(claims::at);
        final Optional<TownFacts> here = claim.map(Claim::town).flatMap(towns::town);
        final Optional<Nation> hereNation =
                here.flatMap(facts -> facts.nation()).flatMap(nations::nation);

        return switch (name) {
            case "player_location_town_or_wildname", "player_location_formattedtown_or_wildname" ->
                    here.map(TownFacts::displayName)
                            .or(() -> where.flatMap(ruins::at).map(ruin -> ruin.name().display()))
                            .orElseGet(() -> where.isPresent() ? "Wilderness" : BLANK);
            case "player_location_town_resident_count" ->
                    here.map(facts -> String.valueOf(facts.town().residentCount())).orElse(BLANK);
            case "player_location_town_mayor_name" ->
                    here.map(facts -> names.describe(facts.town().mayor())).orElse(BLANK);
            case "player_location_town_nation_name" ->
                    hereNation.map(found -> found.name().display()).orElse(BLANK);
            case "player_location_town_board" ->
                    here.map(facts -> facts.town().profile().board()).orElse(BLANK);
            case "player_location_nation_board" ->
                    hereNation.map(found -> found.profile().board()).orElse(BLANK);
            case "player_location_town_map_color_hex" -> here
                    .map(facts -> facts.town().profile().colourOrDefault().hashHex()).orElse(BLANK);
            case "player_location_town_map_color_minimessage_hex" -> here
                    .map(facts -> facts.town().profile().colourOrDefault().miniMessage()).orElse(BLANK);
            case "player_location_nation_map_color_hex" -> hereNation
                    .map(found -> found.profile().colourOrDefault().hashHex()).orElse(BLANK);
            case "player_location_nation_map_color_minimessage_hex" -> hereNation
                    .map(found -> found.profile().colourOrDefault().miniMessage()).orElse(BLANK);
            case "player_location_in_homeblock" -> claim
                    .map(found -> truth.of(found.kind() == ClaimKind.HOMEBLOCK)).orElse(BLANK);
            case "player_location_in_homeblock_owntown" -> claim
                    .map(found -> truth.of(found.kind() == ClaimKind.HOMEBLOCK
                            && who != null && towns.townOf(who).filter(found.town()::equals).isPresent()))
                    .orElse(BLANK);
            case "player_location_in_homeblock_ownnation" -> claim
                    .map(found -> truth.of(found.kind() == ClaimKind.HOMEBLOCK && sameNation(who, found)))
                    .orElse(BLANK);
            case "player_location_pvp" -> BLANK;
            case "player_location_plot_name" -> claim.map(found -> found.type().name()
                    .toLowerCase(Locale.ROOT)).orElse(BLANK);
            case "player_location_plot_owner_name", "player_plot_owner" -> claim
                    .flatMap(Claim::holder).map(names::describe).orElse(BLANK);
            case "player_plot_type" ->
                    claim.map(found -> found.type().name().toLowerCase(Locale.ROOT)).orElse(BLANK);
            case "player_plot_is_trusted" -> BLANK;
            case "player_town_is_trusted" -> here
                    .map(facts -> truth.of(who != null && facts.trusts(who))).orElse(BLANK);
            case "player_location_town_prefix", "player_location_town_postfix" -> BLANK;
            // Diplomacy, areas and property are unbuilt; each of these belongs to one of them.
            case "player_location_in_homeblock_enemy", "player_location_in_homeblock_ally",
                 "player_location_district_name", "player_location_plotgroup_name",
                 "player_location_plot_forsale", "player_location_town_forsale_cost" -> BLANK;
            case "rel_color" -> colourOf(hereNation, here.map(TownFacts::town));
            default -> null;
        };
    }

    // --- the parameterised names -----------------------------------------------------------------

    /**
     * The leaderboard names, which end in a number the caller supplies.
     *
     * <p>Sorted from the same directory the listings use, so {@code %townyadvanced_top_town_land_1%}
     * and the first row of {@code /town list land} can never disagree.</p>
     */
    private Optional<String> parameterised(final String name) {
        final int rank = trailingNumber(name);
        if (rank < 1) {
            return Optional.empty();
        }
        final String stem = name.substring(0, name.lastIndexOf('_') + 1);
        return switch (stem) {
            case "top_town_residents_" -> Optional.of(nth(CivicSort.RESIDENTS, rank, false));
            case "top_town_land_" -> Optional.of(nth(CivicSort.LAND, rank, false));
            case "top_town_residents_and_open_" ->
                    Optional.of(nth(CivicSort.RESIDENTS, rank, true));
            // A balance leaderboard would need a balance snapshot, which does not exist. Served as
            // blank rather than omitted, so a scoreboard shows an empty line instead of raw markup.
            case "top_town_balance_" -> Optional.of(BLANK);
            default -> Optional.empty();
        };
    }

    private String nth(final CivicSort sort, final int rank, final boolean openOnly) {
        final List<TownSummary> all = directory.allTowns().stream()
                .filter(summary -> !openOnly || isOpen(summary.id()))
                .sorted(sort.order())
                .toList();
        return rank > all.size() ? BLANK : all.get(rank - 1).name();
    }

    private boolean isOpen(final TownId town) {
        return towns.town(town).map(facts -> facts.town().profile().open()).orElse(false);
    }

    /** The number a leaderboard name ends with, or -1 when it does not end with one. */
    private static int trailingNumber(final String name) {
        final int underscore = name.lastIndexOf('_');
        if (underscore < 0 || underscore == name.length() - 1) {
            return -1;
        }
        try {
            return Integer.parseInt(name.substring(underscore + 1));
        } catch (final NumberFormatException notANumber) {
            return -1;
        }
    }

    // --- helpers ---------------------------------------------------------------------------------

    private String tagOf(final Optional<Nation> nation, final Optional<Town> town) {
        return nation.map(found -> found.profile().tag())
                .filter(tag -> !tag.isEmpty())
                .or(() -> town.map(found -> found.profile().tag()))
                .orElse(BLANK);
    }

    private String colourOf(final Optional<Nation> nation, final Optional<Town> town) {
        return nation.map(found -> found.profile().colourOrDefault())
                .or(() -> town.map(found -> found.profile().colourOrDefault()))
                .map(MapColour::miniMessage)
                .orElse(BLANK);
    }

    private String rolesOf(final Optional<TownFacts> facts, final ResidentId who) {
        if (facts.isEmpty() || who == null) {
            return BLANK;
        }
        return String.join(", ", directory.roleNamesOf(facts.get().id(), who));
    }

    /** The highest-priority role the resident holds, which is what Towny calls the primary rank. */
    private String primaryRole(final Optional<TownFacts> facts, final ResidentId who) {
        if (facts.isEmpty() || who == null) {
            return BLANK;
        }
        final var book = facts.get().roles();
        final var held = book.rolesOf(who);
        return book.ordered().stream()
                .filter(role -> held.contains(role.id()))
                .max(java.util.Comparator.comparingInt(Role::priority))
                .map(Role::name)
                .orElse(BLANK);
    }

    private String onlineIn(final Town town) {
        int online = 0;
        for (final ResidentId resident : town.residents()) {
            if (presence.isOnline(resident)) {
                online++;
            }
        }
        return String.valueOf(online);
    }

    private String onlineIn(final Nation nation) {
        int online = 0;
        for (final TownId member : nation.towns()) {
            final Optional<TownFacts> facts = towns.town(member);
            if (facts.isEmpty()) {
                continue;
            }
            for (final ResidentId resident : facts.get().residents()) {
                if (presence.isOnline(resident)) {
                    online++;
                }
            }
        }
        return String.valueOf(online);
    }

    private int residentsIn(final Nation nation) {
        int total = 0;
        for (final TownId member : nation.towns()) {
            total += towns.town(member).map(facts -> facts.town().residentCount()).orElse(0);
        }
        return total;
    }

    private int countKind(final TownId town, final ClaimKind kind) {
        int count = 0;
        for (final Claim claim : claims.all()) {
            if (claim.town().equals(town) && claim.kind() == kind) {
                count++;
            }
        }
        return count;
    }

    private int neutralTowns() {
        int count = 0;
        for (final TownId id : towns.townIds()) {
            if (towns.town(id).map(facts -> facts.town().profile().neutral()).orElse(false)) {
                count++;
            }
        }
        return count;
    }

    private boolean sameNation(final ResidentId who, final Claim claim) {
        if (who == null) {
            return false;
        }
        final Optional<net.riftbreaker.rifttowny.domain.org.NationId> mine =
                towns.nationOfResident(who);
        return mine.isPresent() && towns.nationOf(claim.town()).filter(mine.get()::equals).isPresent();
    }

    private String upkeepOf(final Optional<Town> town) {
        if (town.isEmpty() || !taxes.collectsAnything()) {
            return BLANK;
        }
        final int chunks = directory.town(town.get().id()).map(TownSummary::chunks).orElse(0);
        return money(taxes.upkeepPerChunk().multiply(BigDecimal.valueOf(chunks)));
    }

    private String nationUpkeep(final Optional<Nation> nation) {
        if (nation.isEmpty() || !taxes.collectsAnything()) {
            return BLANK;
        }
        return money(taxes.nationTaxPerTown()
                .multiply(BigDecimal.valueOf(nation.get().townCount())));
    }

    /** A configured amount, trimmed of trailing zeroes so {@code 5.0000} reads as {@code 5}. */
    private static String money(final BigDecimal amount) {
        if (amount == null) {
            return BLANK;
        }
        return amount.stripTrailingZeros().toPlainString();
    }

    /**
     * How long until the next tax run.
     *
     * <p>Blank when taxes are off, which is the documented behaviour rather than a misleading zero:
     * a countdown to nothing is worse than no countdown.</p>
     */
    private String untilNewDay(final java.util.function.ToLongFunction<Duration> part) {
        return remaining().map(left -> String.valueOf(part.applyAsLong(left))).orElse(BLANK);
    }

    private String untilNewDay(
            final java.util.function.ToLongFunction<Duration> part, final String suffix) {
        return remaining().map(left -> part.applyAsLong(left) + suffix).orElse(BLANK);
    }

    private String untilNewDayFormatted() {
        return remaining()
                .map(left -> String.format(Locale.ROOT, "%02d:%02d:%02d",
                        left.toHours(), left.toMinutes() % 60L, left.toSeconds() % 60L))
                .orElse(BLANK);
    }

    private Optional<Duration> remaining() {
        if (!taxes.collectsAnything()) {
            return Optional.empty();
        }
        final Instant now = clock.instant();
        final Duration interval = taxes.interval();
        final long millis = interval.toMillis();
        if (millis <= 0L) {
            return Optional.empty();
        }
        // The next boundary is this period's start plus one interval. Floored the same way
        // TaxPolicy.periodKey floors it, so the countdown ends exactly when a run becomes due.
        final long sinceEpoch = now.toEpochMilli();
        final long intoPeriod = Math.floorMod(sinceEpoch, millis);
        return Optional.of(Duration.ofMillis(millis - intoPeriod));
    }
}
