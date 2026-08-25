package net.riftbreaker.rifttowny.paper.protection;

import net.riftbreaker.rifttowny.domain.flag.ProtectionFlag;
import net.riftbreaker.rifttowny.domain.role.Permission;
import org.bukkit.Material;

import java.util.EnumSet;
import java.util.Objects;
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
 * <h2>What is and is not covered</h2>
 *
 * <p>Anything reaching none of the branches below is not protected by the interact rules, and that
 * is still a real limit rather than a solved problem. It is a much smaller one than it was: the
 * families are now closed by vanilla tag rather than by name, so a wood type added in a later
 * release arrives already covered instead of arriving unprotected.</p>
 *
 * <p><strong>The branches are ordered, and the order is the rule.</strong> The tool in hand comes
 * first, because where a (block, tool) pair matches, the tool is what the game does — an axe on a
 * copper door scrapes it rather than opening it, so testing the door first would judge a permanent
 * rewrite as a toggle. Only genuine pairs match, so an axe carried past a wooden door still leaves
 * the door a door. After that, theft is tested before toggling, because a shelf is both a thing you
 * click and a thing you can empty, and answering {@code INTERACT} for it would let a town that
 * opened its gates lose its stock.</p>
 */
public final class BlockActions {

    /**
     * Blocks whose contents can be taken. Theft, not vandalism.
     *
     * <p>The families that have a tag are not here; they are in the tag branch. What is left is the
     * blocks vanilla gives no tag for, which have to be listed by name and are the part of this
     * class that goes stale.</p>
     */
    private static final Set<Material> CONTAINERS = EnumSet.of(
            Material.CHEST, Material.TRAPPED_CHEST, Material.ENDER_CHEST, Material.BARREL,
            Material.HOPPER, Material.DROPPER, Material.DISPENSER, Material.CRAFTER,
            Material.FURNACE, Material.BLAST_FURNACE, Material.SMOKER, Material.BREWING_STAND,
            Material.CHISELED_BOOKSHELF, Material.DECORATED_POT, Material.LECTERN,
            Material.BEACON, Material.JUKEBOX,
            // A right-click hands the clicker items out of the block, which is the definition this
            // set is drawn on, whether or not the block looks like a chest.
            Material.VAULT, Material.SUSPICIOUS_SAND, Material.SUSPICIOUS_GRAVEL);

    /** Blocks that toggle or open without moving anything of value. */
    private static final Set<Material> SWITCHES = EnumSet.of(
            Material.LEVER, Material.REPEATER, Material.COMPARATOR, Material.DAYLIGHT_DETECTOR,
            Material.NOTE_BLOCK, Material.CRAFTING_TABLE, Material.ENCHANTING_TABLE,
            Material.GRINDSTONE, Material.STONECUTTER, Material.LOOM, Material.SMITHING_TABLE,
            Material.CARTOGRAPHY_TABLE, Material.ANVIL, Material.CHIPPED_ANVIL,
            Material.DAMAGED_ANVIL, Material.BELL, Material.FLOWER_POT, Material.COMPOSTER,
            Material.CAULDRON, Material.WATER_CAULDRON, Material.LAVA_CAULDRON,
            Material.POWDER_SNOW_CAULDRON, Material.RESPAWN_ANCHOR, Material.CAKE,
            Material.LODESTONE);

    /** Blocks a held tool rewrites in place, with no break and no place event to catch it. */
    private static final Set<Material> SHEARABLE = EnumSet.of(Material.PUMPKIN);

    private final BlockTags tags;

    public BlockActions(final BlockTags tags) {
        this.tags = Objects.requireNonNull(tags, "tags");
    }

    /**
     * What a right-click on this block is, if it is anything.
     *
     * <p>Empty means "no opinion" rather than "allowed": the caller has other rules — placing a
     * block is a {@code BlockPlaceEvent}, and hitting one is a break.</p>
     *
     * @param block what was clicked
     * @param inHand what the player was holding, or {@code null} for an empty hand. Consulted first,
     *        and only for the tools that genuinely rewrite the block they are used on
     */
    public Optional<Action> forRightClick(final Material block, final Material inHand) {
        if (block == null) {
            return Optional.empty();
        }
        // Tested before the block's own meaning, because where a pair matches, the tool is what
        // the game actually does: an axe on a copper door scrapes it rather than opening it. Only
        // genuine pairs match, so an axe carried past a wooden door still leaves it a door.
        final Optional<Action> rewritten = transformation(block, inHand);
        if (rewritten.isPresent()) {
            return rewritten;
        }
        if (CONTAINERS.contains(block)
                || tags.has(BlockTags.Kind.SHULKER_BOXES, block)
                // Copper chests and shelves are ordinary containers with a new name. A beehive is not
                // an inventory - org.bukkit.block.Beehive is an EntityBlockStorage - but what comes
                // out of it is the town's, and a bottle or shears is how it leaves.
                || tags.has(BlockTags.Kind.COPPER_CHESTS, block)
                || tags.has(BlockTags.Kind.WOODEN_SHELVES, block)
                || tags.has(BlockTags.Kind.BEEHIVES, block)) {
            // The ender chest is here for a different reason than the rest: its contents are the
            // player's own, but opening one still needs the town's consent to stand there doing it.
            return Optional.of(new Action(ProtectionFlag.CONTAINER, Permission.CONTAINER));
        }
        if (block == Material.DRAGON_EGG) {
            // Using it teleports it exactly as hitting it does, so the right-click needs the same
            // answer as forLeftClick. Protecting only the punch would have left the block this
            // was written for reachable by the other hand.
            return Optional.of(new Action(ProtectionFlag.BREAK, Permission.BREAK));
        }
        if (block == Material.END_PORTAL_FRAME) {
            // An eye of ender put in cannot be taken out, and a completed ring opens a portal
            // inside the claim. The block ends up permanently different, which is this file's own
            // definition of a build rather than of a toggle.
            return Optional.of(new Action(ProtectionFlag.BUILD, Permission.BUILD));
        }
        if (block == Material.SWEET_BERRY_BUSH || tags.has(BlockTags.Kind.CAVE_VINES, block)) {
            // Picking, not stealing. Permission.FARMLAND is documented as covering exactly this -
            // "trampling farmland, harvesting crops" - and filing it under CONTAINER would have
            // made "may pick berries" and "may open chests" the same grant.
            return Optional.of(new Action(ProtectionFlag.FARMLAND, Permission.FARMLAND));
        }
        if (block == Material.SPAWNER) {
            // Its own flag rather than the switch permission. Re-egging a town's mob farm is not the
            // same decision as being allowed to open its doors, and one flag for both would hand it
            // out to everybody who has the other.
            return Optional.of(new Action(ProtectionFlag.SPAWNER_USE, Permission.SPAWNER_USE));
        }
        if (SWITCHES.contains(block)
                || tags.has(BlockTags.Kind.DOORS, block)
                || tags.has(BlockTags.Kind.TRAPDOORS, block)
                || tags.has(BlockTags.Kind.FENCE_GATES, block)
                || tags.has(BlockTags.Kind.BUTTONS, block)
                || tags.has(BlockTags.Kind.BEDS, block)
                || tags.has(BlockTags.Kind.CANDLES, block)
                || tags.has(BlockTags.Kind.CANDLE_CAKES, block)
                // A sign is edited by right-clicking it, so an unprotected one lets a visitor
                // rewrite a town's notices, its shop prices and its plot markings.
                || tags.has(BlockTags.Kind.ALL_SIGNS, block)
                || tags.has(BlockTags.Kind.FLOWER_POTS, block)
                || tags.has(BlockTags.Kind.CAMPFIRES, block)) {
            return Optional.of(new Action(ProtectionFlag.INTERACT, Permission.SWITCH));
        }
        return Optional.empty();
    }

    /**
     * Rewriting a block with the tool in hand.
     *
     * <p>The one gap here that a list of materials could never close. Stripping a log, scraping or
     * waxing copper, turning grass into a path, tilling dirt and carving a pumpkin all change a
     * town's blocks permanently and none of them fires a break or a place event — they arrive as an
     * ordinary right-click on a block that, by itself, means nothing. Judged on the pair, so a tool
     * carried through a town does not deny every harmless click on the way.</p>
     *
     * <p>{@link ProtectionFlag#BUILD} rather than {@code BREAK}: the block is replaced rather than
     * removed, and a town that lets somebody build has already accepted them changing its walls.</p>
     */
    private Optional<Action> transformation(final Material block, final Material inHand) {
        if (inHand == null) {
            return Optional.empty();
        }
        final boolean rewrites =
                (tags.has(BlockTags.Kind.AXES, inHand)
                        && (tags.has(BlockTags.Kind.LOGS, block)
                                || tags.has(BlockTags.Kind.COPPER, block)))
                        || (inHand == Material.HONEYCOMB && tags.has(BlockTags.Kind.COPPER, block))
                        || (tags.has(BlockTags.Kind.SHOVELS, inHand)
                                && tags.has(BlockTags.Kind.DIRT, block))
                        || (tags.has(BlockTags.Kind.HOES, inHand)
                                && tags.has(BlockTags.Kind.DIRT, block))
                        || (inHand == Material.SHEARS && SHEARABLE.contains(block));
        return rewrites
                ? Optional.of(new Action(ProtectionFlag.BUILD, Permission.BUILD))
                : Optional.empty();
    }

    /**
     * What hitting this block is, if it is anything.
     *
     * <p>Almost always nothing: breaking a block is a {@code BlockBreakEvent} and is caught there.
     * The dragon egg is the exception in the game — punching it teleports it somewhere else in the
     * world and fires no break event at all, so it is the one block that can be taken off a town by
     * left-clicking it.</p>
     */
    public Optional<Action> forLeftClick(final Material block) {
        return block == Material.DRAGON_EGG
                ? Optional.of(new Action(ProtectionFlag.BREAK, Permission.BREAK))
                : Optional.empty();
    }

    /**
     * What standing on this block is, if it is anything.
     *
     * <p>Trampling crops and cracking turtle eggs are breaks in everything but name, and they are
     * the two ways to destroy a town's work without a {@code BlockBreakEvent} ever firing.</p>
     */
    public Optional<Action> forStandingOn(final Material material) {
        if (material == null) {
            return Optional.empty();
        }
        if (material == Material.FARMLAND) {
            return Optional.of(new Action(ProtectionFlag.FARMLAND, Permission.FARMLAND));
        }
        if (material == Material.TURTLE_EGG) {
            return Optional.of(new Action(ProtectionFlag.BREAK, Permission.BREAK));
        }
        if (tags.has(BlockTags.Kind.PRESSURE_PLATES, material) || material == Material.TRIPWIRE) {
            return Optional.of(new Action(ProtectionFlag.INTERACT, Permission.SWITCH));
        }
        return Optional.empty();
    }

    /** A flag and the role permission that goes with it. */
    public record Action(ProtectionFlag flag, Permission permission) {
    }
}
