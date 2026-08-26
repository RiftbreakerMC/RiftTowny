package net.riftbreaker.rifttowny.storage;

import net.riftbreaker.rifttowny.api.ChunkKey;
import net.riftbreaker.rifttowny.domain.naming.NamePolicy;
import net.riftbreaker.rifttowny.domain.org.ChangeDenial;
import net.riftbreaker.rifttowny.domain.org.OrganisationScope;
import net.riftbreaker.rifttowny.domain.org.Resident;
import net.riftbreaker.rifttowny.domain.org.ResidentId;
import net.riftbreaker.rifttowny.domain.org.Town;
import net.riftbreaker.rifttowny.domain.org.TownId;
import net.riftbreaker.rifttowny.domain.role.Permission;
import net.riftbreaker.rifttowny.domain.role.Role;
import net.riftbreaker.rifttowny.domain.role.RoleBook;
import net.riftbreaker.rifttowny.domain.role.RoleId;
import net.riftbreaker.rifttowny.domain.service.TerritoryService;
import net.riftbreaker.rifttowny.domain.service.TownService;
import net.riftbreaker.rifttowny.domain.territory.ClaimKind;
import net.riftbreaker.rifttowny.domain.territory.ClaimPreview;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

import net.riftbreaker.rifttowny.domain.flag.ProtectionFlag;
import net.riftbreaker.rifttowny.domain.flag.Relationship;
import net.riftbreaker.rifttowny.domain.org.ChangeDenial;

import static org.assertj.core.api.Assertions.assertThat;

class TerritoryServiceTest extends SqliteFixture {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-08-10T12:00:00Z"), ZoneOffset.UTC);
    private static final UUID WORLD = UUID.randomUUID();

    private static final ResidentId MAYOR = ResidentId.of(UUID.randomUUID());
    private static final ResidentId RIVAL = ResidentId.of(UUID.randomUUID());
    private static final ResidentId CITIZEN = ResidentId.of(UUID.randomUUID());

    private final net.riftbreaker.rifttowny.domain.territory.TerritoryIndex index =

            net.riftbreaker.rifttowny.domain.territory.TerritoryIndex.empty();

    private JdbcCivicStore store;
    private TownService towns;
    private TerritoryService territory;
    private Town riftholm;

    @BeforeEach
    void setUp() {
        store = new JdbcCivicStore(database, DIRECT, CLOCK);
        towns = new TownService(store, NamePolicy.defaults(), CLOCK, index);
        territory = new TerritoryService(store, CLOCK, index);
        riftholm = towns.found(MAYOR, "Mayor", "Riftholm").join().value().orElseThrow();
    }

    private static ChunkKey at(final int x, final int z) {
        return new ChunkKey(WORLD, x, z);
    }

    private void addMember(final ResidentId who, final String name) {
        new JdbcResidentRepository(database, DIRECT)
                .save(Resident.newcomer(who, name, CLOCK.instant())).join();
        towns.join(MAYOR, who, riftholm.id()).join();
    }

    /** Claims the homeblock and any further ordinary chunks, as the mayor. */
    private void settle(final ChunkKey... ordinary) {
        territory.claim(MAYOR, riftholm.id(), at(0, 0), ClaimKind.HOMEBLOCK).join();
        for (final ChunkKey chunk : ordinary) {
            territory.claim(MAYOR, riftholm.id(), chunk, ClaimKind.ORDINARY).join();
        }
    }

    @Nested
    @DisplayName("claiming")
    class Claiming {

        @Test
        @DisplayName("the first claim persists as the homeblock")
        void firstClaimPersists() {
            assertThat(territory.claim(MAYOR, riftholm.id(), at(0, 0), ClaimKind.HOMEBLOCK)
                    .join().succeeded()).isTrue();

            assertThat(territory.territoryOf(riftholm.id()).join().homeblock().orElseThrow().chunk())
                    .isEqualTo(at(0, 0));
            assertThat(territory.ownerOf(at(0, 0)).join().orElseThrow().town())
                    .isEqualTo(riftholm.id());
        }

        @Test
        @DisplayName("territory survives a reload and keeps its shape rules")
        void territorySurvivesAReload() {
            settle(at(1, 0), at(2, 0));

            final var reloaded = territory.territoryOf(riftholm.id()).join();

            assertThat(reloaded.size()).isEqualTo(3);
            assertThat(reloaded.owns(at(2, 0))).isTrue();
            assertThat(reloaded.unclaim(at(1, 0)).denial())
                    .as("the reloaded aggregate still refuses to sever the town")
                    .contains(ChangeDenial.UNCLAIM_WOULD_DISCONNECT);
        }

        @Test
        @DisplayName("a chunk another town owns is refused with its own reason")
        void anotherTownsChunkIsRefused() {
            settle();
            new JdbcResidentRepository(database, DIRECT)
                    .save(Resident.newcomer(RIVAL, "Rival", CLOCK.instant())).join();
            final Town ashford =
                    towns.found(RIVAL, "Rival", "Ashford").join().value().orElseThrow();

            assertThat(territory.claim(RIVAL, ashford.id(), at(0, 0), ClaimKind.HOMEBLOCK)
                    .join().denial())
                    .contains(ChangeDenial.CHUNK_OWNED_BY_ANOTHER_TOWN);
            assertThat(territory.ownerOf(at(0, 0)).join().orElseThrow().town())
                    .isEqualTo(riftholm.id());
        }

        @Test
        @DisplayName("re-claiming your own chunk is a different reason again")
        void ownChunkIsAlreadyClaimed() {
            settle();

            assertThat(territory.claim(MAYOR, riftholm.id(), at(0, 0), ClaimKind.ORDINARY)
                    .join().denial())
                    .contains(ChangeDenial.CHUNK_ALREADY_CLAIMED);
        }

        @Test
        @DisplayName("the shape rules still apply through the service")
        void shapeRulesApply() {
            settle();

            assertThat(territory.claim(MAYOR, riftholm.id(), at(5, 5), ClaimKind.ORDINARY)
                    .join().denial())
                    .contains(ChangeDenial.CLAIM_MUST_TOUCH_TOWN);
            assertThat(territory.claim(MAYOR, riftholm.id(), at(1, 0), ClaimKind.OUTPOST)
                    .join().denial())
                    .contains(ChangeDenial.OUTPOST_MUST_NOT_TOUCH_TOWN);
        }

        @Test
        @DisplayName("a refused claim writes nothing")
        void refusedClaimWritesNothing() {
            settle();

            territory.claim(MAYOR, riftholm.id(), at(5, 5), ClaimKind.ORDINARY).join();

            assertThat(territory.ownerOf(at(5, 5)).join()).isEmpty();
            assertThat(territory.territoryOf(riftholm.id()).join().size()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("authority")
    class Authority {

        @Test
        @DisplayName("an ordinary member cannot claim or unclaim")
        void memberCannotClaim() {
            settle(at(1, 0));
            addMember(CITIZEN, "Citizen");

            assertThat(territory.claim(CITIZEN, riftholm.id(), at(2, 0), ClaimKind.ORDINARY)
                    .join().denial()).contains(ChangeDenial.MISSING_PERMISSION);
            assertThat(territory.unclaim(CITIZEN, riftholm.id(), at(1, 0)).join().denial())
                    .contains(ChangeDenial.MISSING_PERMISSION);
        }

        @Test
        @DisplayName("a role carrying CLAIM_LAND is enough, without being mayor")
        void roleGrantsClaiming() {
            settle();
            addMember(CITIZEN, "Citizen");
            final RoleId roleId = RoleId.random();
            store.inTransaction(transaction -> {
                final RoleBook book = transaction.roles()
                        .find(OrganisationScope.TOWN, riftholm.id().value()).orElseThrow();
                final Role surveyor = Role.custom(
                        roleId, OrganisationScope.TOWN, riftholm.id().value(), "Surveyor", 500,
                        Set.of(Permission.CLAIM_LAND), CLOCK.instant());
                transaction.roles().save(book.create(surveyor, Set.of()).orElseThrow()
                        .assign(CITIZEN, roleId).orElseThrow());
                return null;
            }).join();

            assertThat(territory.claim(CITIZEN, riftholm.id(), at(1, 0), ClaimKind.ORDINARY)
                    .join().succeeded()).isTrue();
        }

        @Test
        @DisplayName("claiming for a town that does not exist is refused")
        void unknownTownIsRefused() {
            assertThat(territory.claim(MAYOR, TownId.random(), at(0, 0), ClaimKind.HOMEBLOCK)
                    .join().denial()).contains(ChangeDenial.TOWN_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("unclaiming and the homeblock")
    class Releasing {

        @Test
        @DisplayName("a leaf chunk is released and its owner lookup clears")
        void leafIsReleased() {
            settle(at(1, 0));

            assertThat(territory.unclaim(MAYOR, riftholm.id(), at(1, 0)).join().succeeded()).isTrue();
            assertThat(territory.ownerOf(at(1, 0)).join()).isEmpty();
        }

        @Test
        @DisplayName("severing is refused and nothing is deleted")
        void severingIsRefused() {
            settle(at(1, 0), at(2, 0));

            assertThat(territory.unclaim(MAYOR, riftholm.id(), at(1, 0)).join().denial())
                    .contains(ChangeDenial.UNCLAIM_WOULD_DISCONNECT);
            assertThat(territory.ownerOf(at(1, 0)).join()).isPresent();
        }

        @Test
        @DisplayName("the homeblock moves, and both chunks change kind together")
        void homeblockMoves() {
            settle(at(1, 0));

            assertThat(territory.moveHomeblock(MAYOR, riftholm.id(), at(1, 0)).join().succeeded())
                    .isTrue();

            final var reloaded = territory.territoryOf(riftholm.id()).join();
            assertThat(reloaded.homeblock().orElseThrow().chunk()).isEqualTo(at(1, 0));
            assertThat(reloaded.at(at(0, 0)).orElseThrow().kind()).isEqualTo(ClaimKind.ORDINARY);
        }

        @Test
        @DisplayName("disbanding releases every chunk")
        void disbandReleasesTerritory() {
            settle(at(1, 0), at(2, 0));

            towns.disband(MAYOR, riftholm.id()).join();

            assertThat(territory.ownerOf(at(0, 0)).join()).isEmpty();
            assertThat(territory.ownerOf(at(2, 0)).join()).isEmpty();
        }
    }

    @Nested
    @DisplayName("previewing")
    class Previewing {

        @Test
        @DisplayName("a preview answers without claiming")
        void previewDoesNotClaim() {
            settle();

            final ClaimPreview preview =
                    territory.previewClaim(riftholm.id(), at(1, 0), ClaimKind.ORDINARY).join();

            assertThat(preview.permitted()).isTrue();
            assertThat(preview.delta()).isEqualTo(1);
            assertThat(territory.ownerOf(at(1, 0)).join()).isEmpty();
        }

        @Test
        @DisplayName("a preview knows about other towns, so it matches what the real call would say")
        void previewSeesOtherTowns() {
            settle();
            new JdbcResidentRepository(database, DIRECT)
                    .save(Resident.newcomer(RIVAL, "Rival", CLOCK.instant())).join();
            final Town ashford =
                    towns.found(RIVAL, "Rival", "Ashford").join().value().orElseThrow();
            territory.claim(RIVAL, ashford.id(), at(9, 9), ClaimKind.HOMEBLOCK).join();

            final ClaimPreview preview =
                    territory.previewClaim(riftholm.id(), at(9, 9), ClaimKind.ORDINARY).join();

            assertThat(preview.permitted()).isFalse();
            assertThat(preview.refusal()).contains(ChangeDenial.CHUNK_OWNED_BY_ANOTHER_TOWN);
        }

        @Test
        @DisplayName("an unclaim preview reports the severing refusal")
        void unclaimPreview() {
            settle(at(1, 0), at(2, 0));

            assertThat(territory.previewUnclaim(riftholm.id(), at(1, 0)).join().refusal())
                    .contains(ChangeDenial.UNCLAIM_WOULD_DISCONNECT);
            assertThat(territory.previewUnclaim(riftholm.id(), at(2, 0)).join().delta())
                    .isEqualTo(-1);
        }
    }

    @Nested
    @DisplayName("releasing everything but the homeblock")
    class UnclaimAll {

        @Test
        @DisplayName("keeps the homeblock and releases the rest")
        void keepsTheHomeblock() {
            settle(at(1, 0), at(2, 0), at(3, 0));
            final Town town = riftholm;

            final var released = territory.unclaimAll(MAYOR, town.id()).join();

            assertThat(released.succeeded()).as("%s", released.denial()).isTrue();
            assertThat(released.value().orElseThrow().count()).isEqualTo(3);
            final var remaining = store.inTransaction(t -> t.claims().of(town.id())).join();
            assertThat(remaining).singleElement()
                    .extracting(net.riftbreaker.rifttowny.domain.territory.Claim::kind)
                    .isEqualTo(ClaimKind.HOMEBLOCK);
            assertThat(index.countForTownScanning(town.id())).isEqualTo(1);
        }

        @Test
        @DisplayName("works on a shape that a chunk-at-a-time release would refuse")
        void worksWhereALoopWouldNot() {
            // The reason this is not a loop over unclaim(). A corridor town cannot give up its
            // middle chunk - UNCLAIM_WOULD_DISCONNECT - so releasing one at a time gets stuck on
            // the first one that is not an end. Going straight to the homeblock never asks.
            settle(at(1, 0), at(2, 0));
            final Town town = riftholm;
            assertThat(territory.unclaim(MAYOR, town.id(), at(1, 0)).join().denial())
                    .as("the middle of a corridor, one at a time")
                    .contains(ChangeDenial.UNCLAIM_WOULD_DISCONNECT);

            assertThat(territory.unclaimAll(MAYOR, town.id()).join().succeeded()).isTrue();
        }

        @Test
        @DisplayName("a town that holds only its homeblock has nothing to release")
        void nothingToRelease() {
            settle();
            final Town town = riftholm;

            assertThat(territory.unclaimAll(MAYOR, town.id()).join().denial())
                    .contains(ChangeDenial.NOTHING_TO_CHANGE);
        }

        @Test
        @DisplayName("each released chunk's own flag overrides go with it")
        void overridesGoToo() {
            // Otherwise the override comes back into force against whoever claims the chunk next:
            // a per-chunk override is keyed on the chunk, not on the town that set it.
            settle(at(1, 0));
            final Town town = riftholm;
            final var target = net.riftbreaker.rifttowny.domain.flag.FlagTarget.claim(at(1, 0));
            store.inTransaction(t -> {
                t.flags().set(net.riftbreaker.rifttowny.domain.flag.FlagOverride.of(
                        target, ProtectionFlag.BUILD, Relationship.VISITOR, true, MAYOR,
                        CLOCK.instant()));
                return null;
            }).join();

            territory.unclaimAll(MAYOR, town.id()).join();

            assertThat(store.inTransaction(t -> t.flags().of(target)).join()).isEmpty();
        }

        @Test
        @DisplayName("a single unclaim takes its chunk's overrides too")
        void singleUnclaimClearsOverrides() {
            // The same leak, on the path that had it first.
            settle(at(1, 0));
            final Town town = riftholm;
            final var target = net.riftbreaker.rifttowny.domain.flag.FlagTarget.claim(at(1, 0));
            store.inTransaction(t -> {
                t.flags().set(net.riftbreaker.rifttowny.domain.flag.FlagOverride.of(
                        target, ProtectionFlag.BUILD, Relationship.VISITOR, true, MAYOR,
                        CLOCK.instant()));
                return null;
            }).join();

            territory.unclaim(MAYOR, town.id(), at(1, 0)).join();

            assertThat(store.inTransaction(t -> t.flags().of(target)).join()).isEmpty();
        }
    }
}
