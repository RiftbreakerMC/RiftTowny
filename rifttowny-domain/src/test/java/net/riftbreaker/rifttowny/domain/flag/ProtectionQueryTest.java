package net.riftbreaker.rifttowny.domain.flag;

import net.riftbreaker.rifttowny.api.ChunkKey;
import net.riftbreaker.rifttowny.domain.civic.CivicCache;
import net.riftbreaker.rifttowny.domain.civic.CivicFixture;
import net.riftbreaker.rifttowny.domain.civic.TownFacts;
import net.riftbreaker.rifttowny.domain.flag.ProtectionQuery.ProtectionAnswer;
import net.riftbreaker.rifttowny.domain.org.NationId;
import net.riftbreaker.rifttowny.domain.org.ResidentId;
import net.riftbreaker.rifttowny.domain.org.Town;
import net.riftbreaker.rifttowny.domain.role.Permission;
import net.riftbreaker.rifttowny.domain.territory.Claim;
import net.riftbreaker.rifttowny.domain.territory.ClaimKind;
import net.riftbreaker.rifttowny.domain.territory.TerritoryIndex;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ProtectionQueryTest {

    private static final UUID WORLD = UUID.randomUUID();
    private static final ChunkKey CLAIMED = new ChunkKey(WORLD, 0, 0);
    private static final ChunkKey WILD = new ChunkKey(WORLD, 50, 50);

    private static final ResidentId MAYOR = CivicFixture.resident();
    private static final ResidentId CITIZEN = CivicFixture.resident();
    private static final ResidentId FRIEND = CivicFixture.resident();
    private static final ResidentId STRANGER = CivicFixture.resident();
    private static final ResidentId COMPATRIOT = CivicFixture.resident();

    private final TerritoryIndex territory = TerritoryIndex.empty();
    private final CivicCache civic = CivicCache.empty();

    private Town riftholm;

    /** Riftholm owns one chunk, has a mayor, a citizen, a trusted friend and a nation. */
    private ProtectionQuery query() {
        return query(FlagSettingsSource.builtInOnly());
    }

    private ProtectionQuery query(final FlagSettingsSource settings) {
        final NationId valen = NationId.random();
        riftholm = CivicFixture.town("Riftholm", MAYOR, CITIZEN)
                .trust(FRIEND).orElseThrow()
                .joinNation(valen).orElseThrow();
        final Town ashford = CivicFixture.town("Ashford", COMPATRIOT)
                .joinNation(valen).orElseThrow();

        territory.put(Claim.of(CLAIMED, riftholm.id(), ClaimKind.ORDINARY, CivicFixture.NOW));
        civic.remember(CivicFixture.facts(riftholm));
        civic.remember(CivicFixture.facts(ashford));
        return new ProtectionQuery(territory, civic, settings);
    }

    @Nested
    @DisplayName("wilderness")
    class Wilderness {

        @Test
        @DisplayName("anyone may build on unclaimed land")
        void anyoneMayBuild() {
            final ProtectionAnswer answer = query().mayAct(STRANGER, WILD, ProtectionFlag.BUILD);

            assertThat(answer.allowed()).isTrue();
            assertThat(answer.relationship()).isEqualTo(Relationship.WILDERNESS);
            assertThat(answer.owningTown()).isEmpty();
            assertThat(answer.explain()).contains("in wilderness");
        }

        @Test
        @DisplayName("a war action is still refused there, because nothing granted it")
        void warActionStillRefused() {
            assertThat(query().mayAct(STRANGER, WILD, ProtectionFlag.WAR_ACTION).denied()).isTrue();
        }
    }

    @Nested
    @DisplayName("inside a claim")
    class InsideAClaim {

        @Test
        @DisplayName("a stranger may not build")
        void strangerMayNotBuild() {
            final ProtectionAnswer answer = query().mayAct(STRANGER, CLAIMED, ProtectionFlag.BUILD);

            assertThat(answer.denied()).isTrue();
            assertThat(answer.relationship()).isEqualTo(Relationship.VISITOR);
            assertThat(answer.source()).isEqualTo(FlagSource.BUILT_IN);
            assertThat(answer.refusedByRole()).isFalse();
            assertThat(answer.ownerName()).isEqualTo("Riftholm");
        }

        @Test
        @DisplayName("a stranger may not open a chest")
        void strangerMayNotOpenContainers() {
            assertThat(query().mayAct(STRANGER, CLAIMED, ProtectionFlag.CONTAINER).denied()).isTrue();
        }

        @Test
        @DisplayName("a member may build")
        void memberMayBuild() {
            final ProtectionAnswer answer = query().mayAct(CITIZEN, CLAIMED, ProtectionFlag.BUILD);

            assertThat(answer.allowed()).isTrue();
            assertThat(answer.relationship()).isEqualTo(Relationship.TOWN);
        }

        @Test
        @DisplayName("a trusted outsider is trusted, but built-in defaults still stop them building")
        void trustedOutsiderIsPlacedButNotEmpowered() {
            final ProtectionQuery query = query();

            assertThat(query.relationshipAt(FRIEND, CLAIMED)).isEqualTo(Relationship.TRUSTED);
            assertThat(query.mayAct(FRIEND, CLAIMED, ProtectionFlag.BUILD).denied()).isTrue();
            // Trust is a placement on the ladder; what it grants is a configuration decision, and
            // nothing configures it yet.
            assertThat(query.mayAct(FRIEND, CLAIMED, ProtectionFlag.SHOP_USE).allowed()).isTrue();
        }

        @Test
        @DisplayName("a member of another town in the same nation may use doors but not build")
        void nationMemberMayInteract() {
            final ProtectionQuery query = query();

            assertThat(query.relationshipAt(COMPATRIOT, CLAIMED)).isEqualTo(Relationship.NATION);
            assertThat(query.mayAct(COMPATRIOT, CLAIMED, ProtectionFlag.INTERACT).allowed()).isTrue();
            assertThat(query.mayAct(COMPATRIOT, CLAIMED, ProtectionFlag.BUILD).denied()).isTrue();
        }

        @Test
        @DisplayName("the owning town is named on the answer so a message needs no second lookup")
        void ownerIsCarried() {
            final ProtectionQuery query = query();
            final ProtectionAnswer answer = query.mayAct(STRANGER, CLAIMED, ProtectionFlag.BREAK);

            assertThat(answer.owningTown()).contains(riftholm.id());
            assertThat(query.ownerAt(CLAIMED)).contains(riftholm.id());
            assertThat(query.ownerFactsAt(CLAIMED).map(TownFacts::displayName)).contains("Riftholm");
        }
    }

    @Nested
    @DisplayName("the role gate")
    class RoleGate {

        @Test
        @DisplayName("a member whose role lost BREAK is refused, and told it was their role")
        void memberWithoutPermissionIsRefused() {
            final NationId valen = NationId.random();
            final Town town = CivicFixture.town("Riftholm", MAYOR, CITIZEN).joinNation(valen)
                    .orElseThrow();
            territory.put(Claim.of(CLAIMED, town.id(), ClaimKind.ORDINARY, CivicFixture.NOW));
            civic.remember(TownFacts.of(town,
                    CivicFixture.rolesWithoutMemberPermission(town, Permission.BREAK)));
            final ProtectionQuery query = new ProtectionQuery(territory, civic);

            final ProtectionAnswer answer = query.mayAct(CITIZEN, CLAIMED, ProtectionFlag.BREAK);

            assertThat(answer.denied()).isTrue();
            assertThat(answer.refusedByRole()).isTrue();
            assertThat(answer.missingPermission()).isEqualTo(Permission.BREAK);
            assertThat(answer.relationship()).isEqualTo(Relationship.TOWN);
            assertThat(answer.explain()).contains("no BREAK in Riftholm");
            // Building is untouched: the gate is per permission, not per member.
            assertThat(query.mayAct(CITIZEN, CLAIMED, ProtectionFlag.BUILD).allowed()).isTrue();
        }

        @Test
        @DisplayName("the mayor holds every permission, whatever the member role lost")
        void mayorIsUnaffected() {
            final Town town = CivicFixture.town("Riftholm", MAYOR, CITIZEN);
            territory.put(Claim.of(CLAIMED, town.id(), ClaimKind.ORDINARY, CivicFixture.NOW));
            civic.remember(TownFacts.of(town,
                    CivicFixture.rolesWithoutMemberPermission(town, Permission.BREAK)));

            final ProtectionAnswer answer = new ProtectionQuery(territory, civic)
                    .mayAct(MAYOR, CLAIMED, ProtectionFlag.BREAK);

            assertThat(answer.allowed()).isTrue();
            // TOWN, not RESIDENT: that rung means "owns this particular plot", and plots do not
            // exist yet. Leading the town is a role, not a claim on the ground.
            assertThat(answer.relationship()).isEqualTo(Relationship.TOWN);
        }

        @Test
        @DisplayName("an outsider is never judged by the owning town's role book")
        void outsiderIsNotRoleGated() {
            final Town town = CivicFixture.town("Riftholm", MAYOR);
            territory.put(Claim.of(CLAIMED, town.id(), ClaimKind.ORDINARY, CivicFixture.NOW));
            civic.remember(TownFacts.of(town,
                    CivicFixture.rolesWithoutMemberPermission(town, Permission.SHOP_USE)));

            // SHOP_USE is allowed to everyone by default, and the stranger holds no role here, so the
            // town stripping it from its own members must not change their answer.
            final ProtectionAnswer answer = new ProtectionQuery(territory, civic)
                    .mayAct(STRANGER, CLAIMED, ProtectionFlag.SHOP_USE);

            assertThat(answer.allowed()).isTrue();
            assertThat(answer.refusedByRole()).isFalse();
        }

        @Test
        @DisplayName("a flag with no matching permission is decided by the territory alone")
        void unmappedFlagSkipsTheRoleGate() {
            assertThat(ProtectionQuery.permissionFor(ProtectionFlag.PVP)).isEmpty();
            assertThat(ProtectionQuery.permissionFor(ProtectionFlag.EXPLOSIONS)).isEmpty();
            assertThat(ProtectionQuery.permissionFor(ProtectionFlag.BUILD))
                    .contains(Permission.BUILD);
            assertThat(ProtectionQuery.permissionFor(ProtectionFlag.INTERACT))
                    .contains(Permission.SWITCH);
            assertThat(ProtectionQuery.permissionFor(null)).isEmpty();
        }

        @Test
        @DisplayName("a caller may override the mapped permission for an action the flag lumps together")
        void callerMayOverrideThePermission() {
            final Town town = CivicFixture.town("Riftholm", MAYOR, CITIZEN);
            territory.put(Claim.of(CLAIMED, town.id(), ClaimKind.ORDINARY, CivicFixture.NOW));
            civic.remember(TownFacts.of(town,
                    CivicFixture.rolesWithoutMemberPermission(town, Permission.ENTITY_INTERACT)));
            final ProtectionQuery query = new ProtectionQuery(territory, civic);

            // An armour stand and a lever are both INTERACT, but only one of them is SWITCH.
            assertThat(query.mayAct(CITIZEN, CLAIMED, ProtectionFlag.INTERACT).allowed()).isTrue();
            assertThat(query.mayAct(CITIZEN, CLAIMED, ProtectionFlag.INTERACT,
                    Permission.ENTITY_INTERACT).denied()).isTrue();
        }
    }

    @Nested
    @DisplayName("world flags")
    class WorldFlags {

        @Test
        @DisplayName("an explosion in a claim is refused however entitled the nearest player is")
        void explosionsDoNotFollowTheActor() {
            final ProtectionQuery query = query();

            for (final ResidentId who : List.of(MAYOR, CITIZEN, FRIEND, STRANGER)) {
                final ProtectionAnswer answer =
                        query.mayAct(who, CLAIMED, ProtectionFlag.EXPLOSIONS);
                assertThat(answer.denied()).as("explosion for %s", who).isTrue();
                assertThat(answer.relationship()).isEqualTo(Relationship.WILDERNESS);
            }
        }

        @Test
        @DisplayName("a world flag with nobody behind it is answerable")
        void nullActorIsFine() {
            final ProtectionQuery query = query();

            assertThat(query.mayAct(null, CLAIMED, ProtectionFlag.PISTONS).denied()).isTrue();
            assertThat(query.mayAct(null, CLAIMED, ProtectionFlag.MOB_SPAWNING).allowed()).isTrue();
            assertThat(query.mayAct(null, CLAIMED, ProtectionFlag.BUILD).denied()).isTrue();
        }
    }

    @Nested
    @DisplayName("configured layers")
    class ConfiguredLayers {

        @Test
        @DisplayName("a claim layer that opens building to visitors wins over the built-in default")
        void claimLayerWins() {
            final FlagSettings open = FlagSettings.builder()
                    .set(ProtectionFlag.BUILD, Relationship.VISITOR, true)
                    .build();
            final ProtectionQuery query = query(
                    (chunk, owner) -> FlagResolver.layers(null, null, null, open, null, null));

            final ProtectionAnswer answer = query.mayAct(STRANGER, CLAIMED, ProtectionFlag.BUILD);

            assertThat(answer.allowed()).isTrue();
            assertThat(answer.source()).isEqualTo(FlagSource.CLAIM);
        }

        @Test
        @DisplayName("an admin layer that closes building beats a claim layer that opens it")
        void adminLayerWinsOverClaim() {
            final FlagSettings open = FlagSettings.builder()
                    .setAll(ProtectionFlag.BUILD, true)
                    .build();
            final FlagSettings shut = FlagSettings.builder()
                    .setAll(ProtectionFlag.BUILD, false)
                    .build();
            final ProtectionQuery query = query(
                    (chunk, owner) -> FlagResolver.layers(shut, null, null, open, null, null));

            final ProtectionAnswer answer = query.mayAct(CITIZEN, CLAIMED, ProtectionFlag.BUILD);

            assertThat(answer.denied()).isTrue();
            assertThat(answer.source()).isEqualTo(FlagSource.ADMIN);
        }

        @Test
        @DisplayName("a layer that opens a flag does not bypass the member's role")
        void territoryPermissionDoesNotBypassTheRole() {
            final Town town = CivicFixture.town("Riftholm", MAYOR, CITIZEN);
            territory.put(Claim.of(CLAIMED, town.id(), ClaimKind.ORDINARY, CivicFixture.NOW));
            civic.remember(TownFacts.of(town,
                    CivicFixture.rolesWithoutMemberPermission(town, Permission.BREAK)));
            final FlagSettings open = FlagSettings.builder()
                    .setAll(ProtectionFlag.BREAK, true)
                    .build();
            final ProtectionQuery query = new ProtectionQuery(territory, civic,
                    (chunk, owner) -> FlagResolver.layers(null, null, null, open, null, null));

            final ProtectionAnswer answer = query.mayAct(CITIZEN, CLAIMED, ProtectionFlag.BREAK);

            assertThat(answer.denied()).isTrue();
            assertThat(answer.refusedByRole()).isTrue();
        }
    }

    @Nested
    @DisplayName("cache faults")
    class CacheFaults {

        @Test
        @DisplayName("a claim owned by a town the cache cannot describe is protected, not open")
        void unknownTownDenies() {
            final Town town = CivicFixture.town("Riftholm", MAYOR);
            territory.put(Claim.of(CLAIMED, town.id(), ClaimKind.ORDINARY, CivicFixture.NOW));
            // Deliberately not remembered.
            final ProtectionQuery query = new ProtectionQuery(territory, civic);

            final ProtectionAnswer answer = query.mayAct(MAYOR, CLAIMED, ProtectionFlag.BUILD);

            assertThat(answer.denied()).isTrue();
            assertThat(answer.cacheFault()).isTrue();
            assertThat(answer.owningTown()).contains(town.id());
            assertThat(answer.explain()).contains("is not loaded");
            assertThat(query.relationshipAt(MAYOR, CLAIMED)).isEqualTo(Relationship.VISITOR);
        }

        @Test
        @DisplayName("an unknown town denies even a flag that is allowed to everyone by default")
        void unknownTownDeniesEvenPermissiveFlags() {
            final Town town = CivicFixture.town("Riftholm", MAYOR);
            territory.put(Claim.of(CLAIMED, town.id(), ClaimKind.ORDINARY, CivicFixture.NOW));

            assertThat(new ProtectionQuery(territory, civic)
                    .mayAct(STRANGER, CLAIMED, ProtectionFlag.SHOP_USE).denied()).isTrue();
        }
    }
}
