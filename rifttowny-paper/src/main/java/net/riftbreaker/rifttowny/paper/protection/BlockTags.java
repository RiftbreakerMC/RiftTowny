package net.riftbreaker.rifttowny.paper.protection;

import org.bukkit.Material;

/**
 * Which vanilla tag a material belongs to.
 *
 * <p>One method behind an interface, for one reason: {@code org.bukkit.Tag}'s constants are
 * initialised from a running server's registry, so touching {@code Tag.DOORS} in a unit test throws
 * before the test body runs. {@link BlockActions} consulted those constants directly, which is why
 * it is the one class in the protection path that has never had a test — and why a hundred and
 * nineteen materials went unrecognised in it without anything failing.</p>
 *
 * <p><strong>What a test using a substitute proves, and what it does not.</strong> It proves the
 * policy: that a sign is an interaction and a shelf is a container, that the branches are ordered so
 * the narrower wins, and that an axe on a log is a build. It cannot prove that vanilla's
 * {@code all_signs} really contains {@code MANGROVE_WALL_HANGING_SIGN} — tag membership is server
 * data and is not in the API jar at all. That half is one verified line per tag in
 * {@link BukkitBlockTags} and nothing else, which is deliberately the smallest untestable surface
 * the split allows.</p>
 */
@FunctionalInterface
public interface BlockTags {

    /** Whether this material carries that tag. Null-safe: nothing is tagged. */
    boolean has(Kind kind, Material material);

    /**
     * The tags protection asks about.
     *
     * <p>Named after the vanilla tag rather than after what RiftTowny does with it, so the mapping
     * in {@link BukkitBlockTags} can be read against the API with no interpretation in between. The
     * last three are item tags rather than block tags — what is in the hand, not what was clicked.
     * </p>
     */
    enum Kind {
        SHULKER_BOXES,
        COPPER_CHESTS,
        WOODEN_SHELVES,
        BEEHIVES,
        CAVE_VINES,
        DOORS,
        TRAPDOORS,
        FENCE_GATES,
        BUTTONS,
        BEDS,
        CANDLES,
        CANDLE_CAKES,
        ALL_SIGNS,
        FLOWER_POTS,
        CAMPFIRES,
        PRESSURE_PLATES,
        LOGS,
        DIRT,
        COPPER,
        AXES,
        SHOVELS,
        HOES
    }
}
