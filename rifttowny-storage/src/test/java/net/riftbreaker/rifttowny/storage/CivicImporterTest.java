package net.riftbreaker.rifttowny.storage;

import net.riftbreaker.rifttowny.api.ChunkKey;
import net.riftbreaker.rifttowny.domain.civic.CivicCache;
import net.riftbreaker.rifttowny.domain.civic.NationCache;
import net.riftbreaker.rifttowny.domain.migration.MigrationPlan;
import net.riftbreaker.rifttowny.domain.migration.MigrationReport;
import net.riftbreaker.rifttowny.domain.naming.NamePolicy;
import net.riftbreaker.rifttowny.domain.org.Resident;
import net.riftbreaker.rifttowny.domain.org.ResidentId;
import net.riftbreaker.rifttowny.domain.org.Town;
import net.riftbreaker.rifttowny.domain.service.CivicCacheService;
import net.riftbreaker.rifttowny.domain.service.CivicImporter;
import net.riftbreaker.rifttowny.domain.service.TownService;
import net.riftbreaker.rifttowny.domain.territory.ClaimKind;
import net.riftbreaker.rifttowny.domain.territory.TerritoryIndex;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bringing another plugin's world in.
 *
 * <p>An import is the one operation here that is both enormous and irreversible, run by somebody
 * who does not know exactly what is in the file, against a server that may already have towns on
 * it. So most of what is tested is what it <em>refuses</em> to do.</p>
 */
class CivicImporterTest extends SqliteFixture {

    private static final Instant NOW = Instant.parse("2026-08-12T09:00:00Z");
    private static final Instant LONG_AGO = Instant.parse("2024-03-01T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final UUID WORLD = UUID.randomUUID();
    private static final UUID MISSING_WORLD = UUID.randomUUID();

    private final CivicCache civicCache = CivicCache.empty();
    private final NationCache nationCache = NationCache.empty();
    private final TerritoryIndex index = TerritoryIndex.empty();

    private JdbcCivicStore store;
    private CivicImporter importer;
    private TownService towns;
    private JdbcResidentRepository residents;

    @BeforeEach
    void createServices() {
        store = new JdbcCivicStore(database, DIRECT, CLOCK);
        final CivicCacheService civic =
                new CivicCacheService(store, civicCache, nationCache, warning -> { });
        importer = new CivicImporter(store, NamePolicy.defaults(), CLOCK, civic, WORLD::equals);
        towns = new TownService(store, NamePolicy.defaults(), CLOCK, index, civic);
        residents = new JdbcResidentRepository(database, DIRECT);
    }

    // --- fixtures ---------------------------------------------------------------------------------

    private static final UUID BEDE = UUID.randomUUID();
    private static final UUID ADA = UUID.randomUUID();
    private static final UUID ROWAN = UUID.randomUUID();

    private static MigrationPlan.Resident resident(final UUID id, final String name, final String town) {
        return new MigrationPlan.Resident(id, name, town, LONG_AGO, LONG_AGO);
    }

    private static MigrationPlan.Town town(final String name, final UUID mayor, final String nation) {
        return new MigrationPlan.Town(name, mayor, "Bede", nation, "Welcome", "ASH",
                false, false, false, LONG_AGO);
    }

    private static MigrationPlan.Claim claim(final String town, final int x, final boolean home) {
        return new MigrationPlan.Claim(town, WORLD, x, 0, home, false, null);
    }

    private MigrationPlan onlyAshford() {
        return new MigrationPlan("a fixture",
                List.of(resident(BEDE, "Bede", "Ashford"), resident(ADA, "Ada", "Ashford")),
                List.of(town("Ashford", BEDE, null)),
                List.of(),
                List.of(claim("Ashford", 0, true), claim("Ashford", 1, false)));
    }

    @Nested
    @DisplayName("a dry run")
    class DryRun {

        @Test
        @DisplayName("says what it would do and writes nothing")
        void previewWritesNothing() {
            final MigrationReport report = importer.preview(onlyAshford()).join();

            assertThat(report.applied()).isFalse();
            assertThat(report.describe()).startsWith("Would import");
            assertThat(store.inTransaction(t -> t.towns().all()).join()).isEmpty();
            assertThat(store.inTransaction(t -> t.residents().find(ResidentId.of(BEDE))).join())
                    .isEmpty();
        }

        @Test
        @DisplayName("counts exactly what applying would bring in")
        void previewMatchesApply() {
            // A dry run whose numbers differ from the real thing is a dry run nobody can trust.
            final MigrationReport preview = importer.preview(onlyAshford()).join();
            final MigrationReport applied = importer.apply(onlyAshford()).join();

            assertThat(preview.towns()).isEqualTo(applied.towns());
            assertThat(preview.residents()).isEqualTo(applied.residents());
            assertThat(preview.claims()).isEqualTo(applied.claims());
        }
    }

    @Nested
    @DisplayName("applying")
    class Applying {

        @Test
        @DisplayName("brings the town, its people and its land across")
        void importsATown() {
            final MigrationReport report = importer.apply(onlyAshford()).join();

            assertThat(report.applied()).isTrue();
            assertThat(report.towns()).isEqualTo(1);
            assertThat(report.residents()).isEqualTo(2);
            assertThat(report.claims()).isEqualTo(2);
            assertThat(report.isClean()).isTrue();

            final Town imported =
                    store.inTransaction(t -> t.towns().findByName("ashford")).join().orElseThrow();
            assertThat(imported.name().display()).isEqualTo("Ashford");
            assertThat(imported.mayor()).isEqualTo(ResidentId.of(BEDE));
            assertThat(imported.residentCount()).isEqualTo(2);
            assertThat(imported.profile().board()).isEqualTo("Welcome");
        }

        @Test
        @DisplayName("keeps the founding date rather than stamping today")
        void keepsHistory() {
            // A migration that reset every town's age would throw away the one thing a long-running
            // server has that a new one does not.
            importer.apply(onlyAshford()).join();

            assertThat(store.inTransaction(t -> t.towns().findByName("ashford")).join()
                    .orElseThrow().createdAt())
                    .isEqualTo(LONG_AGO);
        }

        @Test
        @DisplayName("gives the town a role book, or its land would refuse everything")
        void townsGetARoleBook() {
            importer.apply(onlyAshford()).join();
            final Town imported =
                    store.inTransaction(t -> t.towns().findByName("ashford")).join().orElseThrow();

            assertThat(store.inTransaction(t -> t.roles().find(
                    net.riftbreaker.rifttowny.domain.org.OrganisationScope.TOWN,
                    imported.id().value())).join())
                    .as("a town with no role book is imported bricked")
                    .isPresent();
        }

        @Test
        @DisplayName("the homeblock arrives as a homeblock")
        void claimKindsSurvive() {
            importer.apply(onlyAshford()).join();

            assertThat(store.inTransaction(t -> t.claims().at(new ChunkKey(WORLD, 0, 0))).join())
                    .get()
                    .extracting(net.riftbreaker.rifttowny.domain.territory.Claim::kind)
                    .isEqualTo(ClaimKind.HOMEBLOCK);
        }

        @Test
        @DisplayName("a nation is built after its towns, with its capital among them")
        void importsANation() {
            final MigrationPlan plan = new MigrationPlan("a fixture",
                    List.of(resident(BEDE, "Bede", "Ashford"), resident(ROWAN, "Rowan", "Highholm")),
                    List.of(town("Ashford", BEDE, "Valen"), town("Highholm", ROWAN, "Valen")),
                    List.of(new MigrationPlan.Nation("Valen", BEDE, "Ashford", "For Valen", "VAL",
                            false, LONG_AGO)),
                    List.of());

            final MigrationReport report = importer.apply(plan).join();

            assertThat(report.nations()).isEqualTo(1);
            final var valen =
                    store.inTransaction(t -> t.nations().findByName("valen")).join().orElseThrow();
            assertThat(valen.townCount()).isEqualTo(2);
            assertThat(store.inTransaction(t -> t.towns().findByName("ashford")).join()
                    .orElseThrow().nation()).contains(valen.id());
        }
    }

    @Nested
    @DisplayName("what it refuses")
    class Refusals {

        @Test
        @DisplayName("a town whose name is already here is skipped, never overwritten")
        void neverOverwrites() {
            // The failure this prevents is somebody's town quietly becoming somebody else's.
            residents.save(Resident.newcomer(ResidentId.of(ROWAN), "Rowan", NOW)).join();
            final Town mine = towns.found(ResidentId.of(ROWAN), "Rowan", "Ashford")
                    .join().value().orElseThrow();

            final MigrationReport report = importer.apply(onlyAshford()).join();

            assertThat(report.towns()).isZero();
            assertThat(report.skipped()).singleElement()
                    .extracting(MigrationReport.Problem::subject).isEqualTo("Ashford");
            assertThat(store.inTransaction(t -> t.towns().findByName("ashford")).join())
                    .get().extracting(Town::id).isEqualTo(mine.id());
        }

        @Test
        @DisplayName("running it twice imports nothing the second time")
        void isIdempotent() {
            // What makes a partial failure survivable: fix the cause and run it again.
            assertThat(importer.apply(onlyAshford()).join().towns()).isEqualTo(1);

            final MigrationReport second = importer.apply(onlyAshford()).join();

            assertThat(second.towns()).isZero();
            assertThat(store.inTransaction(t -> t.towns().all()).join()).hasSize(1);
        }

        @Test
        @DisplayName("a town whose mayor is not in the source cannot be founded")
        void mayorMustExist() {
            final MigrationPlan plan = new MigrationPlan("a fixture",
                    List.of(resident(ADA, "Ada", "Ashford")),
                    List.of(town("Ashford", BEDE, null)),
                    List.of(), List.of());

            final MigrationReport report = importer.apply(plan).join();

            assertThat(report.towns()).isZero();
            assertThat(report.skipped()).singleElement()
                    .extracting(MigrationReport.Problem::detail)
                    .asString().contains("nobody to lead it");
        }

        @Test
        @DisplayName("a claim in a world this server does not have is left behind")
        void unknownWorldsAreSkipped() {
            final MigrationPlan plan = new MigrationPlan("a fixture",
                    List.of(resident(BEDE, "Bede", "Ashford")),
                    List.of(town("Ashford", BEDE, null)),
                    List.of(),
                    List.of(new MigrationPlan.Claim("Ashford", MISSING_WORLD, 0, 0, true, false, null)));

            final MigrationReport report = importer.apply(plan).join();

            assertThat(report.towns()).isEqualTo(1);
            assertThat(report.claims()).isZero();
            assertThat(report.skipped()).singleElement()
                    .extracting(MigrationReport.Problem::detail)
                    .asString().contains("not on this server");
        }

        @Test
        @DisplayName("two towns claiming the same chunk: the first keeps it")
        void overlappingClaimsAreResolved() {
            final MigrationPlan plan = new MigrationPlan("a fixture",
                    List.of(resident(BEDE, "Bede", "Ashford"), resident(ROWAN, "Rowan", "Highholm")),
                    List.of(town("Ashford", BEDE, null), town("Highholm", ROWAN, null)),
                    List.of(),
                    List.of(claim("Ashford", 0, true), claim("Highholm", 0, true)));

            final MigrationReport report = importer.apply(plan).join();

            assertThat(report.claims()).isEqualTo(1);
            assertThat(report.skipped()).singleElement()
                    .extracting(MigrationReport.Problem::detail)
                    .asString().contains("only the first was taken");
        }

        @Test
        @DisplayName("a name this server's policy refuses is reported, not silently renamed")
        void badNamesAreReported() {
            // Renaming automatically would land a town under a name its members do not recognise.
            final MigrationPlan plan = new MigrationPlan("a fixture",
                    List.of(resident(BEDE, "Bede", "Two Words")),
                    List.of(town("Two Words", BEDE, null)),
                    List.of(), List.of());

            final MigrationReport report = importer.apply(plan).join();

            assertThat(report.towns()).isZero();
            assertThat(report.skipped()).singleElement()
                    .extracting(MigrationReport.Problem::subject).isEqualTo("Two Words");
        }

        @Test
        @DisplayName("a nation whose capital was skipped is skipped too")
        void nationsNeedTheirCapital() {
            // Nation.restore refuses a capital that is not one of its towns, so importing it anyway
            // would throw rather than produce a nation.
            final MigrationPlan plan = new MigrationPlan("a fixture",
                    List.of(resident(BEDE, "Bede", "Ashford")),
                    List.of(),
                    List.of(new MigrationPlan.Nation("Valen", BEDE, "Ashford", "", "", false, LONG_AGO)),
                    List.of());

            final MigrationReport report = importer.apply(plan).join();

            assertThat(report.nations()).isZero();
            assertThat(report.skipped()).singleElement()
                    .extracting(MigrationReport.Problem::detail)
                    .asString().contains("was not imported");
        }

        @Test
        @DisplayName("one bad town does not cost the others")
        void oneFailureDoesNotStopTheRun() {
            // Per-town transactions, so a run is not all-or-nothing across a whole server.
            final MigrationPlan plan = new MigrationPlan("a fixture",
                    List.of(resident(BEDE, "Bede", "Ashford"), resident(ROWAN, "Rowan", "Two Words")),
                    List.of(town("Two Words", ROWAN, null), town("Ashford", BEDE, null)),
                    List.of(), List.of());

            final MigrationReport report = importer.apply(plan).join();

            assertThat(report.towns()).isEqualTo(1);
            assertThat(store.inTransaction(t -> t.towns().findByName("ashford")).join()).isPresent();
        }

        @Test
        @DisplayName("the same account twice in the source is reported")
        void duplicateResidentsAreReported() {
            final MigrationPlan plan = new MigrationPlan("a fixture",
                    List.of(resident(BEDE, "Bede", "Ashford"), resident(BEDE, "Bede", "Ashford")),
                    List.of(town("Ashford", BEDE, null)),
                    List.of(), List.of());

            final MigrationReport report = importer.apply(plan).join();

            assertThat(report.residents()).isEqualTo(1);
            assertThat(report.problems()).anyMatch(
                    problem -> problem.detail().contains("appears twice"));
        }

        @Test
        @DisplayName("an empty plan is a clean no-op rather than an error")
        void emptyPlansAreFine() {
            final MigrationReport report = importer.apply(MigrationPlan.empty()).join();

            assertThat(report.isClean()).isTrue();
            assertThat(report.towns()).isZero();
        }
    }

    @Nested
    @DisplayName("the caches afterwards")
    class Caches {

        @Test
        @DisplayName("an imported town is in memory, so its land protects immediately")
        void cachesAreRefreshed() {
            // Without this the town exists in the database and nowhere else, and every block in it
            // reads as an unknown town - which denies everybody, including its own residents.
            importer.apply(onlyAshford()).join();
            final Town imported =
                    store.inTransaction(t -> t.towns().findByName("ashford")).join().orElseThrow();

            assertThat(civicCache.knows(imported.id())).isTrue();
            assertThat(civicCache.townOf(ResidentId.of(ADA))).contains(imported.id());
        }
    }
}
