package net.riftbreaker.rifttowny.paper.protection;

import net.riftbreaker.rifttowny.domain.flag.ProtectionFlag;
import net.riftbreaker.rifttowny.domain.role.Permission;
import org.bukkit.Material;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What touching a block is.
 *
 * <p>The first test this class has ever had, and the reason it had none is the thing being fixed:
 * {@code org.bukkit.Tag}'s constants come from a running server's registry and throw in a bare JVM,
 * so a class that consulted them directly could not be exercised at all. {@link BlockTags} is the
 * seam; this supplies a substitute for it.</p>
 *
 * <p><strong>What this proves.</strong> The policy — which flag and which permission each kind of
 * block answers with, and the order the branches are tried in. <strong>What it cannot prove.</strong>
 * That vanilla's {@code all_signs} really contains every sign: tag membership is server data and is
 * not in the API jar. That half is one line per tag in {@link BukkitBlockTags} and is verified
 * against the artifact rather than tested here.</p>
 */
class BlockActionsTest {

    /** A stand-in registry: a material is tagged if it was put in that tag here. */
    private static final class FakeTags implements BlockTags {

        private final Map<Kind, Set<Material>> members = new EnumMap<>(Kind.class);

        FakeTags with(final Kind kind, final Material... materials) {
            members.computeIfAbsent(kind, ignored -> EnumSet.noneOf(Material.class))
                    .addAll(Set.of(materials));
            return this;
        }

        @Override
        public boolean has(final Kind kind, final Material material) {
            return kind != null && material != null
                    && members.getOrDefault(kind, Set.of()).contains(material);
        }
    }

    /** One member of every tag protection consults, so each branch has something to match. */
    private static FakeTags vanillaish() {
        return new FakeTags()
                .with(BlockTags.Kind.SHULKER_BOXES, Material.SHULKER_BOX)
                .with(BlockTags.Kind.COPPER_CHESTS,
                        Material.COPPER_CHEST, Material.WAXED_OXIDIZED_COPPER_CHEST)
                .with(BlockTags.Kind.WOODEN_SHELVES, Material.OAK_SHELF, Material.BAMBOO_SHELF)
                .with(BlockTags.Kind.BEEHIVES, Material.BEEHIVE, Material.BEE_NEST)
                .with(BlockTags.Kind.CAVE_VINES, Material.CAVE_VINES, Material.CAVE_VINES_PLANT)
                .with(BlockTags.Kind.DOORS, Material.OAK_DOOR, Material.COPPER_DOOR)
                .with(BlockTags.Kind.TRAPDOORS, Material.OAK_TRAPDOOR)
                .with(BlockTags.Kind.FENCE_GATES, Material.OAK_FENCE_GATE)
                .with(BlockTags.Kind.BUTTONS, Material.STONE_BUTTON)
                .with(BlockTags.Kind.BEDS, Material.RED_BED)
                .with(BlockTags.Kind.CANDLES, Material.CANDLE)
                .with(BlockTags.Kind.CANDLE_CAKES, Material.CANDLE_CAKE)
                .with(BlockTags.Kind.ALL_SIGNS, Material.OAK_SIGN, Material.OAK_WALL_SIGN,
                        Material.MANGROVE_WALL_HANGING_SIGN)
                .with(BlockTags.Kind.FLOWER_POTS, Material.FLOWER_POT, Material.POTTED_CACTUS)
                .with(BlockTags.Kind.CAMPFIRES, Material.CAMPFIRE, Material.SOUL_CAMPFIRE)
                .with(BlockTags.Kind.PRESSURE_PLATES, Material.STONE_PRESSURE_PLATE)
                .with(BlockTags.Kind.LOGS, Material.OAK_LOG)
                .with(BlockTags.Kind.DIRT, Material.GRASS_BLOCK, Material.DIRT)
                .with(BlockTags.Kind.COPPER, Material.COPPER_BLOCK, Material.COPPER_DOOR)
                .with(BlockTags.Kind.AXES, Material.IRON_AXE, Material.NETHERITE_AXE)
                .with(BlockTags.Kind.SHOVELS, Material.IRON_SHOVEL)
                .with(BlockTags.Kind.HOES, Material.IRON_HOE);
    }

    private final BlockActions blocks = new BlockActions(vanillaish());

    private Optional<BlockActions.Action> click(final Material block) {
        return blocks.forRightClick(block, null);
    }

    private Optional<BlockActions.Action> click(final Material block, final Material inHand) {
        return blocks.forRightClick(block, inHand);
    }

    @Nested
    @DisplayName("theft")
    class Theft {

        @Test
        @DisplayName("a chest, a shulker box and every copper chest are containers")
        void containers() {
            assertThat(click(Material.CHEST)).contains(
                    new BlockActions.Action(ProtectionFlag.CONTAINER, Permission.CONTAINER));
            assertThat(click(Material.SHULKER_BOX)).isPresent();
            assertThat(click(Material.COPPER_CHEST).orElseThrow().flag())
                    .isEqualTo(ProtectionFlag.CONTAINER);
            assertThat(click(Material.WAXED_OXIDIZED_COPPER_CHEST).orElseThrow().flag())
                    .isEqualTo(ProtectionFlag.CONTAINER);
        }

        @Test
        @DisplayName("a shelf is a container, not a switch")
        void shelves() {
            // It is clicked like a lever and emptied like a chest. Answering INTERACT would let a
            // town that opened its gates lose its stock.
            assertThat(click(Material.OAK_SHELF).orElseThrow().flag())
                    .isEqualTo(ProtectionFlag.CONTAINER);
            assertThat(click(Material.BAMBOO_SHELF).orElseThrow().permission())
                    .isEqualTo(Permission.CONTAINER);
        }

        @Test
        @DisplayName("things that hand the clicker the town's goods count as taking")
        void harvests() {
            // Honey out of a hive, loot out of a vault, the one buried item in suspicious sand.
            for (final Material block : new Material[] {
                    Material.BEEHIVE, Material.BEE_NEST, Material.VAULT,
                    Material.SUSPICIOUS_SAND, Material.SUSPICIOUS_GRAVEL }) {
                assertThat(click(block).orElseThrow().flag())
                        .as("%s", block)
                        .isEqualTo(ProtectionFlag.CONTAINER);
            }
        }

        @Test
        @DisplayName("picking a crop is farmland, not theft")
        void cropsAreNotContainers() {
            // Permission.FARMLAND is documented as "trampling farmland, harvesting crops", so this
            // is the flag that already means this. Filing it under CONTAINER would have made "may
            // pick berries" and "may open every chest in town" the same grant.
            for (final Material crop : new Material[] {
                    Material.SWEET_BERRY_BUSH, Material.CAVE_VINES, Material.CAVE_VINES_PLANT }) {
                assertThat(click(crop)).as("%s", crop).contains(
                        new BlockActions.Action(ProtectionFlag.FARMLAND, Permission.FARMLAND));
            }
        }
    }

    @Nested
    @DisplayName("touching")
    class Touching {

        @Test
        @DisplayName("a sign of any shape is an interaction")
        void signs() {
            // Editing one rewrites a town's notices, its shop prices and its plot markings. Nothing
            // leaves the block, so it is not theft.
            for (final Material sign : new Material[] {
                    Material.OAK_SIGN, Material.OAK_WALL_SIGN,
                    Material.MANGROVE_WALL_HANGING_SIGN }) {
                assertThat(click(sign)).as("%s", sign).contains(
                        new BlockActions.Action(ProtectionFlag.INTERACT, Permission.SWITCH));
            }
        }

        @Test
        @DisplayName("doors, plates, pots and campfires are interactions")
        void switches() {
            for (final Material block : new Material[] {
                    Material.LEVER, Material.OAK_DOOR, Material.OAK_TRAPDOOR,
                    Material.OAK_FENCE_GATE, Material.STONE_BUTTON, Material.RED_BED,
                    Material.CANDLE, Material.CANDLE_CAKE, Material.FLOWER_POT,
                    Material.POTTED_CACTUS, Material.CAMPFIRE, Material.SOUL_CAMPFIRE,
                    Material.LODESTONE }) {
                assertThat(click(block).orElseThrow().flag())
                        .as("%s", block)
                        .isEqualTo(ProtectionFlag.INTERACT);
            }
        }

        @Test
        @DisplayName("a spawner has its own flag rather than the switch permission")
        void spawner() {
            // Re-egging a town's mob farm is not the same decision as opening its doors, and one
            // flag for both would hand it to everybody who has the other.
            assertThat(click(Material.SPAWNER)).contains(
                    new BlockActions.Action(ProtectionFlag.SPAWNER_USE, Permission.SPAWNER_USE));
        }

        @Test
        @DisplayName("scenery is still nothing")
        void scenery() {
            assertThat(click(Material.STONE)).isEmpty();
            assertThat(click(null)).isEmpty();
        }
    }

    @Nested
    @DisplayName("what the tool in hand does")
    class Tools {

        @Test
        @DisplayName("stripping, scraping, waxing, pathing, tilling and carving are builds")
        void rewrites() {
            assertThat(click(Material.OAK_LOG, Material.IRON_AXE)).contains(
                    new BlockActions.Action(ProtectionFlag.BUILD, Permission.BUILD));
            assertThat(click(Material.COPPER_BLOCK, Material.NETHERITE_AXE).orElseThrow().flag())
                    .isEqualTo(ProtectionFlag.BUILD);
            assertThat(click(Material.COPPER_BLOCK, Material.HONEYCOMB).orElseThrow().flag())
                    .isEqualTo(ProtectionFlag.BUILD);
            assertThat(click(Material.GRASS_BLOCK, Material.IRON_SHOVEL).orElseThrow().flag())
                    .isEqualTo(ProtectionFlag.BUILD);
            assertThat(click(Material.DIRT, Material.IRON_HOE).orElseThrow().flag())
                    .isEqualTo(ProtectionFlag.BUILD);
            assertThat(click(Material.PUMPKIN, Material.SHEARS).orElseThrow().flag())
                    .isEqualTo(ProtectionFlag.BUILD);
        }

        @Test
        @DisplayName("the same log with an empty hand, or the wrong tool, is nothing")
        void onlyThePair() {
            // Judged on the pair. A tool carried through a town must not deny every click on the way.
            assertThat(click(Material.OAK_LOG, null)).isEmpty();
            assertThat(click(Material.OAK_LOG, Material.IRON_SHOVEL)).isEmpty();
            assertThat(click(Material.STONE, Material.IRON_AXE)).isEmpty();
            assertThat(click(Material.COPPER_BLOCK, Material.SHEARS)).isEmpty();
        }

        @Test
        @DisplayName("a tool that does nothing to the block leaves the block's own meaning")
        void theBlockWinsWhenThereIsNoPair() {
            // An axe does not turn a wooden door into a build, and holding one near a chest does
            // not downgrade the chest: neither is a pair the game does anything with.
            assertThat(click(Material.OAK_DOOR, Material.IRON_AXE).orElseThrow().flag())
                    .isEqualTo(ProtectionFlag.INTERACT);
            assertThat(click(Material.CHEST, Material.IRON_AXE).orElseThrow().flag())
                    .isEqualTo(ProtectionFlag.CONTAINER);
            assertThat(click(Material.SPAWNER, Material.IRON_HOE).orElseThrow().flag())
                    .isEqualTo(ProtectionFlag.SPAWNER_USE);
        }

        @Test
        @DisplayName("but a tool that does rewrite the block beats what the block otherwise means")
        void thePairWinsWhenThereIsOne() {
            // A copper door is a door and is also scrapeable, and in the game the axe wins - using
            // one on it strips the oxidation rather than opening it. Judging it as INTERACT would
            // let anybody who may open a gate permanently restyle the town's copper.
            assertThat(click(Material.COPPER_DOOR, Material.IRON_AXE).orElseThrow().flag())
                    .isEqualTo(ProtectionFlag.BUILD);
            assertThat(click(Material.COPPER_DOOR, Material.HONEYCOMB).orElseThrow().flag())
                    .isEqualTo(ProtectionFlag.BUILD);

            // With an empty hand it is still just a door.
            assertThat(click(Material.COPPER_DOOR).orElseThrow().flag())
                    .isEqualTo(ProtectionFlag.INTERACT);
        }
    }

    @Nested
    @DisplayName("hitting and standing")
    class Other {

        @Test
        @DisplayName("a dragon egg is the one block taken by punching it")
        void dragonEgg() {
            // Punching it teleports it elsewhere in the world and fires no BlockBreakEvent, so the
            // break listener never sees it leave.
            assertThat(blocks.forLeftClick(Material.DRAGON_EGG)).contains(
                    new BlockActions.Action(ProtectionFlag.BREAK, Permission.BREAK));
        }

        @Test
        @DisplayName("and by using it, which teleports it exactly the same way")
        void dragonEggBothWays() {
            // Protecting only the punch would have left the block this was written for reachable
            // by the other hand.
            assertThat(click(Material.DRAGON_EGG)).contains(
                    new BlockActions.Action(ProtectionFlag.BREAK, Permission.BREAK));
        }

        @Test
        @DisplayName("an end portal frame is a permanent rewrite, not a toggle")
        void endPortalFrame() {
            // An eye of ender put in cannot be taken out, and a completed ring opens a portal in
            // the claim. The block ends up permanently different, which is a build.
            assertThat(click(Material.END_PORTAL_FRAME)).contains(
                    new BlockActions.Action(ProtectionFlag.BUILD, Permission.BUILD));
        }

        @Test
        @DisplayName("hitting anything else is left to the break listener")
        void otherLeftClicks() {
            assertThat(blocks.forLeftClick(Material.CHEST)).isEmpty();
            assertThat(blocks.forLeftClick(Material.STONE)).isEmpty();
            assertThat(blocks.forLeftClick(null)).isEmpty();
        }

        @Test
        @DisplayName("farmland, turtle eggs and plates are what standing on something can be")
        void standing() {
            assertThat(blocks.forStandingOn(Material.FARMLAND)).contains(
                    new BlockActions.Action(ProtectionFlag.FARMLAND, Permission.FARMLAND));
            assertThat(blocks.forStandingOn(Material.TURTLE_EGG).orElseThrow().flag())
                    .isEqualTo(ProtectionFlag.BREAK);
            assertThat(blocks.forStandingOn(Material.STONE_PRESSURE_PLATE).orElseThrow().flag())
                    .isEqualTo(ProtectionFlag.INTERACT);
            assertThat(blocks.forStandingOn(Material.TRIPWIRE).orElseThrow().permission())
                    .isEqualTo(Permission.SWITCH);
            assertThat(blocks.forStandingOn(Material.STONE)).isEmpty();
            assertThat(blocks.forStandingOn(null)).isEmpty();
        }
    }
}
