package net.riftbreaker.rifttowny.storage.migration;

import net.riftbreaker.rifttowny.domain.migration.MigrationPlan;
import net.riftbreaker.rifttowny.domain.migration.MigrationSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Reading a Towny data folder.
 *
 * <p>Fixtures are written in the layout and reference style read out of Towny's own bytecode:
 * town and nation files named after <em>UUIDs</em>, and {@code nation=}, {@code capital=},
 * {@code town=}, {@code resident=} written as UUIDs — which is what modern Towny does, and what an
 * importer matching on names alone would silently find nothing of. Older files still hold names,
 * so both are covered.</p>
 *
 * <p>There is no integration test against a real Towny and there cannot be one: it cannot run
 * beside RiftTowny.</p>
 */
class TownyFlatFileSourceTest {

    private static final UUID BEDE = UUID.randomUUID();
    private static final UUID ADA = UUID.randomUUID();
    private static final UUID WORLD = UUID.randomUUID();
    private static final UUID ASHFORD = UUID.randomUUID();
    private static final UUID VALEN = UUID.randomUUID();
    private static final long REGISTERED = Instant.parse("2024-03-01T12:00:00Z").toEpochMilli();

    @TempDir
    private Path root;

    private Path data;

    @BeforeEach
    void createLayout() throws IOException {
        data = root.resolve("data");
        for (final String folder : new String[] {"residents", "towns", "nations", "worlds"}) {
            Files.createDirectories(data.resolve(folder));
        }
        Files.createDirectories(data.resolve("townblocks").resolve(WORLD.toString()));
    }

    private void write(final String relative, final String... lines) throws IOException {
        final Path file = data.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, String.join("\n", lines) + '\n', StandardCharsets.UTF_8);
    }

    private MigrationPlan read() throws MigrationSource.MigrationException {
        return new TownyFlatFileSource(data).read();
    }

    private void aResident(final String name, final UUID id, final String town) throws IOException {
        write("residents/" + name + ".txt",
                "name=" + name,
                "uuid=" + id,
                "town=" + (town == null ? "" : town),
                "registered=" + REGISTERED,
                "lastOnline=" + REGISTERED);
    }

    @Nested
    @DisplayName("a straightforward data folder")
    class HappyPath {

        @BeforeEach
        void populate() throws IOException {
            aResident("Bede", BEDE, "Ashford");
            aResident("Ada", ADA, "Ashford");
            // As modern Towny writes it: every cross-reference is a UUID.
            write("towns/" + ASHFORD + ".txt",
                    "name=Ashford",
                    "uuid=" + ASHFORD,
                    "mayor=" + BEDE,
                    "nation=" + VALEN,
                    "townBoard=Welcome to Ashford",
                    "tag=ASH",
                    "open=true",
                    "public=false",
                    "registered=" + REGISTERED,
                    "homeBlock=world,3,4");
            write("nations/" + VALEN + ".txt",
                    "name=Valen",
                    "uuid=" + VALEN,
                    "capital=" + ASHFORD,
                    "nationBoard=For Valen",
                    "tag=VAL",
                    "registered=" + REGISTERED);
            write("townblocks/" + WORLD + "/3_4_16.data", "town=" + ASHFORD);
            write("townblocks/" + WORLD + "/3_5_16.data", "town=" + ASHFORD, "resident=" + ADA);
            write("townblocks/" + WORLD + "/90_90_16.data", "town=" + ASHFORD, "outpost=true");
        }

        @Test
        @DisplayName("reads residents from their own files")
        void readsResidents() throws Exception {
            assertThat(read().residents())
                    .extracting(MigrationPlan.Resident::id)
                    .containsExactlyInAnyOrder(BEDE, ADA);
        }

        @Test
        @DisplayName("reads the town and everything written on it")
        void readsTowns() throws Exception {
            final MigrationPlan.Town town = read().towns().getFirst();

            assertThat(town.name()).isEqualTo("Ashford");
            assertThat(town.mayorId()).isEqualTo(BEDE);
            assertThat(town.nationName()).isEqualTo("Valen");
            assertThat(town.board()).isEqualTo("Welcome to Ashford");
            assertThat(town.tag()).isEqualTo("ASH");
            assertThat(town.open()).isTrue();
            assertThat(town.publicSpawn()).isFalse();
            assertThat(town.founded()).isEqualTo(Instant.ofEpochMilli(REGISTERED));
        }

        @Test
        @DisplayName("takes a nation's leader from its capital's mayor")
        void nationLeaderComesFromTheCapital() throws Exception {
            final MigrationPlan.Nation nation = read().nations().getFirst();

            assertThat(nation.kingId()).isEqualTo(BEDE);
            assertThat(nation.capitalTownName()).isEqualTo("Ashford");
            assertThat(nation.board()).isEqualTo("For Valen");
        }

        @Test
        @DisplayName("takes each claim's coordinates from its file name")
        void readsClaimsFromFileNames() throws Exception {
            final var claims = read().claims();

            assertThat(claims).hasSize(3);
            assertThat(claims).allSatisfy(claim -> assertThat(claim.worldId()).isEqualTo(WORLD));
            assertThat(claims).extracting(MigrationPlan.Claim::chunkX)
                    .containsExactlyInAnyOrder(3, 3, 90);
        }

        @Test
        @DisplayName("matches the homeblock onto the chunk the town names")
        void findsTheHomeblock() throws Exception {
            assertThat(read().claims()).filteredOn(MigrationPlan.Claim::homeblock)
                    .singleElement()
                    .satisfies(claim -> {
                        assertThat(claim.chunkX()).isEqualTo(3);
                        assertThat(claim.chunkZ()).isEqualTo(4);
                    });
        }

        @Test
        @DisplayName("carries plot ownership and outposts")
        void readsClaimDetail() throws Exception {
            final var claims = read().claims();

            assertThat(claims).filteredOn(claim -> claim.ownerId() != null)
                    .singleElement().extracting(MigrationPlan.Claim::ownerId).isEqualTo(ADA);
            assertThat(claims).filteredOn(MigrationPlan.Claim::outpost).hasSize(1);
        }
    }

    @Nested
    @DisplayName("the awkward cases")
    class Awkward {

        @Test
        @DisplayName("a value containing '=' survives, because only the first one splits")
        void valuesMayContainEquals() throws Exception {
            // A board is free text. Splitting on every '=' would truncate whatever a player wrote.
            aResident("Bede", BEDE, "Ashford");
            write("towns/" + ASHFORD + ".txt", "name=Ashford", "mayor=" + BEDE, "townBoard=x = y = z");

            assertThat(read().towns().getFirst().board()).isEqualTo("x = y = z");
        }

        @Test
        @DisplayName("UTF-8 text survives whatever the platform's default charset is")
        void utf8IsPreserved() throws Exception {
            // Read on a machine defaulting to Windows-1252, a board with an accent would otherwise
            // arrive as mojibake and nobody would notice until a player complained.
            aResident("Bede", BEDE, "Ashford");
            write("towns/" + ASHFORD + ".txt", "name=Ashford", "mayor=" + BEDE, "townBoard=Bienvenue à Ashford ⛏");

            assertThat(read().towns().getFirst().board()).isEqualTo("Bienvenue à Ashford ⛏");
        }

        @Test
        @DisplayName("blank lines and comments are ignored")
        void commentsAreIgnored() throws Exception {
            write("residents/Bede.txt", "# a comment", "", "uuid=" + BEDE, "   ", "name=Bede");

            assertThat(read().residents()).singleElement()
                    .extracting(MigrationPlan.Resident::id).isEqualTo(BEDE);
        }

        @Test
        @DisplayName("a key a different Towny version never wrote reads as absent")
        void missingKeysAreAbsent() throws Exception {
            aResident("Bede", BEDE, "Ashford");
            write("towns/" + ASHFORD + ".txt", "name=Ashford", "mayor=" + BEDE);

            final MigrationPlan.Town town = read().towns().getFirst();

            assertThat(town.name()).isEqualTo("Ashford");
            assertThat(town.board()).isNull();
            assertThat(town.tag()).isNull();
            assertThat(town.open()).isFalse();
            assertThat(town.founded()).isNull();
        }

        @Test
        @DisplayName("the deleted and hibernated recycle bins are not walked")
        void recycleBinsAreLeftAlone() throws Exception {
            // Towny keeps removed records under these. Importing them would resurrect residents and
            // towns a server had already thrown away.
            aResident("Bede", BEDE, "Ashford");
            write("residents/deleted/Ghost.txt", "uuid=" + ADA, "name=Ghost");
            write("towns/deleted/Ruins.txt", "name=Ruins", "mayor=" + BEDE);

            assertThat(read().residents()).hasSize(1);
            assertThat(read().towns()).isEmpty();
        }

        @Test
        @DisplayName("a townblock whose name is not coordinates is skipped and reported")
        void unparseableTownblockNames() throws Exception {
            aResident("Bede", BEDE, "Ashford");
            write("towns/" + ASHFORD + ".txt", "name=Ashford", "mayor=" + BEDE);
            write("townblocks/" + WORLD + "/notcoords.data", "town=" + ASHFORD);

            final TownyFlatFileSource source = new TownyFlatFileSource(data);
            assertThat(source.read().claims()).isEmpty();
            assertThat(source.notes()).anyMatch(note -> note.contains("_<z>_"));
        }

        @Test
        @DisplayName("a world folder named by name rather than UUID still resolves")
        void worldFoldersMayBeNamed() throws Exception {
            write("worlds/world.txt", "uuid=" + WORLD);
            aResident("Bede", BEDE, "Ashford");
            write("towns/" + ASHFORD + ".txt", "name=Ashford", "mayor=" + BEDE);
            write("townblocks/world/0_0_16.data", "town=" + ASHFORD);

            assertThat(read().claims()).singleElement()
                    .extracting(MigrationPlan.Claim::worldId).isEqualTo(WORLD);
        }

        @Test
        @DisplayName("a resident with no UUID is left out and reported")
        void residentsWithoutAccountsAreDropped() throws Exception {
            write("residents/Ancient.txt", "name=Ancient");

            final TownyFlatFileSource source = new TownyFlatFileSource(data);
            assertThat(source.read().residents()).isEmpty();
            assertThat(source.notes()).anyMatch(note -> note.contains("no UUID"));
        }

        @Test
        @DisplayName("a ruined town is left out, not resurrected")
        void ruinedTownsAreDropped() throws Exception {
            aResident("Bede", BEDE, "Fallen");
            write("towns/Fallen.txt", "name=Fallen", "mayor=" + BEDE, "ruined=true");

            final TownyFlatFileSource source = new TownyFlatFileSource(data);
            assertThat(source.read().towns()).isEmpty();
            assertThat(source.notes()).anyMatch(note -> note.contains("already ruined"));
        }

        @Test
        @DisplayName("a resident's town is a UUID, and resolves to the town's name")
        void residentTownReferenceIsAUuid() throws Exception {
            // The bug this exists for was silent and severe: a resident's town= is a town UUID on
            // modern Towny, the importer joins residents to towns by NAME, so every town would have
            // arrived holding only its mayor and every other resident would have been dropped -
            // reported as an adjustment rather than as the data loss it is.
            write("residents/Bede.txt", "name=Bede", "uuid=" + BEDE, "town=" + ASHFORD);
            write("residents/Ada.txt", "name=Ada", "uuid=" + ADA, "town=" + ASHFORD);
            write("towns/" + ASHFORD + ".txt",
                    "name=Ashford", "uuid=" + ASHFORD, "mayor=" + BEDE);

            assertThat(read().residents())
                    .as("both residents must name the town, not its UUID")
                    .allSatisfy(resident ->
                            assertThat(resident.townName()).isEqualTo("Ashford"))
                    .hasSize(2);
        }

        @Test
        @DisplayName("a resident whose town did not come across keeps their reference")
        void unresolvedTownReferencesSurvive() throws Exception {
            // Left as-is rather than nulled: the importer reports a town it cannot find, and
            // blanking it here would turn a reportable problem into a silent townless player.
            final UUID missing = UUID.randomUUID();
            write("residents/Bede.txt", "name=Bede", "uuid=" + BEDE, "town=" + missing);

            assertThat(read().residents()).singleElement()
                    .extracting(MigrationPlan.Resident::townName)
                    .isEqualTo(missing.toString());
        }

        @Test
        @DisplayName("older files referencing by name still resolve")
        void legacyNameReferencesStillWork() throws Exception {
            // Towny's own loader tries the UUID and falls back to the name, because files carried
            // forward from older versions hold names. Handling only one would drop half of them.
            aResident("Bede", BEDE, "Ashford");
            write("towns/Ashford.txt", "name=Ashford", "mayor=Bede", "nation=Valen");
            write("nations/Valen.txt", "name=Valen", "capital=Ashford");
            write("townblocks/" + WORLD + "/0_0_16.data", "town=Ashford", "resident=Bede");

            final MigrationPlan plan = read();

            assertThat(plan.towns()).singleElement()
                    .extracting(MigrationPlan.Town::nationName).isEqualTo("Valen");
            assertThat(plan.nations()).singleElement()
                    .extracting(MigrationPlan.Nation::kingId).isEqualTo(BEDE);
            assertThat(plan.claims()).singleElement()
                    .extracting(MigrationPlan.Claim::ownerId).isEqualTo(BEDE);
        }

        @Test
        @DisplayName("a claim on a non-default grid size is refused rather than misplaced")
        void nonDefaultTownBlockSizeIsRefused() throws Exception {
            // Towny claims a configurable grid cell; RiftTowny claims a chunk. They are the same
            // thing only at 16. Importing a size-32 cell as a chunk would put every claim in the
            // wrong place by a factor of two, and nothing downstream could tell.
            aResident("Bede", BEDE, "Ashford");
            write("towns/" + ASHFORD + ".txt", "name=Ashford", "mayor=" + BEDE);
            write("townblocks/" + WORLD + "/3_4_32.data", "town=" + ASHFORD);

            final TownyFlatFileSource source = new TownyFlatFileSource(data);

            assertThat(source.read().claims()).isEmpty();
            assertThat(source.notes()).anyMatch(note -> note.contains("grid other than 16"));
        }

        @Test
        @DisplayName("a folder that is not a Towny data folder says so, naming what it wanted")
        void wrongFolderIsNamed() {
            assertThatThrownBy(() -> new TownyFlatFileSource(root.resolve("nope")).read())
                    .isInstanceOf(MigrationSource.MigrationException.class)
                    .hasMessageContaining("plugins/Towny/data");
        }

        @Test
        @DisplayName("a data folder with no townblocks imports the towns and says the land is missing")
        void noTownblocksFolder() throws Exception {
            Files.delete(data.resolve("townblocks").resolve(WORLD.toString()));
            Files.delete(data.resolve("townblocks"));
            aResident("Bede", BEDE, "Ashford");
            write("towns/" + ASHFORD + ".txt", "name=Ashford", "mayor=" + BEDE);

            final TownyFlatFileSource source = new TownyFlatFileSource(data);
            assertThat(source.read().towns()).hasSize(1);
            assertThat(source.notes()).anyMatch(note -> note.contains("No townblocks folder"));
        }
    }
}
