package net.riftbreaker.rifttowny.storage;

import net.riftbreaker.rifttowny.api.ChunkKey;
import net.riftbreaker.rifttowny.domain.civic.CivicCache;
import net.riftbreaker.rifttowny.domain.flag.FlagOverrides;
import net.riftbreaker.rifttowny.domain.flag.ProtectionFlag;
import net.riftbreaker.rifttowny.domain.flag.ProtectionQuery;
import net.riftbreaker.rifttowny.domain.naming.NamePolicy;
import net.riftbreaker.rifttowny.domain.org.ChangeDenial;
import net.riftbreaker.rifttowny.domain.org.Resident;
import net.riftbreaker.rifttowny.domain.org.ResidentId;
import net.riftbreaker.rifttowny.domain.org.Town;
import net.riftbreaker.rifttowny.domain.service.CivicCacheService;
import net.riftbreaker.rifttowny.domain.service.RuinService;
import net.riftbreaker.rifttowny.domain.service.TerritoryService;
import net.riftbreaker.rifttowny.domain.service.TownService;
import net.riftbreaker.rifttowny.domain.territory.ClaimKind;
import net.riftbreaker.rifttowny.domain.territory.Ruin;
import net.riftbreaker.rifttowny.domain.territory.RuinIndex;
import net.riftbreaker.rifttowny.domain.territory.TerritoryIndex;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The ruin lifecycle: a town falls, its land is held, and it is either taken on or let go.
 *
 * <p>Asserted against the resolver rather than only against the tables, because the whole feature is
 * a claim about what a player standing there may do. A ruin row that changed no answer would be
 * bookkeeping.
 */
class RuinServiceTest extends SqliteFixture {

    private static final Instant NOW = Instant.parse("2026-08-12T09:00:00Z");
    private static final Duration LIFETIME = Duration.ofDays(3);
    private static final Duration RECLAIM_DELAY = Duration.ofDays(1);
    /** After the delay, before the window closes. */
    private static final Instant OPEN = NOW.plus(RECLAIM_DELAY).plusSeconds(1);

    private static final UUID WORLD = UUID.randomUUID();
    private static final ChunkKey HOME = new ChunkKey(WORLD, 0, 0);
    private static final ChunkKey MARKET = new ChunkKey(WORLD, 1, 0);
    private static final ChunkKey WILD = new ChunkKey(WORLD, 60, 60);

    private static final ResidentId MAYOR = ResidentId.of(UUID.randomUUID());
    private static final ResidentId WANDERER = ResidentId.of(UUID.randomUUID());

    private final TerritoryIndex index = TerritoryIndex.empty();
    private final RuinIndex ruinIndex = RuinIndex.empty();
    private final CivicCache civicCache = CivicCache.empty();
    private final FlagOverrides overrides = FlagOverrides.empty();

    private JdbcCivicStore store;
    private CivicCacheService civic;
    private TownService towns;
    private TerritoryService territory;
    private RuinService ruins;
    private JdbcResidentRepository residents;

    @BeforeEach
    void createServices() {
        store = new JdbcCivicStore(database, DIRECT, at(NOW));
        civic = new CivicCacheService(store, civicCache, warning -> { });
        towns = servicesAt(NOW).towns();
        territory = new TerritoryService(store, at(NOW), index);
        ruins = servicesAt(NOW).ruins();
        residents = new JdbcResidentRepository(database, DIRECT);
    }

    private static Clock at(final Instant when) {
        return Clock.fixed(when, ZoneOffset.UTC);
    }

    /** The two services that need a clock, built together so a test can move time. */
    private Services servicesAt(final Instant when) {
        final Clock clock = at(when);
        return new Services(
                new TownService(store, NamePolicy.defaults(), clock, index, civic, overrides,
                        ruinIndex, RECLAIM_DELAY, LIFETIME),
                new RuinService(store, NamePolicy.defaults(), clock, index, ruinIndex, civic,
                        LIFETIME));
    }

    private record Services(TownService towns, RuinService ruins) {
    }

    private ProtectionQuery query() {
        return new ProtectionQuery(index, civicCache, overrides, ruinIndex);
    }

    /** Riftholm, two chunks, then disbanded. */
    private Town fallenRiftholm() {
        residents.save(Resident.newcomer(MAYOR, "Mayor", NOW)).join();
        final Town town = towns.found(MAYOR, "Mayor", "Riftholm").join().value().orElseThrow();
        territory.claim(MAYOR, town.id(), HOME, ClaimKind.HOMEBLOCK).join();
        territory.claim(MAYOR, town.id(), MARKET, ClaimKind.ORDINARY).join();
        towns.disband(MAYOR, town.id()).join();
        return town;
    }

    @Nested
    @DisplayName("a town falls")
    class Falling {

        @Test
        @DisplayName("a disbanded town leaves a ruin holding every chunk it had")
        void disbandLeavesARuin() {
            final Town town = fallenRiftholm();

            final Optional<Ruin> ruin = ruins.at(HOME);
            assertThat(ruin).isPresent();
            assertThat(ruin.get().formerTown()).isEqualTo(town.id());
            assertThat(ruin.get().name().display()).isEqualTo("Riftholm");
            assertThat(ruinIndex.claimedChunks()).isEqualTo(2);
            assertThat(ruins.at(MARKET)).contains(ruin.get());
            assertThat(ruins.at(WILD)).isEmpty();
        }

        @Test
        @DisplayName("the claims are gone, so the town owns nothing while its ruin stands")
        void claimsAreReleased() {
            fallenRiftholm();

            assertThat(index.size()).isZero();
            assertThat(query().ownerAt(HOME)).isEmpty();
        }

        @Test
        @DisplayName("a town with no land leaves no ruin, because there is nothing to hold")
        void landlessTownsLeaveNothing() {
            residents.save(Resident.newcomer(MAYOR, "Mayor", NOW)).join();
            final Town town = towns.found(MAYOR, "Mayor", "Riftholm").join().value().orElseThrow();

            towns.disband(MAYOR, town.id()).join();

            assertThat(ruinIndex.size()).isZero();
        }

        @Test
        @DisplayName("with ruins switched off the land goes straight back to wilderness")
        void ruinsCanBeDisabled() {
            final RuinIndex unused = RuinIndex.empty();
            final TownService noRuins = new TownService(
                    store, NamePolicy.defaults(), at(NOW), index, civic, overrides,
                    unused, Duration.ZERO, Duration.ZERO);
            residents.save(Resident.newcomer(MAYOR, "Mayor", NOW)).join();
            final Town town = noRuins.found(MAYOR, "Mayor", "Riftholm").join().value().orElseThrow();
            territory.claim(MAYOR, town.id(), HOME, ClaimKind.HOMEBLOCK).join();

            noRuins.disband(MAYOR, town.id()).join();

            assertThat(unused.size()).isZero();
            assertThat(query().mayAct(WANDERER, HOME, ProtectionFlag.BREAK).allowed())
                    .as("wilderness, not a ruin")
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("what a ruin allows")
    class Protection {

        @Test
        @DisplayName("a ruin is not protected: it can be looted and pulled apart")
        void ruinsAreOpen() {
            fallenRiftholm();

            for (final ProtectionFlag flag : java.util.List.of(
                    ProtectionFlag.BUILD, ProtectionFlag.BREAK, ProtectionFlag.CONTAINER,
                    ProtectionFlag.INTERACT, ProtectionFlag.PVP)) {
                assertThat(query().mayAct(WANDERER, HOME, flag).allowed())
                        .as("%s in a ruin: a fallen town is picked over, not sealed", flag)
                        .isTrue();
            }
        }

        @Test
        @DisplayName("the world takes it apart too: it burns and it blows up")
        void theWorldIsNotHeldBack() {
            fallenRiftholm();

            for (final ProtectionFlag flag : java.util.List.of(
                    ProtectionFlag.EXPLOSIONS, ProtectionFlag.FIRE_SPREAD,
                    ProtectionFlag.FLUID_FLOW, ProtectionFlag.PISTONS)) {
                assertThat(query().mayAct(null, HOME, flag).allowed())
                        .as("%s in a ruin", flag)
                        .isTrue();
            }
        }

        @Test
        @DisplayName("a subsystem action is still refused, in a ruin as anywhere else")
        void subsystemActionsStillNeedTheirLayer() {
            fallenRiftholm();

            assertThat(query().mayAct(WANDERER, HOME, ProtectionFlag.WAR_ACTION).denied()).isTrue();
            assertThat(query().mayAct(WANDERER, HOME, ProtectionFlag.EVENT_ACTION).denied())
                    .isTrue();
        }

        @Test
        @DisplayName("it is still reported as a ruin, not as wilderness")
        void itIsStillARuin() {
            fallenRiftholm();

            final var answer = query().mayAct(WANDERER, HOME, ProtectionFlag.CONTAINER);
            assertThat(answer.isRuin()).isTrue();
            assertThat(answer.explain()).contains("in the ruins of Riftholm");
            assertThat(query().mayAct(WANDERER, WILD, ProtectionFlag.CONTAINER).isRuin()).isFalse();
        }

        @Test
        @DisplayName("the mayor who disbanded it gets no special standing")
        void theFormerMayorIsNobody() {
            fallenRiftholm();

            assertThat(query().relationshipAt(MAYOR, HOME))
                    .isEqualTo(net.riftbreaker.rifttowny.domain.flag.Relationship.VISITOR);
        }

        @Test
        @DisplayName("an administrator can still shut a ruin down")
        void adminLayerStillApplies() {
            fallenRiftholm();
            overrides.apply(net.riftbreaker.rifttowny.domain.flag.FlagOverride.of(
                    net.riftbreaker.rifttowny.domain.flag.FlagTarget.admin(),
                    ProtectionFlag.CONTAINER,
                    net.riftbreaker.rifttowny.domain.flag.Relationship.VISITOR,
                    false, null, NOW));

            assertThat(query().mayAct(WANDERER, HOME, ProtectionFlag.CONTAINER).denied())
                    .as("a server that does not want looting must be able to say so")
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("rebuilding a fallen town")
    class Reclaiming {

        /** The reclaim services, at a moment when the ruin is open. */
        private RuinService open() {
            residents.save(Resident.newcomer(WANDERER, "Wanderer", NOW)).join();
            return servicesAt(OPEN).ruins();
        }

        @Test
        @DisplayName("the town comes back as itself, with the reclaimer as mayor")
        void theTownReturns() {
            final Town fallen = fallenRiftholm();

            final Town restored = open().reclaim(WANDERER, "Wanderer", HOME)
                    .join().value().orElseThrow();

            assertThat(restored.name().display())
                    .as("a restoration, not a land sale")
                    .isEqualTo("Riftholm");
            assertThat(restored.id())
                    .as("the same town, so everything ever written about it still points at it")
                    .isEqualTo(fallen.id());
            assertThat(restored.mayor()).isEqualTo(WANDERER);
        }

        @Test
        @DisplayName("it takes back every chunk the ruin still held")
        void theLandReturns() {
            fallenRiftholm();

            final Town restored = open().reclaim(WANDERER, "Wanderer", MARKET)
                    .join().value().orElseThrow();

            assertThat(index.ownerOf(HOME)).contains(restored.id());
            assertThat(index.ownerOf(MARKET))
                    .as("the whole ruin, not only the chunk they stood in")
                    .contains(restored.id());
            assertThat(ruinIndex.size()).isZero();
        }

        @Test
        @DisplayName("the homeblock returns to where it was, not to where the reclaimer stood")
        void theHomeblockReturns() {
            fallenRiftholm();

            open().reclaim(WANDERER, "Wanderer", MARKET).join();

            assertThat(store.inTransaction(t -> t.claims().at(HOME).orElseThrow().kind()).join())
                    .as("the town is rebuilt where it was, not where somebody happened to stand")
                    .isEqualTo(ClaimKind.HOMEBLOCK);
            assertThat(store.inTransaction(t -> t.claims().at(MARKET).orElseThrow().kind()).join())
                    .isEqualTo(ClaimKind.ORDINARY);
        }

        @Test
        @DisplayName("its population does not return: no residents, no roles from before")
        void thePopulationDoesNot() {
            fallenRiftholm();

            final Town restored = open().reclaim(WANDERER, "Wanderer", HOME)
                    .join().value().orElseThrow();

            assertThat(restored.residents())
                    .as("handing a stranger the old membership list would be a different feature")
                    .containsExactly(WANDERER);
            assertThat(store.inTransaction(t -> t.residents().find(MAYOR).orElseThrow().town())
                    .join()).isEmpty();
        }

        @Test
        @DisplayName("the rebuilt town protects its land immediately")
        void protectionFollows() {
            fallenRiftholm();

            open().reclaim(WANDERER, "Wanderer", HOME).join();

            assertThat(query().mayAct(WANDERER, HOME, ProtectionFlag.BUILD).allowed())
                    .as("the reclaimer is its mayor")
                    .isTrue();
            assertThat(query().mayAct(MAYOR, HOME, ProtectionFlag.BUILD).denied())
                    .as("and the ruin's open season is over")
                    .isTrue();
        }

        @Test
        @DisplayName("a ruin that is too fresh cannot be rebuilt yet")
        void tooFreshToRebuild() {
            fallenRiftholm();
            residents.save(Resident.newcomer(WANDERER, "Wanderer", NOW)).join();

            assertThat(ruins.reclaim(WANDERER, "Wanderer", HOME).join().denial())
                    .as("otherwise a mayor disbands and re-founds, shedding everything but the land")
                    .contains(ChangeDenial.RUIN_NOT_YET_RECLAIMABLE);
            // Still a ruin, still open to be picked over in the meantime.
            assertThat(query().mayAct(WANDERER, HOME, ProtectionFlag.CONTAINER).allowed()).isTrue();
        }

        @Test
        @DisplayName("the ruin is kept as the record of the fall it recovered from")
        void theRecordSurvives() {
            final Town fallen = fallenRiftholm();

            final Town restored = open().reclaim(WANDERER, "Wanderer", HOME)
                    .join().value().orElseThrow();

            final Ruin record = storedRuinOf(fallen);
            assertThat(record.isReclaimed()).isTrue();
            assertThat(record.successor()).contains(restored.id());
            assertThat(record.reclaimedBy()).isEqualTo(WANDERER);
        }

        @Test
        @DisplayName("somebody who already has a town cannot take one on")
        void oneTownPerResident() {
            fallenRiftholm();
            residents.save(Resident.newcomer(WANDERER, "Wanderer", NOW)).join();
            towns.found(WANDERER, "Wanderer", "Ashford").join();

            assertThat(servicesAt(OPEN).ruins().reclaim(WANDERER, "Wanderer", HOME).join().denial())
                    .contains(ChangeDenial.ALREADY_IN_ANOTHER_TOWN);
        }

        @Test
        @DisplayName("there is nothing to rebuild in wilderness")
        void wildernessIsNotARuin() {
            fallenRiftholm();

            assertThat(open().reclaim(WANDERER, "Wanderer", WILD).join().denial())
                    .contains(ChangeDenial.NOT_A_RUIN);
        }

        @Test
        @DisplayName("a rebuilt town can fall again, leaving a second ruin")
        void aTownCanFallTwice() {
            final Town fallen = fallenRiftholm();
            final Town restored = open().reclaim(WANDERER, "Wanderer", HOME)
                    .join().value().orElseThrow();

            // Same id as the first fall. Keyed on it, one fall per town would have been a
            // constraint violation here rather than a second ruin.
            assertThat(servicesAt(OPEN).towns().disband(WANDERER, restored.id()).join().succeeded())
                    .isTrue();

            assertThat(ruins.at(HOME)).isPresent();
            assertThat(countRuinsOf(fallen))
                    .as("two falls are two ruins, and the first one is still the record of it")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("a town that took the fallen one's name in the meantime blocks the rebuild")
        void nameTakenSinceTheFall() {
            fallenRiftholm();
            residents.save(Resident.newcomer(WANDERER, "Wanderer", NOW)).join();
            final ResidentId other = ResidentId.of(UUID.randomUUID());
            residents.save(Resident.newcomer(other, "Other", NOW)).join();
            towns.found(other, "Other", "Riftholm").join();

            assertThat(servicesAt(OPEN).ruins().reclaim(WANDERER, "Wanderer", HOME).join().denial())
                    .contains(ChangeDenial.NAME_TAKEN);
        }
    }

    @Nested
    @DisplayName("letting go")
    class Lapsing {

        @Test
        @DisplayName("a ruin past its window releases its land to the sweep")
        void sweepReleasesLand() {
            fallenRiftholm();
            final RuinService later = servicesAt(NOW.plus(LIFETIME).plusSeconds(1)).ruins();

            assertThat(later.sweepLapsed().join()).isEqualTo(1);

            assertThat(ruinIndex.size()).isZero();
            assertThat(ruinIndex.claimedChunks()).isZero();
            assertThat(query().mayAct(WANDERER, HOME, ProtectionFlag.BREAK).isRuin())
                    .as("wilderness again, not a ruin")
                    .isFalse();
        }

        @Test
        @DisplayName("a ruin inside its window is left alone")
        void sweepSparesStandingRuins() {
            fallenRiftholm();

            assertThat(ruins.sweepLapsed().join()).isZero();
            assertThat(ruinIndex.claimedChunks()).isEqualTo(2);
        }

        @Test
        @DisplayName("the row survives the sweep, because it is the record of what stood there")
        void theRecordSurvivesTheSweep() {
            final Town fallen = fallenRiftholm();
            servicesAt(NOW.plus(LIFETIME).plusSeconds(1)).ruins().sweepLapsed().join();

            assertThat(storedRuinOf(fallen).name().display()).isEqualTo("Riftholm");
        }

        @Test
        @DisplayName("a lapsed ruin cannot be rebuilt, and says so distinctly")
        void lapsedRuinsRefuseReclaim() {
            fallenRiftholm();
            residents.save(Resident.newcomer(WANDERER, "Wanderer", NOW)).join();
            final RuinService later = servicesAt(NOW.plus(LIFETIME).plusSeconds(1)).ruins();

            assertThat(later.reclaim(WANDERER, "Wanderer", HOME).join().denial())
                    .as("'there is no ruin here' would read as a bug to somebody standing in one")
                    .contains(ChangeDenial.RUIN_HAS_LAPSED);
        }
    }

    @Test
    @DisplayName("standing ruins survive a restart")
    void ruinsReload() {
        fallenRiftholm();
        final RuinIndex reloaded = RuinIndex.empty();

        assertThat(new RuinService(store, NamePolicy.defaults(), at(NOW), index, reloaded, civic,
                LIFETIME).loadIndex().join()).isEqualTo(1);

        assertThat(reloaded.claimedChunks()).isEqualTo(2);
        assertThat(reloaded.at(HOME).map(ruin -> ruin.name().display())).contains("Riftholm");
    }

    /**
     * The ruin row for a fallen town, found by the town it replaced.
     *
     * <p>Read by former town rather than by chunk because these assertions run after the land has
     * gone — which is the point being made: the row outlives the ground.</p>
     */
    private int countRuinsOf(final Town fallen) {
        try (java.sql.Connection connection = database.connection();
             java.sql.PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM rt_ruin WHERE former_town_id = ?")) {
            statement.setString(1, fallen.id().value().toString());
            try (java.sql.ResultSet results = statement.executeQuery()) {
                assertThat(results.next()).isTrue();
                return results.getInt(1);
            }
        } catch (final java.sql.SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private Ruin storedRuinOf(final Town fallen) {
        try (java.sql.Connection connection = database.connection();
             java.sql.PreparedStatement statement = connection.prepareStatement(
                     "SELECT ruin_id FROM rt_ruin WHERE former_town_id = ? "
                             + "ORDER BY ruined_at DESC")) {
            statement.setString(1, fallen.id().value().toString());
            try (java.sql.ResultSet results = statement.executeQuery()) {
                assertThat(results.next()).isTrue();
                final UUID ruinId = UUID.fromString(results.getString(1));
                return store.inTransaction(t -> t.ruins().find(ruinId).orElseThrow()).join();
            }
        } catch (final java.sql.SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
