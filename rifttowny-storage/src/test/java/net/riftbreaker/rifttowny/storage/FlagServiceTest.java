package net.riftbreaker.rifttowny.storage;

import net.riftbreaker.rifttowny.api.ChunkKey;
import net.riftbreaker.rifttowny.domain.civic.CivicCache;
import net.riftbreaker.rifttowny.domain.flag.FlagOverride;
import net.riftbreaker.rifttowny.domain.flag.FlagOverrides;
import net.riftbreaker.rifttowny.domain.flag.FlagSource;
import net.riftbreaker.rifttowny.domain.flag.FlagTarget;
import net.riftbreaker.rifttowny.domain.flag.ProtectionFlag;
import net.riftbreaker.rifttowny.domain.flag.ProtectionQuery;
import net.riftbreaker.rifttowny.domain.flag.Relationship;
import net.riftbreaker.rifttowny.domain.naming.NamePolicy;
import net.riftbreaker.rifttowny.domain.org.ChangeDenial;
import net.riftbreaker.rifttowny.domain.org.Resident;
import net.riftbreaker.rifttowny.domain.org.ResidentId;
import net.riftbreaker.rifttowny.domain.org.Town;
import net.riftbreaker.rifttowny.domain.org.TownId;
import net.riftbreaker.rifttowny.domain.service.CivicCacheService;
import net.riftbreaker.rifttowny.domain.service.FlagService;
import net.riftbreaker.rifttowny.domain.service.TerritoryService;
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
 * Flag persistence against a real database, and — the part that matters — against the resolver that
 * reads it. A stored override that never changes an answer is not a feature.
 */
class FlagServiceTest extends SqliteFixture {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-08-12T09:00:00Z"), ZoneOffset.UTC);

    private static final UUID WORLD = UUID.randomUUID();
    private static final ChunkKey HOME = new ChunkKey(WORLD, 0, 0);
    private static final ChunkKey MARKET = new ChunkKey(WORLD, 1, 0);

    private static final ResidentId MAYOR = ResidentId.of(UUID.randomUUID());
    private static final ResidentId CITIZEN = ResidentId.of(UUID.randomUUID());
    private static final ResidentId STRANGER = ResidentId.of(UUID.randomUUID());

    private final TerritoryIndex index = TerritoryIndex.empty();
    private final CivicCache civicCache = CivicCache.empty();
    private final FlagOverrides overrides = FlagOverrides.empty();

    private JdbcCivicStore store;
    private FlagService flags;
    private TownService towns;
    private TerritoryService territory;
    private CivicCacheService civic;
    private JdbcResidentRepository residents;

    @BeforeEach
    void createServices() {
        store = new JdbcCivicStore(database, DIRECT, CLOCK);
        civic = new CivicCacheService(store, civicCache, warning -> { });
        flags = new FlagService(store, CLOCK, overrides);
        towns = new TownService(store, NamePolicy.defaults(), CLOCK, index, civic, overrides);
        territory = new TerritoryService(store, CLOCK, index);
        residents = new JdbcResidentRepository(database, DIRECT);
    }

    /** Riftholm, with a homeblock and a second chunk, and a resolver reading the live caches. */
    private Town riftholm() {
        final Town town = towns.found(MAYOR, "Mayor", "Riftholm").join().value().orElseThrow();
        territory.claim(MAYOR, town.id(), HOME, ClaimKind.HOMEBLOCK).join();
        territory.claim(MAYOR, town.id(), MARKET, ClaimKind.ORDINARY).join();
        return town;
    }

    private ProtectionQuery query() {
        return new ProtectionQuery(index, civicCache, overrides);
    }

    @Nested
    @DisplayName("changing what a town allows")
    class Changing {

        @Test
        @DisplayName("a town-wide override changes the answer everywhere it owns")
        void townWideOverrideApplies() {
            final Town town = riftholm();
            assertThat(query().mayAct(STRANGER, HOME, ProtectionFlag.BUILD).denied()).isTrue();

            assertThat(flags.setForTown(
                    MAYOR, town.id(), ProtectionFlag.BUILD, Relationship.VISITOR, true)
                    .join().succeeded()).isTrue();

            for (final ChunkKey chunk : List.of(HOME, MARKET)) {
                final var answer = query().mayAct(STRANGER, chunk, ProtectionFlag.BUILD);
                assertThat(answer.allowed()).as("build at %s", chunk).isTrue();
                assertThat(answer.source()).isEqualTo(FlagSource.ORGANISATION);
            }
        }

        @Test
        @DisplayName("a chunk override beats the town-wide one, so one square can differ")
        void claimOverrideBeatsOrganisation() {
            final Town town = riftholm();
            flags.setForTown(MAYOR, town.id(), ProtectionFlag.CONTAINER, Relationship.VISITOR, false)
                    .join();
            flags.setForClaim(MAYOR, town.id(), MARKET, ProtectionFlag.CONTAINER,
                    Relationship.VISITOR, true).join();

            assertThat(query().mayAct(STRANGER, HOME, ProtectionFlag.CONTAINER).denied()).isTrue();
            final var atMarket = query().mayAct(STRANGER, MARKET, ProtectionFlag.CONTAINER);
            assertThat(atMarket.allowed()).isTrue();
            assertThat(atMarket.source()).isEqualTo(FlagSource.CLAIM);
        }

        @Test
        @DisplayName("clearing an override lets the layer below answer again")
        void clearingRestoresTheLayerBelow() {
            final Town town = riftholm();
            flags.setForTown(MAYOR, town.id(), ProtectionFlag.BUILD, Relationship.VISITOR, true)
                    .join();
            assertThat(query().mayAct(STRANGER, HOME, ProtectionFlag.BUILD).allowed()).isTrue();

            assertThat(flags.clearForTown(
                    MAYOR, town.id(), ProtectionFlag.BUILD, Relationship.VISITOR)
                    .join().succeeded()).isTrue();

            final var answer = query().mayAct(STRANGER, HOME, ProtectionFlag.BUILD);
            assertThat(answer.denied()).isTrue();
            assertThat(answer.source()).isEqualTo(FlagSource.BUILT_IN);
        }

        @Test
        @DisplayName("clearing something that was never set is refused, not silently accepted")
        void clearingNothingIsRefused() {
            final Town town = riftholm();

            assertThat(flags.clearForTown(
                    MAYOR, town.id(), ProtectionFlag.PVP, Relationship.VISITOR).join().denial())
                    .contains(ChangeDenial.FLAG_NOT_SET);
        }

        @Test
        @DisplayName("setting the same flag twice replaces the opinion rather than duplicating it")
        void settingTwiceReplaces() {
            final Town town = riftholm();
            flags.setForTown(MAYOR, town.id(), ProtectionFlag.PVP, Relationship.VISITOR, true).join();
            flags.setForTown(MAYOR, town.id(), ProtectionFlag.PVP, Relationship.VISITOR, false)
                    .join();

            final List<FlagOverride> stored =
                    flags.of(FlagTarget.organisation(town.id())).join();

            assertThat(stored).hasSize(1);
            assertThat(stored.getFirst().allowed()).isFalse();
        }

        @Test
        @DisplayName("a world flag can be opened, and the world does not care who is standing there")
        void worldFlagsCanBeOpened() {
            final Town town = riftholm();
            assertThat(query().mayAct(null, HOME, ProtectionFlag.EXPLOSIONS).denied()).isTrue();

            // World flags resolve at wilderness whatever the actor's standing, so that is the row a
            // town has to set. Setting it for VISITOR would change nothing, which is worth knowing.
            flags.setForTown(MAYOR, town.id(), ProtectionFlag.EXPLOSIONS, Relationship.VISITOR, true)
                    .join();
            assertThat(query().mayAct(null, HOME, ProtectionFlag.EXPLOSIONS).denied()).isTrue();

            flags.setForTown(
                    MAYOR, town.id(), ProtectionFlag.EXPLOSIONS, Relationship.WILDERNESS, true)
                    .join();
            assertThat(query().mayAct(null, HOME, ProtectionFlag.EXPLOSIONS).allowed()).isTrue();
        }
    }

    @Nested
    @DisplayName("authority")
    class Authority {

        @Test
        @DisplayName("someone without MANAGE_FLAGS cannot change anything")
        void permissionIsRequired() {
            final Town town = riftholm();
            residents.save(Resident.newcomer(CITIZEN, "Citizen", CLOCK.instant())).join();
            towns.join(MAYOR, CITIZEN, town.id()).join();

            assertThat(flags.setForTown(
                    CITIZEN, town.id(), ProtectionFlag.BUILD, Relationship.VISITOR, true)
                    .join().denial())
                    .contains(ChangeDenial.MISSING_PERMISSION);
            assertThat(flags.of(FlagTarget.organisation(town.id())).join()).isEmpty();
        }

        @Test
        @DisplayName("a mayor cannot set a flag on another town's chunk")
        void cannotReachAnotherTownsLand() {
            final Town riftholm = riftholm();
            residents.save(Resident.newcomer(STRANGER, "Stranger", CLOCK.instant())).join();
            final Town ashford =
                    towns.found(STRANGER, "Stranger", "Ashford").join().value().orElseThrow();

            assertThat(flags.setForClaim(
                    STRANGER, ashford.id(), HOME, ProtectionFlag.BUILD, Relationship.VISITOR, true)
                    .join().denial())
                    .as("the resolver reads a chunk's overrides without asking who wrote them")
                    .contains(ChangeDenial.FLAG_TARGET_NOT_YOURS);
            assertThat(query().mayAct(STRANGER, HOME, ProtectionFlag.BUILD).denied()).isTrue();
        }

        @Test
        @DisplayName("a flag cannot be set on unclaimed land")
        void cannotSetOnWilderness() {
            final Town town = riftholm();

            assertThat(flags.setForClaim(MAYOR, town.id(), new ChunkKey(WORLD, 40, 40),
                    ProtectionFlag.BUILD, Relationship.VISITOR, true).join().denial())
                    .contains(ChangeDenial.CHUNK_NOT_CLAIMED);
        }

        @Test
        @DisplayName("an administrative override beats everything a town says")
        void adminBeatsTheTown() {
            final Town town = riftholm();
            flags.setForTown(MAYOR, town.id(), ProtectionFlag.BUILD, Relationship.VISITOR, true)
                    .join();

            flags.setAdministrative(FlagTarget.admin(), ProtectionFlag.BUILD,
                    Relationship.VISITOR, false, null).join();

            final var answer = query().mayAct(STRANGER, HOME, ProtectionFlag.BUILD);
            assertThat(answer.denied()).isTrue();
            assertThat(answer.source()).isEqualTo(FlagSource.ADMIN);
        }

        @Test
        @DisplayName("a town-scoped target cannot be set through the administrative door")
        void administrativeDoorRefusesTownTargets() {
            final Town town = riftholm();

            assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
                    flags.setAdministrative(FlagTarget.organisation(town.id()),
                            ProtectionFlag.BUILD, Relationship.VISITOR, true, null)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("loading and sweeping")
    class LoadingAndSweeping {

        @Test
        @DisplayName("overrides survive a restart")
        void overridesReload() {
            final Town town = riftholm();
            flags.setForTown(MAYOR, town.id(), ProtectionFlag.BUILD, Relationship.VISITOR, true)
                    .join();
            flags.setForClaim(MAYOR, town.id(), MARKET, ProtectionFlag.PVP, Relationship.TOWN, true)
                    .join();

            final FlagOverrides reloaded = FlagOverrides.empty();
            assertThat(new FlagService(store, CLOCK, reloaded).loadAll().join()).isEqualTo(2);

            assertThat(new ProtectionQuery(index, civicCache, reloaded)
                    .mayAct(STRANGER, HOME, ProtectionFlag.BUILD).allowed()).isTrue();
        }

        @Test
        @DisplayName("a disbanded town's overrides go with it, town-wide and per chunk")
        void disbandSweepsOverrides() {
            final Town town = riftholm();
            flags.setForTown(MAYOR, town.id(), ProtectionFlag.BUILD, Relationship.VISITOR, true)
                    .join();
            flags.setForClaim(MAYOR, town.id(), MARKET, ProtectionFlag.CONTAINER,
                    Relationship.VISITOR, true).join();
            assertThat(overrides.size()).isEqualTo(2);

            towns.disband(MAYOR, town.id()).join();

            assertThat(overrides.size())
                    .as("an override left behind returns the moment somebody reclaims the chunk")
                    .isZero();
            assertThat(flags.of(FlagTarget.claim(MARKET)).join()).isEmpty();
            assertThat(flags.of(FlagTarget.organisation(town.id())).join()).isEmpty();
            // And the resolver agrees: the land is wilderness, judged by wilderness defaults.
            assertThat(new ProtectionQuery(index, civicCache, overrides)
                    .mayAct(STRANGER, MARKET, ProtectionFlag.CONTAINER).allowed()).isTrue();
        }

        @Test
        @DisplayName("an unreadable row is skipped rather than taking the whole set with it")
        void unreadableRowsAreSkipped() {
            final Town town = riftholm();
            flags.setForTown(MAYOR, town.id(), ProtectionFlag.BUILD, Relationship.VISITOR, true)
                    .join();
            writeRawOverride("ORGANISATION", town.id().value().toString(),
                    "FLAG_FROM_THE_FUTURE", "VISITOR");
            writeRawOverride("ORGANISATION", town.id().value().toString(),
                    "BUILD", "RELATIONSHIP_FROM_THE_FUTURE");

            final FlagOverrides reloaded = FlagOverrides.empty();
            assertThat(new FlagService(store, CLOCK, reloaded).loadAll().join()).isEqualTo(1);

            assertThat(new ProtectionQuery(index, civicCache, reloaded)
                    .mayAct(STRANGER, HOME, ProtectionFlag.BUILD).allowed()).isTrue();
        }
    }

    /** Writes a row the domain would never write, for the unreadable-row case. */
    private void writeRawOverride(
            final String scope, final String target, final String flag, final String relationship) {
        store.inTransaction(transaction -> null).join();
        try (java.sql.Connection connection = database.connection();
             java.sql.PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO rt_flag_override "
                             + "(scope, target, flag, relationship, allowed, set_by, set_at) "
                             + "VALUES (?, ?, ?, ?, 1, NULL, 0)")) {
            statement.setString(1, scope);
            statement.setString(2, target);
            statement.setString(3, flag);
            statement.setString(4, relationship);
            statement.executeUpdate();
        } catch (final java.sql.SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
