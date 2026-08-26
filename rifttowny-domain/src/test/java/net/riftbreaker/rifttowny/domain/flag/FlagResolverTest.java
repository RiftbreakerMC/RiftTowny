package net.riftbreaker.rifttowny.domain.flag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FlagResolverTest {

    private static FlagSettings deny(final ProtectionFlag flag, final Relationship relationship) {
        return FlagSettings.builder().set(flag, relationship, false).build();
    }

    private static FlagSettings allow(final ProtectionFlag flag, final Relationship relationship) {
        return FlagSettings.builder().set(flag, relationship, true).build();
    }

    private static FlagDecision resolve(
            final ProtectionFlag flag,
            final Relationship relationship,
            final List<FlagResolver.FlagLayer> layers) {
        return FlagResolver.resolve(flag, relationship, layers);
    }

    @Nested
    @DisplayName("layer order")
    class Order {

        @Test
        @DisplayName("with no layers at all, the built-in default answers")
        void builtInDefaultAnswers() {
            final FlagDecision decision = resolve(ProtectionFlag.BUILD, Relationship.VISITOR, List.of());

            assertThat(decision.allowed()).isFalse();
            assertThat(decision.source()).isEqualTo(FlagSource.BUILT_IN);
        }

        @Test
        @DisplayName("an admin restriction beats every other layer")
        void adminWins() {
            final List<FlagResolver.FlagLayer> layers = FlagResolver.layers(
                    deny(ProtectionFlag.BUILD, Relationship.TOWN),
                    allow(ProtectionFlag.BUILD, Relationship.TOWN),
                    allow(ProtectionFlag.BUILD, Relationship.TOWN),
                    allow(ProtectionFlag.BUILD, Relationship.TOWN),
                    allow(ProtectionFlag.BUILD, Relationship.TOWN),
                    allow(ProtectionFlag.BUILD, Relationship.TOWN));

            final FlagDecision decision = resolve(ProtectionFlag.BUILD, Relationship.TOWN, layers);

            assertThat(decision.allowed())
                    .as("an organisation can never grant itself what an administrator forbade")
                    .isFalse();
            assertThat(decision.source()).isEqualTo(FlagSource.ADMIN);
        }

        @Test
        @DisplayName("a war override beats an area, claim and organisation setting")
        void warBeatsTheRest() {
            final List<FlagResolver.FlagLayer> layers = FlagResolver.layers(
                    null,
                    allow(ProtectionFlag.PVP, Relationship.VISITOR),
                    deny(ProtectionFlag.PVP, Relationship.VISITOR),
                    deny(ProtectionFlag.PVP, Relationship.VISITOR),
                    deny(ProtectionFlag.PVP, Relationship.VISITOR),
                    null);

            final FlagDecision decision = resolve(ProtectionFlag.PVP, Relationship.VISITOR, layers);

            assertThat(decision.allowed()).isTrue();
            assertThat(decision.source()).isEqualTo(FlagSource.WAR_OR_EVENT);
        }

        @Test
        @DisplayName("an area overrides the claim it sits in")
        void areaBeatsClaim() {
            final List<FlagResolver.FlagLayer> layers = FlagResolver.layers(
                    null, null,
                    allow(ProtectionFlag.CONTAINER, Relationship.VISITOR),
                    deny(ProtectionFlag.CONTAINER, Relationship.VISITOR),
                    null, null);

            assertThat(resolve(ProtectionFlag.CONTAINER, Relationship.VISITOR, layers).source())
                    .isEqualTo(FlagSource.AREA);
        }

        @Test
        @DisplayName("a claim overrides its organisation's default")
        void claimBeatsOrganisation() {
            final List<FlagResolver.FlagLayer> layers = FlagResolver.layers(
                    null, null, null,
                    allow(ProtectionFlag.BUILD, Relationship.ALLY),
                    deny(ProtectionFlag.BUILD, Relationship.ALLY),
                    null);

            final FlagDecision decision = resolve(ProtectionFlag.BUILD, Relationship.ALLY, layers);

            assertThat(decision.allowed()).isTrue();
            assertThat(decision.source()).isEqualTo(FlagSource.CLAIM);
        }

        @Test
        @DisplayName("an organisation overrides the world default")
        void organisationBeatsWorld() {
            final List<FlagResolver.FlagLayer> layers = FlagResolver.layers(
                    null, null, null, null,
                    allow(ProtectionFlag.PVP, Relationship.TOWN),
                    deny(ProtectionFlag.PVP, Relationship.TOWN));

            assertThat(resolve(ProtectionFlag.PVP, Relationship.TOWN, layers).source())
                    .isEqualTo(FlagSource.ORGANISATION);
        }

        @Test
        @DisplayName("a layer with no opinion on this flag is passed straight through")
        void silentLayersDefer() {
            final List<FlagResolver.FlagLayer> layers = FlagResolver.layers(
                    deny(ProtectionFlag.PVP, Relationship.VISITOR),
                    null, null, null,
                    allow(ProtectionFlag.BUILD, Relationship.VISITOR),
                    null);

            final FlagDecision decision = resolve(ProtectionFlag.BUILD, Relationship.VISITOR, layers);

            assertThat(decision.allowed())
                    .as("the admin layer said nothing about BUILD, so it must not block it")
                    .isTrue();
            assertThat(decision.source()).isEqualTo(FlagSource.ORGANISATION);
        }

        @Test
        @DisplayName("an opinion about another relationship does not answer this one")
        void opinionsAreScopedToTheirRelationship() {
            final List<FlagResolver.FlagLayer> layers = FlagResolver.layers(
                    null, null, null, null,
                    allow(ProtectionFlag.BUILD, Relationship.ALLY),
                    null);

            assertThat(resolve(ProtectionFlag.BUILD, Relationship.VISITOR, layers).source())
                    .isEqualTo(FlagSource.BUILT_IN);
        }

        @Test
        @DisplayName("empty layers are dropped, so they cannot shadow a later one")
        void emptyLayersAreDropped() {
            final List<FlagResolver.FlagLayer> layers = FlagResolver.layers(
                    FlagSettings.empty(), FlagSettings.empty(), null, null,
                    allow(ProtectionFlag.BUILD, Relationship.VISITOR), null);

            assertThat(layers).hasSize(1);
            assertThat(resolve(ProtectionFlag.BUILD, Relationship.VISITOR, layers).source())
                    .isEqualTo(FlagSource.ORGANISATION);
        }
    }

    @Nested
    @DisplayName("built-in defaults")
    class Defaults {

        @Test
        @DisplayName("a fresh install protects claims without any configuration")
        void claimsAreProtectedOutOfTheBox() {
            for (final ProtectionFlag flag : List.of(
                    ProtectionFlag.BUILD, ProtectionFlag.BREAK, ProtectionFlag.CONTAINER)) {
                assertThat(resolve(flag, Relationship.VISITOR, List.of()).allowed())
                        .as("%s for a visitor", flag)
                        .isFalse();
                assertThat(resolve(flag, Relationship.TOWN, List.of()).allowed())
                        .as("%s for a member", flag)
                        .isTrue();
            }
        }

        @Test
        @DisplayName("wilderness behaves like vanilla")
        void wildernessIsOpen() {
            for (final ProtectionFlag flag : List.of(
                    ProtectionFlag.BUILD, ProtectionFlag.BREAK, ProtectionFlag.CONTAINER,
                    ProtectionFlag.PVP, ProtectionFlag.EXPLOSIONS)) {
                assertThat(resolve(flag, Relationship.WILDERNESS, List.of()).allowed())
                        .as("%s in wilderness", flag)
                        .isTrue();
            }
        }

        @Test
        @DisplayName("a town is not an arena by default")
        void pvpIsOffInsideClaims() {
            assertThat(resolve(ProtectionFlag.PVP, Relationship.TOWN, List.of()).allowed()).isFalse();
        }

        @Test
        @DisplayName("a shop nobody may use is not a shop")
        void shopsAreUsableByAnyone() {
            assertThat(resolve(ProtectionFlag.SHOP_USE, Relationship.VISITOR, List.of()).allowed())
                    .isTrue();
        }

        @Test
        @DisplayName("event and war actions are never granted by default")
        void subsystemActionsNeedTheirOwnLayer() {
            for (final Relationship relationship : Relationship.values()) {
                assertThat(resolve(ProtectionFlag.WAR_ACTION, relationship, List.of()).allowed())
                        .as("WAR_ACTION for %s", relationship)
                        .isFalse();
            }
        }
    }

    @Nested
    @DisplayName("world flags")
    class WorldFlags {

        @Test
        @DisplayName("a piston is judged as wilderness whoever is standing there")
        void worldFlagsIgnoreTheActor() {
            final FlagDecision decision =
                    resolve(ProtectionFlag.PISTONS, Relationship.RESIDENT, List.of());

            assertThat(decision.relationship())
                    .as("otherwise a member standing nearby widens a border grief")
                    .isEqualTo(Relationship.WILDERNESS);
        }

        @Test
        @DisplayName("a world flag reads the wilderness row of a layer, not the actor's")
        void worldFlagsReadTheWildernessOpinion() {
            final List<FlagResolver.FlagLayer> layers = FlagResolver.layers(
                    null, null, null, null,
                    FlagSettings.builder()
                            .set(ProtectionFlag.EXPLOSIONS, Relationship.RESIDENT, true)
                            .set(ProtectionFlag.EXPLOSIONS, Relationship.WILDERNESS, false)
                            .build(),
                    null);

            assertThat(resolve(ProtectionFlag.EXPLOSIONS, Relationship.RESIDENT, layers).allowed())
                    .isFalse();
        }

        @Test
        @DisplayName("a world flag inside a claim is refused, though it resolves at wilderness")
        void worldFlagsDoNotInheritWildernessDefaults() {
            for (final ProtectionFlag flag : List.of(
                    ProtectionFlag.EXPLOSIONS, ProtectionFlag.FIRE_SPREAD,
                    ProtectionFlag.FLUID_FLOW, ProtectionFlag.PISTONS)) {
                final FlagDecision decision = resolve(flag, Relationship.TOWN, List.of());

                assertThat(decision.allowed())
                        .as("%s inside a claim: the world does not get to rearrange a town", flag)
                        .isFalse();
                assertThat(decision.relationship()).isEqualTo(Relationship.WILDERNESS);
            }
        }

        @Test
        @DisplayName("the same world flag outside a claim is vanilla")
        void worldFlagsAreVanillaOutsideClaims() {
            for (final ProtectionFlag flag : List.of(
                    ProtectionFlag.EXPLOSIONS, ProtectionFlag.FIRE_SPREAD,
                    ProtectionFlag.FLUID_FLOW, ProtectionFlag.PISTONS)) {
                assertThat(resolve(flag, Relationship.WILDERNESS, List.of()).allowed())
                        .as("%s in wilderness", flag)
                        .isTrue();
            }
        }

        @Test
        @DisplayName("a player flag keeps the actor's own relationship")
        void playerFlagsUseTheActualRelationship() {
            assertThat(resolve(ProtectionFlag.BUILD, Relationship.RESIDENT, List.of())
                    .relationship()).isEqualTo(Relationship.RESIDENT);
        }
    }

    @Nested
    @DisplayName("explaining and validating")
    class Diagnostics {

        @Test
        @DisplayName("a decision says what, for whom, and which layer decided")
        void decisionsExplainThemselves() {
            final List<FlagResolver.FlagLayer> layers = FlagResolver.layers(
                    deny(ProtectionFlag.BUILD, Relationship.TOWN), null, null, null, null, null);

            assertThat(resolve(ProtectionFlag.BUILD, Relationship.TOWN, layers).explain())
                    .isEqualTo("BUILD for TOWN is denied by ADMIN");
        }

        @Test
        @DisplayName("a configuration letting visitors do what allies cannot is reported")
        void nonMonotonicSettingsAreDetectable() {
            final FlagSettings settings = FlagSettings.builder()
                    .set(ProtectionFlag.BUILD, Relationship.VISITOR, true)
                    .set(ProtectionFlag.BUILD, Relationship.ALLY, false)
                    .build();

            assertThat(settings.firstNonMonotonic()).contains(ProtectionFlag.BUILD);
        }

        @Test
        @DisplayName("a sensible configuration reports nothing")
        void monotonicSettingsAreQuiet() {
            final FlagSettings settings = FlagSettings.builder()
                    .set(ProtectionFlag.BUILD, Relationship.VISITOR, false)
                    .set(ProtectionFlag.BUILD, Relationship.ALLY, false)
                    .set(ProtectionFlag.BUILD, Relationship.TOWN, true)
                    .build();

            assertThat(settings.firstNonMonotonic()).isEmpty();
        }

        @Test
        @DisplayName("setFrom grants a relationship and everything above it")
        void setFromCoversHigherRelationships() {
            final FlagSettings settings = FlagSettings.builder()
                    .setFrom(ProtectionFlag.CONTAINER, Relationship.ALLY, true)
                    .build();

            assertThat(settings.opinionOn(ProtectionFlag.CONTAINER, Relationship.TRUSTED)).isEmpty();
            assertThat(settings.opinionOn(ProtectionFlag.CONTAINER, Relationship.ALLY)).contains(true);
            assertThat(settings.opinionOn(ProtectionFlag.CONTAINER, Relationship.RESIDENT))
                    .contains(true);
        }
    }

    @Nested
    @DisplayName("an outlaw and PvP")
    class OutlawPvp {

        private static FlagDecision decide(final Relationship actor, final FlagSettings town) {
            return resolve(ProtectionFlag.PVP, actor,
                    List.of(new FlagResolver.FlagLayer(FlagSource.ORGANISATION, town)));
        }

        @Test
        @DisplayName("a town that lets visitors fight lets outlaws fight back")
        void outlawFollowsVisitor() {
            // The bug this exists for: PVP is resolved at the ATTACKER's rung, so an outlawed
            // player in a town with pvp enabled for visitors was hittable by everybody and could
            // not swing back - every attempt fell to the built-in refusal because no layer held an
            // opinion at OUTLAW. Being allowed to fight is exposure, not a privilege.
            final FlagSettings town = allow(ProtectionFlag.PVP, Relationship.VISITOR);

            assertThat(decide(Relationship.OUTLAW, town).allowed()).isTrue();
            assertThat(decide(Relationship.VISITOR, town).allowed()).isTrue();
        }

        @Test
        @DisplayName("and a town that forbids it forbids it for them too")
        void outlawFollowsVisitorBothWays() {
            final FlagSettings town = deny(ProtectionFlag.PVP, Relationship.VISITOR);

            assertThat(decide(Relationship.OUTLAW, town).allowed()).isFalse();
        }

        @Test
        @DisplayName("an explicit setting for outlaws still wins, so the row is never dead")
        void explicitOutlawSettingWins() {
            // A fallback rather than a collapse. Resolving outlaws AT the visitor rung would make
            // /town flag set pvp outlaw <...> a row nothing ever reads, which is the shape this
            // project keeps finding and closing.
            final FlagSettings town = FlagSettings.builder()
                    .set(ProtectionFlag.PVP, Relationship.VISITOR, true)
                    .set(ProtectionFlag.PVP, Relationship.OUTLAW, false)
                    .build();

            assertThat(decide(Relationship.OUTLAW, town).allowed()).isFalse();
            assertThat(decide(Relationship.VISITOR, town).allowed()).isTrue();
        }

        @Test
        @DisplayName("the decision names the rung that actually answered")
        void reportsTheRungThatAnswered() {
            // "Why was I denied" is a daily support question, and answering it with a rung nobody
            // configured would be worse than not answering.
            final FlagSettings town = allow(ProtectionFlag.PVP, Relationship.VISITOR);

            assertThat(decide(Relationship.OUTLAW, town).relationship())
                    .isEqualTo(Relationship.VISITOR);
        }

        @Test
        @DisplayName("an unconfigured town still refuses everybody, outlaw included")
        void unconfiguredIsUnchanged() {
            final FlagSettings nothing = FlagSettings.builder().build();

            assertThat(decide(Relationship.OUTLAW, nothing).allowed()).isFalse();
            assertThat(decide(Relationship.VISITOR, nothing).allowed()).isFalse();
        }

        @Test
        @DisplayName("no other flag falls back, so outlawry stays a demotion everywhere else")
        void onlyPvpFallsBack() {
            for (final ProtectionFlag flag : ProtectionFlag.values()) {
                if (flag != ProtectionFlag.PVP) {
                    assertThat(flag.fallbackRelationship(Relationship.OUTLAW))
                            .as("%s", flag)
                            .isEmpty();
                }
            }
            assertThat(ProtectionFlag.PVP.fallbackRelationship(Relationship.VISITOR)).isEmpty();
            assertThat(ProtectionFlag.PVP.fallbackRelationship(Relationship.OUTLAW))
                    .contains(Relationship.VISITOR);
        }
    }
}
