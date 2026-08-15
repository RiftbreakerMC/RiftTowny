package net.riftbreaker.rifttowny.storage.migration;

import net.riftbreaker.rifttowny.domain.migration.MigrationPlan;
import net.riftbreaker.rifttowny.domain.migration.MigrationSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Reads a Towny installation's own MySQL database.
 *
 * <p>Offline, necessarily. RiftTowny disables itself when Towny is present, so the two are never
 * running together and there is no live Towny to ask — this connects to the database Towny left
 * behind, while it is stopped.</p>
 *
 * <h2>Read defensively, on purpose</h2>
 *
 * <p>Every column is fetched with {@code SELECT *} and read <em>by name against the result set's
 * own metadata</em>, never by hard-coded position and never by asserting a column exists. Towny's
 * schema grows: {@code SQLSchema} adds columns as versions land, and a server may be on any of
 * them. A reader that named its columns in the {@code SELECT} would fail wholesale on the one
 * version missing one of them — and the failure would arrive during somebody's migration, which is
 * the worst possible moment to discover a version difference.</p>
 *
 * <p>So a missing column reads as absent rather than throwing, and the import reports what it could
 * not find instead of refusing to run.</p>
 *
 * <h2>Two mappings that are not obvious</h2>
 *
 * <p><strong>A nation has no leader column.</strong> Towny stores {@code capital} and derives the
 * king from that town's mayor. Guessing at a {@code king} column would have produced a reader that
 * compiled, ran, and imported every nation leaderless.</p>
 *
 * <p><strong>A town's homeblock is on the town, not the townblock.</strong> Towny records it as a
 * {@code world,x,z} string on the town row; the townblock rows carry no "this is the homeblock"
 * flag. So the homeblock is matched back onto the chunk it names.</p>
 */
public final class TownySqlSource implements MigrationSource {

    /** Towny's own default, from its {@code database.sql.table_prefix} setting. */
    public static final String DEFAULT_PREFIX = "towny_";

    private final String jdbcUrl;
    private final String username;
    private final String password;
    private final String prefix;
    private final List<String> notes = new ArrayList<>();

    public TownySqlSource(
            final String jdbcUrl,
            final String username,
            final String password,
            final String prefix
    ) {
        this.jdbcUrl = Objects.requireNonNull(jdbcUrl, "jdbcUrl");
        this.username = username == null ? "" : username;
        this.password = password == null ? "" : password;
        this.prefix = prefix == null || prefix.isBlank() ? DEFAULT_PREFIX : prefix;
    }

    @Override
    public List<String> notes() {
        return List.copyOf(notes);
    }

    @Override
    public MigrationPlan read() throws MigrationException {
        notes.clear();
        try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password)) {
            final Map<String, Resident> residents = readResidents(connection);
            final Map<String, TownRow> towns = readTowns(connection, residents);
            final List<MigrationPlan.Nation> nations = readNations(connection, towns);
            final List<MigrationPlan.Claim> claims = readClaims(connection, towns, residents);

            return new MigrationPlan(
                    "Towny at " + jdbcUrl,
                    resolveResidentTowns(residents, towns),
                    towns.values().stream().map(TownRow::plan).toList(),
                    nations,
                    claims);
        } catch (final SQLException failure) {
            throw new MigrationException(
                    "Could not read the Towny database at " + jdbcUrl + ": " + failure.getMessage(),
                    failure);
        }
    }

    // --- residents --------------------------------------------------------------------------------

    /**
     * Every player, keyed by the lower-cased name Towny joins on.
     *
     * <p>Towny's primary key for a resident is their <em>name</em>, and every other table refers to
     * them by it — so the name is the join key here even though the UUID is what gets imported. A
     * resident with no UUID is dropped: RiftTowny keys people by account, and inventing one would
     * hand somebody else's town to whoever next took that name.</p>
     */
    private Map<String, Resident> readResidents(final Connection connection) throws SQLException {
        final Map<String, Resident> residents = new LinkedHashMap<>();
        int withoutUuid = 0;
        int npcs = 0;

        try (PreparedStatement statement = connection.prepareStatement(select("RESIDENTS"));
             ResultSet results = statement.executeQuery()) {
            final Columns columns = Columns.of(results);
            while (results.next()) {
                final String name = columns.string(results, "name");
                if (name == null || name.isBlank()) {
                    continue;
                }
                // NPCs are Towny's own bookkeeping accounts, not players. Importing them would
                // create residents nobody can log in as.
                if (columns.bool(results, "isNPC", false)) {
                    npcs++;
                    continue;
                }
                final UUID id = columns.uuid(results, "uuid");
                if (id == null) {
                    withoutUuid++;
                    continue;
                }
                residents.put(key(name), new Resident(new MigrationPlan.Resident(
                        id,
                        name,
                        columns.string(results, "town"),
                        columns.instant(results, "registered"),
                        columns.instant(results, "lastOnline"))));
            }
        }

        if (withoutUuid > 0) {
            notes.add(withoutUuid + " resident(s) had no UUID recorded and were left out; "
                    + "a player cannot be matched by name alone.");
        }
        if (npcs > 0) {
            notes.add(npcs + " Towny NPC account(s) were left out.");
        }
        return residents;
    }

    /**
     * Turns each resident's {@code town} value into a town name.
     *
     * <p>It is a town <strong>UUID</strong> on modern Towny, not a name — Towny's own loader parses
     * it as one and falls back to a name for older rows. The importer joins residents to towns by
     * name, so leaving the UUID would match nothing: every town would arrive holding only its
     * mayor and every other resident would be dropped, reported as an adjustment rather than as
     * the data loss it is.</p>
     */
    private List<MigrationPlan.Resident> resolveResidentTowns(
            final Map<String, Resident> residents, final Map<String, TownRow> towns) {
        // Name and UUID both point at the same town. Kept separate from the towns map itself,
        // which must stay one entry per town or the plan would carry each town twice.
        final Map<String, String> namesByReference = new HashMap<>();
        for (final TownRow town : towns.values()) {
            namesByReference.put(key(town.plan().name()), town.plan().name());
            if (town.uuid() != null) {
                namesByReference.put(key(town.uuid().toString()), town.plan().name());
            }
        }

        final List<MigrationPlan.Resident> resolved = new ArrayList<>(residents.size());
        for (final Resident resident : residents.values()) {
            final MigrationPlan.Resident plan = resident.plan();
            resolved.add(new MigrationPlan.Resident(
                    plan.id(), plan.name(),
                    namesByReference.getOrDefault(key(plan.townName()), plan.townName()),
                    plan.joined(), plan.lastSeen()));
        }
        return List.copyOf(resolved);
    }

    // --- towns ------------------------------------------------------------------------------------

    private Map<String, TownRow> readTowns(
            final Connection connection, final Map<String, Resident> residents) throws SQLException {
        final Map<String, TownRow> towns = new LinkedHashMap<>();
        int ruined = 0;
        int mayorless = 0;

        try (PreparedStatement statement = connection.prepareStatement(select("TOWNS"));
             ResultSet results = statement.executeQuery()) {
            final Columns columns = Columns.of(results);
            while (results.next()) {
                final String name = columns.string(results, "name");
                if (name == null || name.isBlank()) {
                    continue;
                }
                // A ruined Towny town has already fallen and is waiting to be deleted. Importing it
                // as a living town would resurrect towns whose members had already lost them.
                if (columns.bool(results, "ruined", false)) {
                    ruined++;
                    continue;
                }
                final Resident mayor = residents.get(key(columns.string(results, "mayor")));
                if (mayor == null) {
                    mayorless++;
                    continue;
                }

                towns.put(key(name), new TownRow(
                        new MigrationPlan.Town(
                                name,
                                mayor.plan().id(),
                                mayor.plan().name(),
                                columns.string(results, "nation"),
                                columns.string(results, "townBoard"),
                                columns.string(results, "tag"),
                                columns.bool(results, "open", false),
                                columns.bool(results, "public", false),
                                columns.bool(results, "neutral", false),
                                columns.instant(results, "registered")),
                        Homeblock.parse(columns.string(results, "homeblock")),
                        columns.uuid(results, "uuid")));
            }
        }

        if (ruined > 0) {
            notes.add(ruined + " town(s) were already ruined in Towny and were left out.");
        }
        if (mayorless > 0) {
            notes.add(mayorless + " town(s) had a mayor who is not an importable resident.");
        }
        return towns;
    }

    // --- nations ----------------------------------------------------------------------------------

    /**
     * Every nation, taking its leader from its capital's mayor.
     *
     * <p>Towny has no leader column on a nation — the king <em>is</em> the capital town's mayor —
     * so a nation whose capital was not imported has nobody to lead it and is dropped here rather
     * than arriving leaderless.</p>
     */
    private List<MigrationPlan.Nation> readNations(
            final Connection connection, final Map<String, TownRow> towns) throws SQLException {
        final List<MigrationPlan.Nation> nations = new ArrayList<>();
        int capitalless = 0;

        try (PreparedStatement statement = connection.prepareStatement(select("NATIONS"));
             ResultSet results = statement.executeQuery()) {
            final Columns columns = Columns.of(results);
            while (results.next()) {
                final String name = columns.string(results, "name");
                if (name == null || name.isBlank()) {
                    continue;
                }
                final String capitalName = columns.string(results, "capital");
                final TownRow capital = towns.get(key(capitalName));
                if (capital == null) {
                    capitalless++;
                    continue;
                }
                nations.add(new MigrationPlan.Nation(
                        name,
                        capital.plan().mayorId(),
                        capital.plan().name(),
                        columns.string(results, "nationBoard"),
                        columns.string(results, "tag"),
                        columns.bool(results, "neutral", false),
                        columns.instant(results, "registered")));
            }
        }

        if (capitalless > 0) {
            notes.add(capitalless + " nation(s) had no importable capital town; Towny takes a "
                    + "nation's leader from its capital's mayor, so they have nobody to lead them.");
        }
        return nations;
    }

    // --- claims -----------------------------------------------------------------------------------

    private List<MigrationPlan.Claim> readClaims(
            final Connection connection,
            final Map<String, TownRow> towns,
            final Map<String, Resident> residents
    ) throws SQLException {
        final Map<String, UUID> worldsByName = readWorlds(connection);
        final List<MigrationPlan.Claim> claims = new ArrayList<>();
        final Set<String> homeblocksFound = new HashSet<>();
        int unknownWorlds = 0;

        try (PreparedStatement statement = connection.prepareStatement(select("TOWNBLOCKS"));
             ResultSet results = statement.executeQuery()) {
            final Columns columns = Columns.of(results);
            while (results.next()) {
                final String townName = columns.string(results, "town");
                final TownRow town = towns.get(key(townName));
                if (town == null) {
                    continue;
                }
                final UUID world = world(columns.string(results, "world"), worldsByName);
                if (world == null) {
                    unknownWorlds++;
                    continue;
                }
                final int x = columns.integer(results, "x", 0);
                final int z = columns.integer(results, "z", 0);

                final boolean homeblock = town.homeblock().matches(world, x, z, worldsByName);
                if (homeblock) {
                    homeblocksFound.add(key(townName));
                }
                final Resident owner = residents.get(key(columns.string(results, "resident")));

                claims.add(new MigrationPlan.Claim(
                        town.plan().name(), world, x, z,
                        homeblock,
                        columns.bool(results, "outpost", false),
                        owner == null ? null : owner.plan().id()));
            }
        }

        if (unknownWorlds > 0) {
            notes.add(unknownWorlds + " townblock(s) named a world that is not in Towny's world "
                    + "table and could not be placed.");
        }
        final long missingHomeblocks = towns.keySet().stream()
                .filter(town -> !homeblocksFound.contains(town))
                .count();
        if (missingHomeblocks > 0) {
            // Worth saying: RiftTowny's contiguity rules anchor on the homeblock, so a town that
            // arrives without one is a town whose shape cannot be checked.
            notes.add(missingHomeblocks + " town(s) had no townblock matching their recorded "
                    + "homeblock; they will arrive without one.");
        }
        return claims;
    }

    /** Towny's world table, so a townblock naming a world by name can still be placed. */
    private Map<String, UUID> readWorlds(final Connection connection) {
        final Map<String, UUID> worlds = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(select("WORLDS"));
             ResultSet results = statement.executeQuery()) {
            final Columns columns = Columns.of(results);
            while (results.next()) {
                final String name = columns.string(results, "name");
                final UUID id = columns.uuid(results, "uuid");
                if (name != null && id != null) {
                    worlds.put(key(name), id);
                }
            }
        } catch (final SQLException unreadable) {
            // Not fatal. Modern Towny stores the world's UUID directly on the townblock, so this
            // table is only a fallback for older data.
            notes.add("Towny's world table could not be read (" + unreadable.getMessage()
                    + "); worlds named by name rather than UUID will be skipped.");
        }
        return worlds;
    }

    /** A townblock's world, which newer Towny stores as a UUID and older Towny as a name. */
    private static UUID world(final String raw, final Map<String, UUID> worldsByName) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return parseUuid(raw).orElseGet(() -> worldsByName.get(key(raw)));
    }

    // --- plumbing ---------------------------------------------------------------------------------

    private String select(final String table) {
        // The table name is built from a configured prefix and a constant, never from user input,
        // so there is nothing here for a parameter to protect - and a table name cannot be one.
        return "SELECT * FROM `" + prefix + table + '`';
    }

    private static String key(final String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    private static Optional<UUID> parseUuid(final String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(raw.trim()));
        } catch (final IllegalArgumentException notAUuid) {
            return Optional.empty();
        }
    }

    private record Resident(MigrationPlan.Resident plan) {
    }

    private record TownRow(MigrationPlan.Town plan, Homeblock homeblock, UUID uuid) {
    }

    /**
     * A town's home chunk, as Towny writes it: {@code world,x,z}.
     *
     * <p>Recorded on the town rather than flagged on the townblock, so the two have to be matched
     * back together. A town whose homeblock will not parse simply has none, and the import reports
     * it — inventing one would put a town's centre somewhere nobody chose.</p>
     */
    private record Homeblock(String world, int x, int z, boolean present) {

        static Homeblock parse(final String raw) {
            if (raw == null || raw.isBlank()) {
                return new Homeblock("", 0, 0, false);
            }
            final String[] parts = raw.split(",");
            if (parts.length < 3) {
                return new Homeblock("", 0, 0, false);
            }
            try {
                return new Homeblock(
                        parts[0].trim(),
                        Integer.parseInt(parts[parts.length - 2].trim()),
                        Integer.parseInt(parts[parts.length - 1].trim()),
                        true);
            } catch (final NumberFormatException notCoordinates) {
                return new Homeblock("", 0, 0, false);
            }
        }

        boolean matches(final UUID chunkWorld, final int chunkX, final int chunkZ,
                        final Map<String, UUID> worldsByName) {
            if (!present || chunkX != x || chunkZ != z) {
                return false;
            }
            final UUID mine = parseUuid(world).orElseGet(() -> worldsByName.get(key(world)));
            // A homeblock whose world cannot be resolved still matches on coordinates alone. Towny
            // allows one homeblock per town, so the risk of a false match is a town with a chunk at
            // the same x and z in a second world - rare, and the cost is a homeblock in the wrong
            // world rather than a wrong town.
            return mine == null || mine.equals(chunkWorld);
        }
    }

    /**
     * Which columns this database actually has.
     *
     * <p>Built from the result set's own metadata, so a column added in a later Towny than the one
     * being read is absent rather than an exception. Every accessor tolerates that.</p>
     */
    private static final class Columns {

        private final Set<String> present;

        private Columns(final Set<String> present) {
            this.present = present;
        }

        static Columns of(final ResultSet results) throws SQLException {
            final ResultSetMetaData metadata = results.getMetaData();
            final Set<String> names = new HashSet<>();
            for (int index = 1; index <= metadata.getColumnCount(); index++) {
                names.add(metadata.getColumnLabel(index).toLowerCase(Locale.ROOT));
            }
            return new Columns(names);
        }

        boolean has(final String column) {
            return present.contains(column.toLowerCase(Locale.ROOT));
        }

        String string(final ResultSet results, final String column) throws SQLException {
            if (!has(column)) {
                return null;
            }
            final String value = results.getString(column);
            return value == null || value.isBlank() ? null : value.trim();
        }

        UUID uuid(final ResultSet results, final String column) throws SQLException {
            return parseUuid(string(results, column)).orElse(null);
        }

        boolean bool(final ResultSet results, final String column, final boolean fallback)
                throws SQLException {
            if (!has(column)) {
                return fallback;
            }
            final boolean value = results.getBoolean(column);
            return results.wasNull() ? fallback : value;
        }

        int integer(final ResultSet results, final String column, final int fallback)
                throws SQLException {
            if (!has(column)) {
                return fallback;
            }
            final int value = results.getInt(column);
            return results.wasNull() ? fallback : value;
        }

        /**
         * A Towny timestamp, which is epoch milliseconds.
         *
         * <p>Zero reads as absent rather than as 1970. Towny writes 0 for "never", and importing a
         * town founded at the epoch would make every listing sorted by age nonsense.</p>
         */
        Instant instant(final ResultSet results, final String column) throws SQLException {
            if (!has(column)) {
                return null;
            }
            final long value = results.getLong(column);
            return results.wasNull() || value <= 0L ? null : Instant.ofEpochMilli(value);
        }
    }
}
