package net.riftbreaker.rifttowny.paper.protection;

import org.bukkit.Material;
import org.bukkit.Tag;

/**
 * The real vanilla tags.
 *
 * <p>The whole of RiftTowny's dependence on {@code org.bukkit.Tag} lives in the switch below —
 * twenty-two lines, each a name checked against the API jar rather than remembered. Everything that
 * decides anything is in {@link BlockActions}, where it can be tested.</p>
 *
 * <p>A tag this server does not have resolves to {@code null} and is treated as containing nothing.
 * That is the same answer protection already gives for an unrecognised material, so a tag removed in
 * a later version degrades to the behaviour of the version before it rather than throwing on every
 * right-click.</p>
 */
public final class BukkitBlockTags implements BlockTags {

    @Override
    public boolean has(final Kind kind, final Material material) {
        if (kind == null || material == null) {
            return false;
        }
        final Tag<Material> tag = switch (kind) {
            case SHULKER_BOXES -> Tag.SHULKER_BOXES;
            case COPPER_CHESTS -> Tag.COPPER_CHESTS;
            case WOODEN_SHELVES -> Tag.WOODEN_SHELVES;
            case BEEHIVES -> Tag.BEEHIVES;
            case CAVE_VINES -> Tag.CAVE_VINES;
            case DOORS -> Tag.DOORS;
            case TRAPDOORS -> Tag.TRAPDOORS;
            case FENCE_GATES -> Tag.FENCE_GATES;
            case BUTTONS -> Tag.BUTTONS;
            case BEDS -> Tag.BEDS;
            case CANDLES -> Tag.CANDLES;
            case CANDLE_CAKES -> Tag.CANDLE_CAKES;
            case ALL_SIGNS -> Tag.ALL_SIGNS;
            case FLOWER_POTS -> Tag.FLOWER_POTS;
            case CAMPFIRES -> Tag.CAMPFIRES;
            case PRESSURE_PLATES -> Tag.PRESSURE_PLATES;
            case LOGS -> Tag.LOGS;
            case DIRT -> Tag.DIRT;
            case COPPER -> Tag.COPPER;
            case AXES -> Tag.ITEMS_AXES;
            case SHOVELS -> Tag.ITEMS_SHOVELS;
            case HOES -> Tag.ITEMS_HOES;
        };
        return tag != null && tag.isTagged(material);
    }
}
