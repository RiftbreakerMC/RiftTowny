package net.riftbreaker.rifttowny.domain.flag;

import net.riftbreaker.rifttowny.api.ChunkKey;
import net.riftbreaker.rifttowny.domain.org.TownId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FlagOverridesTest {

    private static final Instant NOW = Instant.parse("2026-08-12T09:00:00Z");
    private static final UUID WORLD = UUID.randomUUID();
    private static final ChunkKey CHUNK = new ChunkKey(WORLD, 3, -7);
    private static final TownId RIFTHOLM = TownId.random();

    private final FlagOverrides overrides = FlagOverrides.empty();

    private static FlagOverride override(
            final FlagTarget target, final ProtectionFlag flag,
            final Relationship relationship, final boolean allowed) {
        return FlagOverride.of(target, flag, relationship, allowed, null, NOW);
    }

    private FlagDecision resolve(final ProtectionFlag flag, final Relationship relationship) {
        return FlagResolver.resolve(flag, relationship, overrides.layersFor(CHUNK, RIFTHOLM));
    }

    @Nested
    @DisplayName("layer order")
    class Order {

        @Test
        @DisplayName("with nothing stored, resolution reaches the built-in default")
        void emptyReachesBuiltIn() {
            assertThat(resolve(ProtectionFlag.BUILD, Relationship.VISITOR).source())
                    .isEqualTo(FlagSource.BUILT_IN);
        }

        @Test
        @DisplayName("a claim override beats its organisation, which beats its world")
        void claimBeatsOrganisationBeatsWorld() {
            overrides.apply(override(
                    FlagTarget.world(WORLD), ProtectionFlag.BUILD, Relationship.VISITOR, false));
            assertThat(resolve(ProtectionFlag.BUILD, Relationship.VISITOR).source())
                    .isEqualTo(FlagSource.WORLD);

            overrides.apply(override(FlagTarget.organisation(RIFTHOLM), ProtectionFlag.BUILD,
                    Relationship.VISITOR, false));
            assertThat(resolve(ProtectionFlag.BUILD, Relationship.VISITOR).source())
                    .isEqualTo(FlagSource.ORGANISATION);

            overrides.apply(override(
                    FlagTarget.claim(CHUNK), ProtectionFlag.BUILD, Relationship.VISITOR, true));
            final FlagDecision decision = resolve(ProtectionFlag.BUILD, Relationship.VISITOR);
            assertThat(decision.source()).isEqualTo(FlagSource.CLAIM);
            assertThat(decision.allowed()).isTrue();
        }

        @Test
        @DisplayName("an administrator's restriction beats every one of them")
        void adminWins() {
            overrides.apply(override(
                    FlagTarget.claim(CHUNK), ProtectionFlag.BUILD, Relationship.VISITOR, true));
            overrides.apply(override(
                    FlagTarget.admin(), ProtectionFlag.BUILD, Relationship.VISITOR, false));

            final FlagDecision decision = resolve(ProtectionFlag.BUILD, Relationship.VISITOR);

            assertThat(decision.allowed()).isFalse();
            assertThat(decision.source()).isEqualTo(FlagSource.ADMIN);
        }

        @Test
        @DisplayName("an opinion about one flag does not answer for another")
        void opinionsAreScoped() {
            overrides.apply(override(FlagTarget.organisation(RIFTHOLM), ProtectionFlag.BUILD,
                    Relationship.VISITOR, true));

            assertThat(resolve(ProtectionFlag.CONTAINER, Relationship.VISITOR).source())
                    .isEqualTo(FlagSource.BUILT_IN);
            assertThat(resolve(ProtectionFlag.BUILD, Relationship.ALLY).source())
                    .isEqualTo(FlagSource.BUILT_IN);
        }

        @Test
        @DisplayName("a chunk in another world does not read this world's overrides")
        void worldsAreScoped() {
            overrides.apply(override(
                    FlagTarget.world(WORLD), ProtectionFlag.BUILD, Relationship.VISITOR, true));

            final ChunkKey elsewhere = new ChunkKey(UUID.randomUUID(), 3, -7);
            assertThat(FlagResolver.resolve(ProtectionFlag.BUILD, Relationship.VISITOR,
                    overrides.layersFor(elsewhere, RIFTHOLM)).source())
                    .isEqualTo(FlagSource.BUILT_IN);
        }

        @Test
        @DisplayName("wilderness reads the world and admin layers but has no owner to ask")
        void wildernessStillReadsWorld() {
            overrides.apply(override(
                    FlagTarget.world(WORLD), ProtectionFlag.PVP, Relationship.WILDERNESS, false));

            assertThat(FlagResolver.resolve(ProtectionFlag.PVP, Relationship.WILDERNESS,
                    overrides.layersFor(CHUNK, null)).source())
                    .isEqualTo(FlagSource.WORLD);
        }
    }

    @Nested
    @DisplayName("editing")
    class Editing {

        @Test
        @DisplayName("setting the same flag twice replaces the opinion")
        void applyReplaces() {
            final FlagTarget target = FlagTarget.organisation(RIFTHOLM);
            overrides.apply(override(target, ProtectionFlag.PVP, Relationship.TOWN, true));
            overrides.apply(override(target, ProtectionFlag.PVP, Relationship.TOWN, false));

            assertThat(overrides.of(target)).hasSize(1);
            assertThat(overrides.opinionOf(target, ProtectionFlag.PVP, Relationship.TOWN))
                    .contains(false);
        }

        @Test
        @DisplayName("clearing removes one opinion and leaves its neighbours alone")
        void clearIsNarrow() {
            final FlagTarget target = FlagTarget.organisation(RIFTHOLM);
            overrides.apply(override(target, ProtectionFlag.BUILD, Relationship.VISITOR, true));
            overrides.apply(override(target, ProtectionFlag.BUILD, Relationship.ALLY, true));

            assertThat(overrides.clear(target, ProtectionFlag.BUILD, Relationship.VISITOR)).isTrue();

            assertThat(overrides.of(target)).hasSize(1);
            assertThat(resolve(ProtectionFlag.BUILD, Relationship.VISITOR).source())
                    .isEqualTo(FlagSource.BUILT_IN);
            assertThat(resolve(ProtectionFlag.BUILD, Relationship.ALLY).source())
                    .isEqualTo(FlagSource.ORGANISATION);
        }

        @Test
        @DisplayName("clearing what was never set says so")
        void clearingNothing() {
            assertThat(overrides.clear(
                    FlagTarget.organisation(RIFTHOLM), ProtectionFlag.PVP, Relationship.TOWN))
                    .isFalse();
            assertThat(overrides.clear(null, ProtectionFlag.PVP, Relationship.TOWN)).isFalse();
        }

        @Test
        @DisplayName("clearing a target removes everything it held")
        void clearAll() {
            final FlagTarget target = FlagTarget.claim(CHUNK);
            overrides.apply(override(target, ProtectionFlag.BUILD, Relationship.VISITOR, true));
            overrides.apply(override(target, ProtectionFlag.CONTAINER, Relationship.VISITOR, true));

            assertThat(overrides.clearAll(target)).isEqualTo(2);

            assertThat(overrides.of(target)).isEmpty();
            assertThat(overrides.settingsFor(target)).isNull();
            assertThat(resolve(ProtectionFlag.BUILD, Relationship.VISITOR).source())
                    .isEqualTo(FlagSource.BUILT_IN);
        }

        @Test
        @DisplayName("a reload drops overrides that are gone and keeps the ones that are not")
        void replaceAll() {
            final FlagTarget target = FlagTarget.organisation(RIFTHOLM);
            overrides.apply(override(target, ProtectionFlag.BUILD, Relationship.VISITOR, true));
            overrides.apply(override(target, ProtectionFlag.PVP, Relationship.TOWN, true));

            overrides.replaceAll(List.of(
                    override(target, ProtectionFlag.PVP, Relationship.TOWN, true)));

            assertThat(overrides.size()).isEqualTo(1);
            assertThat(overrides.opinionOf(target, ProtectionFlag.BUILD, Relationship.VISITOR))
                    .isEmpty();
            assertThat(overrides.opinionOf(target, ProtectionFlag.PVP, Relationship.TOWN))
                    .contains(true);
        }

        @Test
        @DisplayName("the generation moves on every change")
        void generationMoves() {
            final long start = overrides.generation();
            overrides.apply(override(
                    FlagTarget.admin(), ProtectionFlag.BUILD, Relationship.VISITOR, true));

            assertThat(overrides.generation()).isGreaterThan(start);
            assertThat(overrides.describe()).isEqualTo("1 override(s) across 1 target(s)");
        }
    }

    @Nested
    @DisplayName("targets")
    class Targets {

        @Test
        @DisplayName("a world and an organisation with the same identifier do not collide")
        void scopesDoNotCollide() {
            final UUID shared = UUID.randomUUID();
            final TownId town = TownId.parse(shared.toString());
            overrides.apply(override(
                    FlagTarget.world(shared), ProtectionFlag.BUILD, Relationship.VISITOR, false));
            overrides.apply(override(
                    FlagTarget.organisation(town), ProtectionFlag.BUILD, Relationship.VISITOR, true));

            assertThat(overrides.opinionOf(
                    FlagTarget.world(shared), ProtectionFlag.BUILD, Relationship.VISITOR))
                    .contains(false);
            assertThat(overrides.opinionOf(
                    FlagTarget.organisation(town), ProtectionFlag.BUILD, Relationship.VISITOR))
                    .contains(true);
        }

        @Test
        @DisplayName("a target round-trips through its stored form")
        void targetsRestore() {
            for (final FlagTarget target : List.of(
                    FlagTarget.admin(),
                    FlagTarget.world(WORLD),
                    FlagTarget.claim(CHUNK),
                    FlagTarget.organisation(RIFTHOLM))) {
                assertThat(FlagTarget.restore(target.source().name(), target.key()))
                        .as("%s", target)
                        .contains(target);
            }
        }

        @Test
        @DisplayName("an unreadable stored target is refused rather than guessed at")
        void unreadableTargets() {
            assertThat(FlagTarget.restore("NOT_A_SCOPE", "*")).isEmpty();
            assertThat(FlagTarget.restore("CLAIM", "")).isEmpty();
            assertThat(FlagTarget.restore(null, "*")).isEmpty();
        }

        @Test
        @DisplayName("a layer that cannot be configured cannot be stored")
        void unstorableLayers() {
            assertThat(FlagSource.WAR_OR_EVENT.isConfigurable()).isFalse();
            assertThat(FlagSource.BUILT_IN.isConfigurable()).isFalse();

            final FlagTarget war = FlagTarget.restore("WAR_OR_EVENT", "*").orElseThrow();
            assertThatThrownBy(() -> FlagOverride.of(
                    war, ProtectionFlag.WAR_ACTION, Relationship.VISITOR, true, null, NOW))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
