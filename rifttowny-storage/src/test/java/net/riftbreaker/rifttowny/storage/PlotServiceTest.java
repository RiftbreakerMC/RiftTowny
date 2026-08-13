package net.riftbreaker.rifttowny.storage;

import net.riftbreaker.rifttowny.api.ChunkKey;
import net.riftbreaker.rifttowny.domain.civic.CivicCache;
import net.riftbreaker.rifttowny.domain.flag.FlagOverrides;
import net.riftbreaker.rifttowny.domain.flag.ProtectionFlag;
import net.riftbreaker.rifttowny.domain.flag.ProtectionQuery;
import net.riftbreaker.rifttowny.domain.flag.Relationship;
import net.riftbreaker.rifttowny.domain.naming.NamePolicy;
import net.riftbreaker.rifttowny.domain.org.ChangeDenial;
import net.riftbreaker.rifttowny.domain.org.OrganisationScope;
import net.riftbreaker.rifttowny.domain.org.Resident;
import net.riftbreaker.rifttowny.domain.org.ResidentId;
import net.riftbreaker.rifttowny.domain.org.Town;
import net.riftbreaker.rifttowny.domain.role.Permission;
import net.riftbreaker.rifttowny.domain.role.RoleBook;
import net.riftbreaker.rifttowny.domain.role.RoleId;
import net.riftbreaker.rifttowny.domain.role.SystemRole;
import net.riftbreaker.rifttowny.domain.service.CivicCacheService;
import net.riftbreaker.rifttowny.domain.service.PlotService;
import net.riftbreaker.rifttowny.domain.service.TerritoryService;
import net.riftbreaker.rifttowny.domain.service.TownService;
import net.riftbreaker.rifttowny.domain.territory.ClaimKind;
import net.riftbreaker.rifttowny.domain.territory.PlotType;
import net.riftbreaker.rifttowny.domain.territory.TerritoryIndex;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plots, and the rung of the relationship ladder they exist to reach.
 *
 * <p>{@code Relationship.RESIDENT} has been in the ladder since it was written and has never been
 * reachable — it is documented as "the resident who owns this particular plot". Most of what is
 * asserted here is that holding a plot now reaches it, because a plot that changed no protection
 * answer would be a label.
 */
class PlotServiceTest extends SqliteFixture {

    private static final Instant NOW = Instant.parse("2026-08-13T09:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static final UUID WORLD = UUID.randomUUID();
    private static final ChunkKey HOME = new ChunkKey(WORLD, 0, 0);
    private static final ChunkKey MARKET = new ChunkKey(WORLD, 1, 0);
    private static final ChunkKey WILD = new ChunkKey(WORLD, 50, 50);

    private static final ResidentId MAYOR = ResidentId.of(UUID.randomUUID());
    private static final ResidentId CITIZEN = ResidentId.of(UUID.randomUUID());
    private static final ResidentId NEIGHBOUR = ResidentId.of(UUID.randomUUID());
    private static final ResidentId STRANGER = ResidentId.of(UUID.randomUUID());

    private final TerritoryIndex index = TerritoryIndex.empty();
    private final CivicCache civicCache = CivicCache.empty();
    private final FlagOverrides overrides = FlagOverrides.empty();

    private JdbcCivicStore store;
    private PlotService plots;
    private TownService towns;
    private TerritoryService territory;
    private JdbcResidentRepository residents;

    @BeforeEach
    void createServices() {
        store = new JdbcCivicStore(database, DIRECT, CLOCK);
        towns = new TownService(store, NamePolicy.defaults(), CLOCK, index,
                new CivicCacheService(store, civicCache, warning -> { }));
        territory = new TerritoryService(store, CLOCK, index);
        plots = new PlotService(store, CLOCK, index);
        residents = new JdbcResidentRepository(database, DIRECT);
    }

    /** Riftholm with two chunks, a citizen and a neighbour. */
    private Town riftholm() {
        residents.save(Resident.newcomer(MAYOR, "Mayor", NOW)).join();
        residents.save(Resident.newcomer(CITIZEN, "Citizen", NOW)).join();
        residents.save(Resident.newcomer(NEIGHBOUR, "Neighbour", NOW)).join();
        final Town town = towns.found(MAYOR, "Mayor", "Riftholm").join().value().orElseThrow();
        territory.claim(MAYOR, town.id(), HOME, ClaimKind.HOMEBLOCK).join();
        territory.claim(MAYOR, town.id(), MARKET, ClaimKind.ORDINARY).join();
        towns.join(MAYOR, CITIZEN, town.id()).join();
        towns.join(MAYOR, NEIGHBOUR, town.id()).join();
        return town;
    }

    private ProtectionQuery query() {
        return new ProtectionQuery(index, civicCache, overrides);
    }

    @Nested
    @DisplayName("holding one")
    class Holding {

        @Test
        @DisplayName("a resident takes an unheld plot in their own town")
        void taking() {
            riftholm();

            final var plot = plots.take(CITIZEN, MARKET).join().value().orElseThrow();

            assertThat(plot.isHeldBy(CITIZEN)).isTrue();
            assertThat(index.at(MARKET).orElseThrow().isHeldBy(CITIZEN))
                    .as("the index is what protection reads, so it has to follow")
                    .isTrue();
        }

        @Test
        @DisplayName("somebody else's plot cannot be taken")
        void takingAHeldPlot() {
            riftholm();
            plots.take(CITIZEN, MARKET).join();

            assertThat(plots.take(NEIGHBOUR, MARKET).join().denial())
                    .contains(ChangeDenial.PLOT_ALREADY_HELD);
        }

        @Test
        @DisplayName("taking one you already hold says so rather than doing nothing")
        void takingYourOwn() {
            riftholm();
            plots.take(CITIZEN, MARKET).join();

            assertThat(plots.take(CITIZEN, MARKET).join().denial())
                    .contains(ChangeDenial.ALREADY_HOLD_THIS_PLOT);
        }

        @Test
        @DisplayName("a non-resident cannot hold a plot in the town")
        void outsidersCannotHold() {
            riftholm();
            residents.save(Resident.newcomer(STRANGER, "Stranger", NOW)).join();

            assertThat(plots.take(STRANGER, MARKET).join().denial())
                    .contains(ChangeDenial.NOT_A_RESIDENT_OF_THIS_TOWN);
        }

        @Test
        @DisplayName("wilderness is not a plot")
        void wildernessIsNotAPlot() {
            riftholm();

            assertThat(plots.take(CITIZEN, WILD).join().denial())
                    .contains(ChangeDenial.CHUNK_NOT_CLAIMED);
        }

        @Test
        @DisplayName("a holder gives their plot back")
        void releasing() {
            riftholm();
            plots.take(CITIZEN, MARKET).join();

            assertThat(plots.release(CITIZEN, MARKET).join().succeeded()).isTrue();

            assertThat(index.at(MARKET).orElseThrow().isHeld()).isFalse();
            assertThat(plots.release(CITIZEN, MARKET).join().denial())
                    .contains(ChangeDenial.PLOT_NOT_HELD);
        }

        @Test
        @DisplayName("a neighbour cannot take somebody's plot away, but the town can")
        void reclaimingSomebodyElses() {
            riftholm();
            plots.take(CITIZEN, MARKET).join();

            assertThat(plots.release(NEIGHBOUR, MARKET).join().denial())
                    .contains(ChangeDenial.MISSING_PERMISSION);
            assertThat(plots.release(MAYOR, MARKET).join().succeeded())
                    .as("the mayor holds MANAGE_PLOTS, which is how a town reclaims a lapsed plot")
                    .isTrue();
        }

        @Test
        @DisplayName("leaving the town gives every plot back")
        void leavingReleasesPlots() {
            final Town town = riftholm();
            plots.take(CITIZEN, MARKET).join();
            plots.take(CITIZEN, HOME).join();

            towns.leave(CITIZEN, town.id()).join();

            assertThat(index.at(MARKET).orElseThrow().isHeld())
                    .as("a plot is authority over a square inside a town they have left")
                    .isFalse();
            assertThat(index.at(HOME).orElseThrow().isHeld()).isFalse();
            assertThat(plots.heldBy(CITIZEN).join()).isEmpty();
        }

        @Test
        @DisplayName("one resident leaving does not disturb another's plots")
        void leavingSparesOthers() {
            final Town town = riftholm();
            plots.take(CITIZEN, MARKET).join();
            plots.take(NEIGHBOUR, HOME).join();

            towns.leave(CITIZEN, town.id()).join();

            assertThat(index.at(HOME).orElseThrow().isHeldBy(NEIGHBOUR)).isTrue();
        }
    }

    @Nested
    @DisplayName("what a plot is for")
    class Types {

        @Test
        @DisplayName("a holder says what their own plot is for")
        void settingYourOwn() {
            riftholm();
            plots.take(CITIZEN, MARKET).join();

            assertThat(plots.setType(CITIZEN, MARKET, PlotType.SHOP).join()
                    .value().orElseThrow().type()).isEqualTo(PlotType.SHOP);
            assertThat(index.at(MARKET).orElseThrow().type()).isEqualTo(PlotType.SHOP);
        }

        @Test
        @DisplayName("somebody else's needs MANAGE_PLOTS")
        void settingSomebodyElses() {
            riftholm();
            plots.take(CITIZEN, MARKET).join();

            assertThat(plots.setType(NEIGHBOUR, MARKET, PlotType.SHOP).join().denial())
                    .contains(ChangeDenial.MISSING_PERMISSION);
            assertThat(plots.setType(MAYOR, MARKET, PlotType.SHOP).join().succeeded()).isTrue();
        }

        @Test
        @DisplayName("setting the type it already has says so")
        void settingTheSameType() {
            riftholm();

            assertThat(plots.setType(MAYOR, MARKET, PlotType.DEFAULT).join().denial())
                    .contains(ChangeDenial.PLOT_ALREADY_THAT_TYPE);
        }

        @Test
        @DisplayName("a plot type never changes the town's shape")
        void typesDoNotTouchTheKind() {
            riftholm();

            plots.setType(MAYOR, HOME, PlotType.BANK).join();

            assertThat(index.at(HOME).orElseThrow().kind())
                    .as("marking a chunk as a bank must not move the homeblock")
                    .isEqualTo(ClaimKind.HOMEBLOCK);
        }

        @Test
        @DisplayName("an unreadable stored type reads as an ordinary plot")
        void unknownTypesDegrade() {
            assertThat(PlotType.fromStorage("MARKET_FROM_THE_FUTURE")).isEqualTo(PlotType.DEFAULT);
            assertThat(PlotType.parse("market_from_the_future")).isEmpty();
            assertThat(PlotType.parse("shop")).contains(PlotType.SHOP);
        }
    }

    @Nested
    @DisplayName("the RESIDENT rung, reachable at last")
    class TheResidentRung {

        @Test
        @DisplayName("holding a plot places you above your fellow residents on it")
        void holdingReachesResident() {
            riftholm();
            assertThat(query().relationshipAt(CITIZEN, MARKET)).isEqualTo(Relationship.TOWN);

            plots.take(CITIZEN, MARKET).join();

            assertThat(query().relationshipAt(CITIZEN, MARKET))
                    .as("the rung has been in the ladder since it was written and unreachable")
                    .isEqualTo(Relationship.RESIDENT);
            assertThat(query().relationshipAt(NEIGHBOUR, MARKET))
                    .as("and only for the holder")
                    .isEqualTo(Relationship.TOWN);
        }

        @Test
        @DisplayName("a town can close a flag to its members and leave holders their own plots")
        void holdersKeepWhatMembersLose() {
            riftholm();
            plots.take(CITIZEN, MARKET).join();
            overrides.apply(net.riftbreaker.rifttowny.domain.flag.FlagOverride.of(
                    net.riftbreaker.rifttowny.domain.flag.FlagTarget.organisation(
                            index.at(MARKET).orElseThrow().town()),
                    ProtectionFlag.BUILD, Relationship.TOWN, false, null, NOW));

            assertThat(query().mayAct(NEIGHBOUR, MARKET, ProtectionFlag.BUILD).denied())
                    .as("an ordinary member is shut out")
                    .isTrue();
            assertThat(query().mayAct(CITIZEN, MARKET, ProtectionFlag.BUILD).allowed())
                    .as("and the holder is not, which is the whole point of the rung")
                    .isTrue();
        }

        @Test
        @DisplayName("giving the plot back gives the standing back too")
        void releasingLosesTheRung() {
            riftholm();
            plots.take(CITIZEN, MARKET).join();

            plots.release(CITIZEN, MARKET).join();

            assertThat(query().relationshipAt(CITIZEN, MARKET)).isEqualTo(Relationship.TOWN);
        }

        @Test
        @DisplayName("a holder is still bound by their role")
        void theRoleGateStillApplies() {
            final Town town = riftholm();
            plots.take(CITIZEN, MARKET).join();
            revokeMemberPermission(town, Permission.BREAK);

            final var answer = query().mayAct(CITIZEN, MARKET, ProtectionFlag.BREAK);

            assertThat(answer.denied())
                    .as("the plot says what a relationship may do, not what a person may")
                    .isTrue();
            assertThat(answer.refusedByRole()).isTrue();
        }
    }

    @Test
    @DisplayName("plots survive a restart")
    void plotsReload() {
        riftholm();
        plots.take(CITIZEN, MARKET).join();
        plots.setType(CITIZEN, MARKET, PlotType.SHOP).join();

        final TerritoryIndex reloaded = TerritoryIndex.empty();
        new TerritoryService(store, CLOCK, reloaded).loadIndex().join();

        assertThat(reloaded.at(MARKET).orElseThrow().isHeldBy(CITIZEN)).isTrue();
        assertThat(reloaded.at(MARKET).orElseThrow().type()).isEqualTo(PlotType.SHOP);
    }

    private void revokeMemberPermission(final Town town, final Permission permission) {
        store.inTransaction(transaction -> {
            final RoleBook book = transaction.roles()
                    .find(OrganisationScope.TOWN, town.id().value()).orElseThrow();
            final RoleId member = book.systemRole(SystemRole.MEMBER).orElseThrow().id();
            transaction.roles().save(book.revoke(member, permission).orElseThrow());
            return null;
        }).join();
        // The cache holds the role book protection reads, so it has to be told.
        new CivicCacheService(store, civicCache, warning -> { }).refresh(town.id()).join();
        assertThat(Set.of(permission)).isNotEmpty();
    }
}
