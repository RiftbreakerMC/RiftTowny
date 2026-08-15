package net.riftbreaker.rifttowny.storage.migration;

import net.riftbreaker.rifttowny.domain.migration.MigrationPlan;
import net.riftbreaker.rifttowny.domain.migration.MigrationSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Reads a Towny installation stored as flatfiles.
 *
 * <p>The other half of {@link TownySqlSource}, for the servers that never moved to a database.
 * Offline for the same forced reason: RiftTowny disables itself when Towny is present, so this
 * reads a data folder nothing is holding open.</p>
 *
 * <h2>The layout</h2>
 *
 * <pre>
 *   &lt;data&gt;/residents/&lt;name&gt;.txt
 *   &lt;data&gt;/towns/&lt;name&gt;.txt
 *   &lt;data&gt;/nations/&lt;name&gt;.txt
 *   &lt;data&gt;/worlds/&lt;name&gt;.txt
 *   &lt;data&gt;/townblocks/&lt;world&gt;/&lt;x&gt;_&lt;z&gt;_&lt;size&gt;.data
 * </pre>
 *
 * <p>Read out of {@code TownyFlatFileSource}'s own string constants rather than guessed. The
 * {@code deleted/} and {@code hibernated/} subfolders are Towny's own recycle bins and are
 * deliberately not walked — importing them would resurrect residents and towns that a server had
 * already removed.</p>
 *
 * <h2>References are UUIDs, or names, and you cannot assume which</h2>
 *
 * <p>Modern Towny writes {@code nation=}, {@code capital=}, {@code town=} and {@code resident=} as
 * <strong>UUIDs</strong>, and names its town and nation files after UUIDs too. Files carried
 * forward from older versions still hold names. Towny's own loader tries the UUID first and falls
 * back to the name, so this does the same — assuming either one alone would silently drop every
 * nation on half the servers in existence.</p>
 *
 * <h2>Read forgivingly</h2>
 *
 * <p>Same principle as the SQL reader, for the same reason: Towny's file format grows key by key
 * across versions. An absent key reads as absent, a file that will not parse costs that one record
 * and is reported, and neither stops the run. A migration that refused wholesale because one town
 * file was truncated would be a migration nobody could complete.</p>
 *
 * <p>One place it is deliberately <em>less</em> forgiving than Towny: Towny aborts the entire
 * townblock load if a single filename is malformed. This skips that one file and carries on.</p>
 */
public final class TownyFlatFileSource implements MigrationSource {

    /**
     * A Minecraft chunk, and Towny's default {@code town_block_size}.
     *
     * <p>Towny claims a configurable grid cell rather than a chunk; RiftTowny claims a chunk. The
     * two are the same thing only at this value, which is why anything else is refused rather than
     * converted.</p>
     */
    private static final int CHUNK = 16;

    private final Path dataFolder;
    private final List<String> notes = new ArrayList<>();

    public TownyFlatFileSource(final Path dataFolder) {
        this.dataFolder = Objects.requireNonNull(dataFolder, "dataFolder");
    }

    @Override
    public List<String> notes() {
        return List.copyOf(notes);
    }

    @Override
    public MigrationPlan read() throws MigrationException {
        notes.clear();
        if (!Files.isDirectory(dataFolder)) {
            throw new MigrationException(
                    "No Towny data folder at " + dataFolder.toAbsolutePath()
                            + ". It should be the folder containing residents/, towns/ and "
                            + "townblocks/ — usually plugins/Towny/data.");
        }

        final Map<String, UUID> worlds = readWorlds();
        final References<MigrationPlan.Resident> residents = readResidents();
        // Nations are read before towns because a town names its nation and needs the name back;
        // the nation then needs its capital's mayor, which is why resolution is a second pass
        // rather than something either read can do on its own.
        final List<RawNation> rawNations = readNations();
        final References<TownRow> towns = readTowns(residents, rawNations);
        final List<MigrationPlan.Nation> nations = resolveNations(rawNations, towns);
        final List<MigrationPlan.Claim> claims = readClaims(towns, residents, worlds);

        return new MigrationPlan(
                "Towny flatfiles at " + dataFolder,
                resolveResidentTowns(residents, towns),
                towns.distinct().stream().map(TownRow::plan).toList(),
                nations,
                claims);
    }

    /**
     * Turns each resident's town reference into a town name.
     *
     * <p>A resident's {@code town=} is a town <strong>UUID</strong> on modern Towny — Towny's own
     * loader parses it as one and falls back to a name. The importer joins residents to towns by
     * name, so leaving the UUID in place would match nothing: every town would arrive holding only
     * its mayor, every other resident would be dropped, and the report would call it an adjustment
     * rather than the data loss it is.</p>
     *
     * <p>Done as a pass at the end because the two reads are circular — a town needs its mayor from
     * the residents, and a resident needs its town's name from the towns.</p>
     */
    private static List<MigrationPlan.Resident> resolveResidentTowns(
            final References<MigrationPlan.Resident> residents, final References<TownRow> towns) {
        final List<MigrationPlan.Resident> resolved = new ArrayList<>(residents.size());
        for (final MigrationPlan.Resident resident : residents.distinct()) {
            final String townName = towns.find(resident.townName())
                    .map(town -> town.plan().name())
                    // Left as it was when nothing matches: a name that already is a town's name
                    // resolves above, and anything else is a town that did not come across, which
                    // the importer reports for itself.
                    .orElse(resident.townName());
            resolved.add(new MigrationPlan.Resident(
                    resident.id(), resident.name(), townName,
                    resident.joined(), resident.lastSeen()));
        }
        return List.copyOf(resolved);
    }

    /**
     * Everything of one kind, reachable by UUID or by name.
     *
     * <p>Both keys point at the same record. Towny writes references as UUIDs now and wrote them as
     * names before, and its own loader accepts either — so a lookup here takes whatever the file
     * happened to contain.</p>
     */
    private static final class References<T> {

        private final Map<String, T> byKey = new LinkedHashMap<>();
        private final List<T> inOrder = new ArrayList<>();

        void add(final T value, final String name, final UUID id) {
            inOrder.add(value);
            if (name != null && !name.isBlank()) {
                byKey.putIfAbsent(key(name), value);
            }
            if (id != null) {
                byKey.putIfAbsent(key(id.toString()), value);
            }
        }

        Optional<T> find(final String reference) {
            return reference == null || reference.isBlank()
                    ? Optional.empty()
                    : Optional.ofNullable(byKey.get(key(reference)));
        }

        /** Each record once, in the order read — not once per key it answers to. */
        List<T> distinct() {
            return List.copyOf(inOrder);
        }

        int size() {
            return inOrder.size();
        }
    }

    // --- the record types ------------------------------------------------------------------------

    private Map<String, UUID> readWorlds() {
        final Map<String, UUID> worlds = new HashMap<>();
        forEachFile(dataFolder.resolve("worlds"), ".txt", (name, keys) ->
                uuid(keys.get("uuid")).ifPresent(id -> worlds.put(key(name), id)));
        return worlds;
    }

    /**
     * Every player.
     *
     * <p>The file is named after the resident, and the name inside is not always present — so the
     * filename is the name, exactly as Towny treats it. A resident with no UUID is dropped for the
     * same reason as in the SQL reader: RiftTowny keys people by account, and matching by name
     * would hand somebody's town to whoever next took it.</p>
     */
    private References<MigrationPlan.Resident> readResidents() {
        final References<MigrationPlan.Resident> residents = new References<>();
        final int[] withoutUuid = {0};
        final int[] npcs = {0};

        forEachFile(dataFolder.resolve("residents"), ".txt", (fileName, keys) -> {
            if (bool(keys.get("isNPC"), false)) {
                npcs[0]++;
                return;
            }
            final Optional<UUID> id = uuid(keys.get("uuid")).or(() -> uuid(fileName));
            if (id.isEmpty()) {
                withoutUuid[0]++;
                return;
            }
            final String name = text(keys.get("name")).orElse(fileName);
            residents.add(new MigrationPlan.Resident(
                    id.get(),
                    name,
                    text(keys.get("town")).orElse(null),
                    instant(keys.get("registered")),
                    instant(keys.get("lastOnline"))), name, id.get());
        });

        if (withoutUuid[0] > 0) {
            notes.add(withoutUuid[0] + " resident file(s) had no UUID and were left out; "
                    + "a player cannot be matched by name alone.");
        }
        if (npcs[0] > 0) {
            notes.add(npcs[0] + " Towny NPC account(s) were left out.");
        }
        return residents;
    }

    /** Nations, unresolved. Their leader lives in a town file, so that has to wait. */
    private List<RawNation> readNations() {
        final List<RawNation> nations = new ArrayList<>();
        forEachFile(dataFolder.resolve("nations"), ".txt", (fileName, keys) ->
                nations.add(new RawNation(
                        text(keys.get("name")).orElse(fileName),
                        uuid(keys.get("uuid")).or(() -> uuid(fileName)).orElse(null),
                        text(keys.get("capital")).orElse(null),
                        text(keys.get("nationBoard")).orElse(null),
                        text(keys.get("tag")).orElse(null),
                        bool(keys.get("neutral"), false),
                        instant(keys.get("registered")))));
        return nations;
    }

    private References<TownRow> readTowns(
            final References<MigrationPlan.Resident> residents, final List<RawNation> nations) {
        final References<TownRow> towns = new References<>();
        final int[] ruined = {0};
        final int[] mayorless = {0};

        // A town names its nation by UUID on modern Towny and by name on older files, so the
        // reference is resolved against both.
        final References<RawNation> nationsByReference = new References<>();
        nations.forEach(nation -> nationsByReference.add(nation, nation.name(), nation.uuid()));

        forEachFile(dataFolder.resolve("towns"), ".txt", (fileName, keys) -> {
            if (bool(keys.get("ruined"), false)) {
                ruined[0]++;
                return;
            }
            final Optional<MigrationPlan.Resident> mayor =
                    residents.find(text(keys.get("mayor")).orElse(null));
            if (mayor.isEmpty()) {
                mayorless[0]++;
                return;
            }
            final String name = text(keys.get("name")).orElse(fileName);
            final UUID id = uuid(keys.get("uuid")).or(() -> uuid(fileName)).orElse(null);
            final String nationName = nationsByReference
                    .find(text(keys.get("nation")).orElse(null))
                    .map(RawNation::name)
                    .orElse(null);

            towns.add(new TownRow(
                    new MigrationPlan.Town(
                            name,
                            mayor.get().id(),
                            mayor.get().name(),
                            nationName,
                            text(keys.get("townBoard")).orElse(null),
                            text(keys.get("tag")).orElse(null),
                            bool(keys.get("open"), false),
                            bool(keys.get("public"), false),
                            bool(keys.get("neutral"), false),
                            instant(keys.get("registered"))),
                    Homeblock.parse(keys.get("homeBlock"))), name, id);
        });

        if (ruined[0] > 0) {
            notes.add(ruined[0] + " town(s) were already ruined in Towny and were left out.");
        }
        if (mayorless[0] > 0) {
            notes.add(mayorless[0] + " town(s) had a mayor who is not an importable resident.");
        }
        return towns;
    }

    /**
     * Nations, once their capitals are known.
     *
     * <p>A nation's leader is its capital's mayor — Towny records no leader of its own, in the
     * flatfiles exactly as in the database. So a nation whose capital did not come across has
     * nobody to lead it and is dropped rather than arriving leaderless.</p>
     */
    private List<MigrationPlan.Nation> resolveNations(
            final List<RawNation> raw, final References<TownRow> towns) {
        final List<MigrationPlan.Nation> nations = new ArrayList<>();
        int capitalless = 0;

        for (final RawNation nation : raw) {
            final Optional<TownRow> capital = towns.find(nation.capitalReference());
            if (capital.isEmpty()) {
                capitalless++;
                continue;
            }
            nations.add(new MigrationPlan.Nation(
                    nation.name(),
                    capital.get().plan().mayorId(),
                    capital.get().plan().name(),
                    nation.board(),
                    nation.tag(),
                    nation.neutral(),
                    nation.registered()));
        }

        if (capitalless > 0) {
            notes.add(capitalless + " nation(s) had no importable capital town; Towny takes a "
                    + "nation's leader from its capital's mayor, so they have nobody to lead them.");
        }
        return nations;
    }

    /**
     * Every claimed chunk, one file each, under a folder per world.
     *
     * <p>The coordinates come from the file <em>name</em>, not from inside it, so parsing the name
     * is load-bearing: a mistake here misplaces every claim on the server.</p>
     */
    private List<MigrationPlan.Claim> readClaims(
            final References<TownRow> towns,
            final References<MigrationPlan.Resident> residents,
            final Map<String, UUID> worlds
    ) {
        final List<MigrationPlan.Claim> claims = new ArrayList<>();
        final Path root = dataFolder.resolve("townblocks");
        if (!Files.isDirectory(root)) {
            notes.add("No townblocks folder; no land was imported.");
            return claims;
        }

        final java.util.Set<String> homeblocksFound = new java.util.HashSet<>();
        final int[] unreadable = {0};
        final int[] unknownWorlds = {0};
        final int[] wrongSize = {0};

        try (Stream<Path> worldFolders = Files.list(root)) {
            for (final Path worldFolder : worldFolders.filter(Files::isDirectory).toList()) {
                final String worldName = worldFolder.getFileName().toString();
                final UUID world = uuid(worldName).orElseGet(() -> worlds.get(key(worldName)));
                if (world == null) {
                    unknownWorlds[0]++;
                    continue;
                }
                forEachFile(worldFolder, ".data", (fileName, keys) -> {
                    final Optional<Coordinates> at = Coordinates.parse(fileName);
                    if (at.isEmpty()) {
                        unreadable[0]++;
                        return;
                    }
                    // Towny's cell coordinates are floorDiv(block, townBlockSize). At the default
                    // 16 that is exactly a chunk, which is RiftTowny's unit. At any other size the
                    // two grids do not line up and there is no correct conversion - so those are
                    // reported and skipped rather than placed a factor out.
                    if (at.get().size() != CHUNK) {
                        wrongSize[0]++;
                        return;
                    }
                    final Optional<TownRow> town = towns.find(text(keys.get("town")).orElse(null));
                    if (town.isEmpty()) {
                        return;
                    }
                    final boolean homeblock =
                            town.get().homeblock().matches(at.get().x(), at.get().z());
                    if (homeblock) {
                        homeblocksFound.add(key(town.get().plan().name()));
                    }
                    final Optional<MigrationPlan.Resident> owner =
                            residents.find(text(keys.get("resident")).orElse(null));

                    claims.add(new MigrationPlan.Claim(
                            town.get().plan().name(), world, at.get().x(), at.get().z(),
                            homeblock,
                            bool(keys.get("outpost"), false),
                            owner.map(MigrationPlan.Resident::id).orElse(null)));
                });
            }
        } catch (final IOException unreadableFolder) {
            notes.add("The townblocks folder could not be listed: " + unreadableFolder.getMessage());
        }

        if (unknownWorlds[0] > 0) {
            notes.add(unknownWorlds[0] + " world folder(s) could not be matched to a world.");
        }
        if (unreadable[0] > 0) {
            notes.add(unreadable[0] + " townblock file(s) had a name that is not "
                    + "<x>_<z>_<size>.data and were skipped.");
        }
        if (wrongSize[0] > 0) {
            notes.add(wrongSize[0] + " townblock(s) were claimed on a " + "grid other than "
                    + CHUNK + " blocks and were skipped: Towny's cells only line up with chunks at "
                    + "the default town_block_size, and there is no correct way to place them "
                    + "otherwise.");
        }
        final long missingHomeblocks = towns.distinct().stream()
                .filter(town -> !homeblocksFound.contains(key(town.plan().name())))
                .count();
        if (missingHomeblocks > 0) {
            notes.add(missingHomeblocks + " town(s) had no townblock matching their recorded "
                    + "homeblock; they will arrive without one.");
        }
        return claims;
    }

    // --- file plumbing ---------------------------------------------------------------------------

    /**
     * Walks one folder, parsing each file and handing over its name and keys.
     *
     * <p>Only the folder itself — never its subfolders. Towny keeps {@code deleted/} and
     * {@code hibernated/} beneath these, and walking into them would import records a server had
     * already thrown away.</p>
     */
    private void forEachFile(final Path folder, final String extension, final RecordReader reader) {
        if (!Files.isDirectory(folder)) {
            return;
        }
        try (Stream<Path> files = Files.list(folder)) {
            for (final Path file : files.filter(Files::isRegularFile).toList()) {
                final String fileName = file.getFileName().toString();
                if (!fileName.endsWith(extension)) {
                    continue;
                }
                final String name = fileName.substring(0, fileName.length() - extension.length());
                try {
                    reader.accept(name, parse(file));
                } catch (final IOException unreadable) {
                    notes.add("Could not read " + fileName + ": " + unreadable.getMessage());
                }
            }
        } catch (final IOException unreadable) {
            notes.add("Could not list " + folder.getFileName() + ": " + unreadable.getMessage());
        }
    }

    /**
     * One Towny data file, as a map.
     *
     * <p>Lines are {@code key=value}, split on the <em>first</em> {@code =} only — a board or a
     * title may legitimately contain one, and splitting on every occurrence would truncate whatever
     * a player had written. Blank lines and {@code #} comments are ignored.</p>
     *
     * <p>Read as UTF-8, which is what Towny writes. A board with an accent or an emoji, read on a
     * server whose default charset is Windows-1252, would otherwise arrive as mojibake — and the
     * operator would have no way to tell until a player complained.</p>
     *
     * <p>One deliberate divergence: Towny does <em>not</em> trim the value, so {@code board= hi }
     * keeps its spaces there. This trims. Nothing observable changes, because a board reaching
     * {@code TownProfile} is trimmed by {@code CivicText} regardless, and trimming here means a
     * numeric field written as {@code registered= 123 } parses instead of being silently dropped —
     * which is what happens inside Towny, where the exception is swallowed and the field left at
     * its default.</p>
     */
    private static Map<String, String> parse(final Path file) throws IOException {
        final Map<String, String> keys = new HashMap<>();
        for (final String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            final String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            final int equals = trimmed.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            keys.put(trimmed.substring(0, equals).trim(),
                    trimmed.substring(equals + 1).trim());
        }
        return keys;
    }

    // --- value readers ---------------------------------------------------------------------------

    private static Optional<String> text(final String raw) {
        return raw == null || raw.isBlank() ? Optional.empty() : Optional.of(raw.trim());
    }

    private static Optional<UUID> uuid(final String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(raw.trim()));
        } catch (final IllegalArgumentException notAUuid) {
            return Optional.empty();
        }
    }

    private static boolean bool(final String raw, final boolean fallback) {
        return text(raw).map(value -> "true".equalsIgnoreCase(value) || "1".equals(value))
                .orElse(fallback);
    }

    /** Epoch milliseconds. Zero reads as absent rather than 1970, exactly as in the SQL reader. */
    private static Instant instant(final String raw) {
        return text(raw).map(value -> {
            try {
                final long millis = Long.parseLong(value);
                return millis <= 0L ? null : Instant.ofEpochMilli(millis);
            } catch (final NumberFormatException notANumber) {
                return null;
            }
        }).orElse(null);
    }

    private static String key(final String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    @FunctionalInterface
    private interface RecordReader {
        void accept(String name, Map<String, String> keys) throws IOException;
    }

    private record TownRow(MigrationPlan.Town plan, Homeblock homeblock) {
    }

    /** A nation before its capital — and therefore its leader — has been resolved. */
    private record RawNation(
            String name,
            UUID uuid,
            String capitalReference,
            String board,
            String tag,
            boolean neutral,
            Instant registered) {
    }

    /**
     * A townblock's position, taken from its file name.
     *
     * <p>{@code <x>_<z>_<size>.data}. The third part is the grid size Towny was configured with,
     * and it is <em>not</em> ignorable: {@code x} and {@code z} are cell coordinates, so they only
     * mean chunks when the size is 16. Towny itself carries the size in the name for exactly this
     * reason — it skips files whose size does not match its current setting.</p>
     */
    private record Coordinates(int x, int z, int size) {

        static Optional<Coordinates> parse(final String fileName) {
            final String[] parts = fileName.split("_");
            if (parts.length < 3) {
                return Optional.empty();
            }
            try {
                return Optional.of(new Coordinates(
                        Integer.parseInt(parts[0].trim()),
                        Integer.parseInt(parts[1].trim()),
                        Integer.parseInt(parts[2].trim())));
            } catch (final NumberFormatException notCoordinates) {
                return Optional.empty();
            }
        }
    }

    /** A town's home chunk, recorded on the town as {@code world,x,z}. */
    private record Homeblock(int x, int z, boolean present) {

        static Homeblock parse(final String raw) {
            if (raw == null || raw.isBlank()) {
                return new Homeblock(0, 0, false);
            }
            final String[] parts = raw.split(",");
            if (parts.length < 3) {
                return new Homeblock(0, 0, false);
            }
            try {
                return new Homeblock(
                        Integer.parseInt(parts[parts.length - 2].trim()),
                        Integer.parseInt(parts[parts.length - 1].trim()),
                        true);
            } catch (final NumberFormatException notCoordinates) {
                return new Homeblock(0, 0, false);
            }
        }

        boolean matches(final int chunkX, final int chunkZ) {
            return present && x == chunkX && z == chunkZ;
        }
    }
}
