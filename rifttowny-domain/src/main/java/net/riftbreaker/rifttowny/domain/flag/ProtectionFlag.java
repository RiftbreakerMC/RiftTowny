package net.riftbreaker.rifttowny.domain.flag;

import java.util.Locale;
import java.util.Optional;

/**
 * Every protected action.
 *
 * <p>The constant name is the stored and configured value, so renaming one is a migration and a
 * configuration break. New constants may be added freely: a layer that has no opinion about a flag
 * simply defers, and the built-in default takes over.</p>
 */
public enum ProtectionFlag {

    // --- what a person does ------------------------------------------------------------------

    BUILD(Category.PLAYER),
    BREAK(Category.PLAYER),
    /** Levers, buttons, doors — toggling without changing the world. */
    INTERACT(Category.PLAYER),
    /** Chests, barrels, hoppers, furnaces. Separate from INTERACT because theft is not vandalism. */
    CONTAINER(Category.PLAYER),
    ENTITY_DAMAGE(Category.PLAYER),
    /** Boats, minecarts, horses. */
    VEHICLE(Category.PLAYER),
    FARMLAND(Category.PLAYER),
    SHOP_USE(Category.PLAYER),
    SPAWNER_USE(Category.PLAYER),
    PVP(Category.PLAYER),
    FLIGHT(Category.PLAYER),

    // --- what the world does -----------------------------------------------------------------

    /**
     * Explosions damaging terrain here.
     *
     * <p>A world flag, not a player one: the entity that lit the TNT is often gone, and asking
     * "what relationship does a creeper have to this town" is not a question with an answer.</p>
     */
    EXPLOSIONS(Category.WORLD),
    FIRE_SPREAD(Category.WORLD),
    FLUID_FLOW(Category.WORLD),
    /** Pistons pushing blocks across a border — the classic way to move a claim's blocks. */
    PISTONS(Category.WORLD),
    REDSTONE(Category.WORLD),
    MOB_SPAWNING(Category.WORLD),

    // --- what a subsystem does ---------------------------------------------------------------

    /** Actions an event grants, such as a supply drop opening a container. */
    EVENT_ACTION(Category.SYSTEM),
    /** Actions a war grants, such as placing a siege banner. */
    WAR_ACTION(Category.SYSTEM);

    /** What kind of thing the flag governs, which decides how a relationship applies to it. */
    public enum Category {
        /** A person did it, so their relationship to the land is the question. */
        PLAYER,
        /**
         * The world did it. Resolved at {@link Relationship#WILDERNESS} regardless of who is
         * nearby, because there is no meaningful actor to have a relationship.
         */
        WORLD,
        /** A subsystem did it, and is expected to supply its own override layer. */
        SYSTEM
    }

    private final Category category;

    ProtectionFlag(final Category category) {
        this.category = category;
    }

    public Category category() {
        return category;
    }

    /**
     * The shipped default when no layer has an opinion.
     *
     * <p>Deliberately conservative inside claims and permissive in wilderness: a server that
     * installs RiftTowny and configures nothing should find its towns protected, not find that
     * claiming land changed nothing.</p>
     *
     * <p><strong>{@code land} is a separate argument on purpose.</strong> It cannot be inferred
     * from the relationship, because {@link #effectiveRelationship} deliberately reports a world
     * flag at {@link Relationship#WILDERNESS} even inside a town. Reading "wilderness" as "nobody
     * owns this" was exactly that mistake: it made every explosion, fire and piston inside a claim
     * fall through to the vanilla default and be allowed, which is the opposite of what the switch
     * below says. One value cannot answer both "whose land is this" and "what standing does the
     * actor have", so it no longer tries.</p>
     *
     * @param land whose land this is, independent of the actor's standing on it
     */
    public boolean allowedByDefault(final Relationship relationship, final LandState land) {
        if (land == LandState.WILDERNESS) {
            // Unclaimed land behaves like vanilla, except for the two that would let somebody grief
            // across a border from outside it.
            return this != EVENT_ACTION && this != WAR_ACTION;
        }
        if (land == LandState.RUIN) {
            return allowedInRuin();
        }
        return switch (this) {
            // Members build; outsiders do not.
            case BUILD, BREAK, CONTAINER, FARMLAND, VEHICLE, SPAWNER_USE ->
                    relationship.isMember();
            // Doors and buttons for anyone the town has some relationship with.
            case INTERACT, ENTITY_DAMAGE -> relationship.isAtLeast(Relationship.ALLY);
            // A shop nobody may use is not a shop.
            case SHOP_USE -> true;
            // Off inside claims until a town turns it on; a town is not an arena by default.
            case PVP -> false;
            case FLIGHT -> relationship.isMember();
            // The world does not get to rearrange a town.
            case EXPLOSIONS, FIRE_SPREAD, FLUID_FLOW, PISTONS -> false;
            case REDSTONE, MOB_SPAWNING -> true;
            // Granted only by the subsystem that owns them, never by default.
            case EVENT_ACTION, WAR_ACTION -> false;
        };
    }

    /**
     * The shipped default on a ruin.
     *
     * <p><strong>The shell is protected; the contents are not.</strong> That is a gameplay decision
     * rather than a technical one, and it is the whole character of the feature, so it is stated
     * here rather than left implicit in a switch.</p>
     *
     * <p>Nobody may build or break: the buildings have to still be standing for reclaiming a ruin to
     * mean anything, and a town that lost a war would otherwise be dismantled before anyone could
     * take it on. Nobody has a relationship to a ruin either — there is no town left to have one
     * with — so this does not vary by actor.</p>
     *
     * <p>Chests, however, open. A ruin nobody may loot is a museum, and the plunder is what makes
     * a fallen town an event rather than an absence. Servers that disagree can say so: a ruin's
     * chunks keep answering to the admin and world flag layers.</p>
     *
     * <p>The world may not touch it at all. Explosions, fire, fluids and pistons are refused for the
     * same reason building is: whatever stands has to survive long enough to be reclaimed, or to be
     * restored by {@code RT-MOD-REGEN} when the window closes.</p>
     */
    public boolean allowedInRuin() {
        return switch (this) {
            case BUILD, BREAK, FLIGHT -> false;
            case CONTAINER, INTERACT, ENTITY_DAMAGE, VEHICLE, FARMLAND, SHOP_USE, SPAWNER_USE -> true;
            // Contested ground. A ruin is the one place a fight is the point.
            case PVP -> true;
            case EXPLOSIONS, FIRE_SPREAD, FLUID_FLOW, PISTONS -> false;
            case REDSTONE, MOB_SPAWNING -> true;
            case EVENT_ACTION, WAR_ACTION -> false;
        };
    }

    /**
     * The relationship this flag should actually be resolved at.
     *
     * <p>A {@link Category#WORLD} flag collapses to {@link Relationship#WILDERNESS}: piston and
     * fluid rules are about the land, not about whoever happens to be standing there, and letting a
     * nearby member's relationship widen them is how a border grief works.</p>
     */
    public Relationship effectiveRelationship(final Relationship actual) {
        return category == Category.WORLD ? Relationship.WILDERNESS : actual;
    }

    public static Optional<ProtectionFlag> parse(final String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        final String normalised = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        for (final ProtectionFlag flag : values()) {
            if (flag.name().equals(normalised)) {
                return Optional.of(flag);
            }
        }
        return Optional.empty();
    }
}
