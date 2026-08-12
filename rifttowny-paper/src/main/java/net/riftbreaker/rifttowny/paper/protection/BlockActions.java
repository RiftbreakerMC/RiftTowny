package net.riftbreaker.rifttowny.paper.protection;

import net.riftbreaker.rifttowny.domain.flag.ProtectionFlag;
import net.riftbreaker.rifttowny.domain.role.Permission;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * What touching a block actually is.
 *
 * <p>Bukkit reports one {@code PlayerInteractEvent} for opening a chest, flipping a lever and
 * sleeping in a bed. Protection has to tell them apart, because a town that lets visitors open its
 * doors has not agreed to let them empty its chests — and that distinction is exactly the one
 * {@link ProtectionFlag#CONTAINER} exists to make.</p>
 *
 * <p>Matched on {@link Material} rather than on the block state. {@code getState()} snapshots a
 * container's whole inventory, and doing that on every right-click to discover that it <em>is</em> a
 * container would be the most expensive part of the check.</p>
 *
 * <p>Anything not listed here is not protected by the interact rules. That is deliberate and it is a
 * real limit: an unrecognised interactive block behaves as though it were scenery. New blocks are
 * added by name, and the list is the honest record of what is covered.</p>
 */
public final class BlockActions {

    /** Blocks whose contents can be taken. Theft, not vandalism. */
    private static final Set<Material> CONTAINERS = EnumSet.of(
            Material.CHEST, Material.TRAPPED_CHEST, Material.ENDER_CHEST, Material.BARREL,
            Material.HOPPER, Material.DROPPER, Material.DISPENSER, Material.CRAFTER,
            Material.FURNACE, Material.BLAST_FURNACE, Material.SMOKER, Material.BREWING_STAND,
            Material.CHISELED_BOOKSHELF, Material.DECORATED_POT, Material.LECTERN,
            Material.BEACON, Material.JUKEBOX);

    /** Blocks that toggle or open without moving anything of value. */
    private static final Set<Material> SWITCHES = EnumSet.of(
            Material.LEVER, Material.REPEATER, Material.COMPARATOR, Material.DAYLIGHT_DETECTOR,
            Material.NOTE_BLOCK, Material.CRAFTING_TABLE, Material.ENCHANTING_TABLE,
            Material.GRINDSTONE, Material.STONECUTTER, Material.LOOM, Material.SMITHING_TABLE,
            Material.CARTOGRAPHY_TABLE, Material.ANVIL, Material.CHIPPED_ANVIL,
            Material.DAMAGED_ANVIL, Material.BELL, Material.FLOWER_POT, Material.COMPOSTER,
            Material.CAULDRON, Material.WATER_CAULDRON, Material.LAVA_CAULDRON,
            Material.POWDER_SNOW_CAULDRON, Material.RESPAWN_ANCHOR, Material.CAKE);

    private BlockActions() {
    }

    /**
     * What a right-click on this block is, if it is anything.
     *
     * <p>Empty means "no opinion" rather than "allowed": the caller has other rules — placing a
     * block is a {@code BlockPlaceEvent}, and hitting one is a break.</p>
     */
    public static Optional<Action> forRightClick(final Block block) {
        return block == null ? Optional.empty() : forRightClick(block.getType());
    }

    /** The same, as a function of the material alone. */
    public static Optional<Action> forRightClick(final Material material) {
        if (material == null) {
            return Optional.empty();
        }
        if (CONTAINERS.contains(material) || Tag.SHULKER_BOXES.isTagged(material)) {
            // The ender chest is here for a different reason than the rest: its contents are the
            // player's own, but opening one still needs the town's consent to stand there doing it.
            return Optional.of(new Action(ProtectionFlag.CONTAINER, Permission.CONTAINER));
        }
        if (SWITCHES.contains(material)
                || Tag.DOORS.isTagged(material)
                || Tag.TRAPDOORS.isTagged(material)
                || Tag.FENCE_GATES.isTagged(material)
                || Tag.BUTTONS.isTagged(material)
                || Tag.BEDS.isTagged(material)
                || Tag.CANDLES.isTagged(material)
                || Tag.CANDLE_CAKES.isTagged(material)) {
            return Optional.of(new Action(ProtectionFlag.INTERACT, Permission.SWITCH));
        }
        return Optional.empty();
    }

    /**
     * What standing on this block is, if it is anything.
     *
     * <p>Trampling crops and cracking turtle eggs are breaks in everything but name, and they are
     * the two ways to destroy a town's work without a {@code BlockBreakEvent} ever firing.</p>
     */
    public static Optional<Action> forStandingOn(final Block block) {
        return block == null ? Optional.empty() : forStandingOn(block.getType());
    }

    /** The same, as a function of the material alone. */
    public static Optional<Action> forStandingOn(final Material material) {
        if (material == null) {
            return Optional.empty();
        }
        if (material == Material.FARMLAND) {
            return Optional.of(new Action(ProtectionFlag.FARMLAND, Permission.FARMLAND));
        }
        if (material == Material.TURTLE_EGG) {
            return Optional.of(new Action(ProtectionFlag.BREAK, Permission.BREAK));
        }
        if (Tag.PRESSURE_PLATES.isTagged(material) || material == Material.TRIPWIRE) {
            return Optional.of(new Action(ProtectionFlag.INTERACT, Permission.SWITCH));
        }
        return Optional.empty();
    }

    /** A flag and the role permission that goes with it. */
    public record Action(ProtectionFlag flag, Permission permission) {
    }
}
