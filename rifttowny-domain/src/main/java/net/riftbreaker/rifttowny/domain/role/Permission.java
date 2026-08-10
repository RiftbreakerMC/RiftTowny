package net.riftbreaker.rifttowny.domain.role;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Everything a role can be granted.
 *
 * <p>The enum constant name is the stored value in {@code rt_role_permission.permission}, so
 * renaming one is a migration. New constants may be added freely: a role simply does not hold a
 * permission that did not exist when it was created.</p>
 *
 * <p><strong>Sensitive</strong> permissions are the ones that can lose a town its land, its money
 * or its identity. An administrator may lock them so no configurable role can hold them, which is
 * what lets a server hand out generous roles without handing out a way to dissolve the town.</p>
 */
public enum Permission {

    // --- action: building and interacting -----------------------------------------------------

    BUILD(PermissionKind.ACTION, false),
    BREAK(PermissionKind.ACTION, false),
    /** Levers, buttons, doors, trapdoors, gates — anything that toggles without changing the world. */
    SWITCH(PermissionKind.ACTION, false),
    /** Chests, barrels, hoppers, furnaces, shulkers. Separate from {@link #SWITCH} because theft is not vandalism. */
    CONTAINER(PermissionKind.ACTION, false),
    /** Right-clicking villagers, item frames, armour stands, leads. */
    ENTITY_INTERACT(PermissionKind.ACTION, false),
    ENTITY_DAMAGE(PermissionKind.ACTION, false),
    /** Boats, minecarts, horses. */
    VEHICLE(PermissionKind.ACTION, false),
    /** Trampling farmland, harvesting crops. */
    FARMLAND(PermissionKind.ACTION, false),

    // --- action: town amenities ---------------------------------------------------------------

    SPAWNER_USE(PermissionKind.ACTION, false),
    SHOP_USE(PermissionKind.ACTION, false),
    TOWN_SPAWN(PermissionKind.ACTION, false),
    FLIGHT(PermissionKind.ACTION, false),
    CHAT_TOWN(PermissionKind.ACTION, false),
    CHAT_NATION(PermissionKind.ACTION, false),
    CHAT_ALLY(PermissionKind.ACTION, false),
    BANK_DEPOSIT(PermissionKind.ACTION, false),

    // --- management: people -------------------------------------------------------------------

    INVITE_RESIDENT(PermissionKind.MANAGEMENT, false),
    KICK_RESIDENT(PermissionKind.MANAGEMENT, false),
    MANAGE_TRUST(PermissionKind.MANAGEMENT, false),
    /** Create, clone, rename, reorder and delete roles. */
    MANAGE_ROLES(PermissionKind.MANAGEMENT, true),
    /** Grant and revoke roles. Distinct from editing them: handing out a role is not redefining it. */
    ASSIGN_ROLES(PermissionKind.MANAGEMENT, true),

    // --- management: territory ----------------------------------------------------------------

    CLAIM_LAND(PermissionKind.MANAGEMENT, false),
    UNCLAIM_LAND(PermissionKind.MANAGEMENT, true),
    MANAGE_AREAS(PermissionKind.MANAGEMENT, false),
    MANAGE_PLOTS(PermissionKind.MANAGEMENT, false),
    MANAGE_DISTRICTS(PermissionKind.MANAGEMENT, false),
    SET_HOMEBLOCK(PermissionKind.MANAGEMENT, true),
    SET_SPAWN(PermissionKind.MANAGEMENT, false),
    MANAGE_FLAGS(PermissionKind.MANAGEMENT, true),

    // --- management: money and policy ---------------------------------------------------------

    BANK_WITHDRAW(PermissionKind.MANAGEMENT, true),
    MANAGE_TAXES(PermissionKind.MANAGEMENT, true),
    MANAGE_SETTINGS(PermissionKind.MANAGEMENT, false),
    RENAME_ORGANISATION(PermissionKind.MANAGEMENT, true),
    MANAGE_SHOPS(PermissionKind.MANAGEMENT, false),
    MANAGE_SPAWNERS(PermissionKind.MANAGEMENT, false),
    MANAGE_DIPLOMACY(PermissionKind.MANAGEMENT, true),
    /** Join or leave a nation. */
    MANAGE_ALLEGIANCE(PermissionKind.MANAGEMENT, true),
    VIEW_LOGS(PermissionKind.MANAGEMENT, false),

    /**
     * Disband the organisation.
     *
     * <p>Always sensitive and never held by a default role. This is the only permission whose misuse
     * cannot be undone by another permission.</p>
     */
    DISBAND(PermissionKind.MANAGEMENT, true);

    private final PermissionKind kind;
    private final boolean sensitive;

    Permission(final PermissionKind kind, final boolean sensitive) {
        this.kind = kind;
        this.sensitive = sensitive;
    }

    public PermissionKind kind() {
        return kind;
    }

    /** Whether an administrator may lock this away from configurable roles. */
    public boolean sensitive() {
        return sensitive;
    }

    public boolean isAction() {
        return kind == PermissionKind.ACTION;
    }

    public boolean isManagement() {
        return kind == PermissionKind.MANAGEMENT;
    }

    /** Every permission of one kind, in declaration order. */
    public static List<Permission> of(final PermissionKind kind) {
        return Arrays.stream(values()).filter(permission -> permission.kind == kind).toList();
    }

    /**
     * Parses a stored or player-supplied value.
     *
     * <p>Empty rather than throwing for an unknown name: a permission removed in a later version
     * must not stop a role loading, and a typo in a command is a message, not a crash.</p>
     */
    public static Optional<Permission> parse(final String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        final String normalised = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        return Arrays.stream(values())
                .filter(permission -> permission.name().equals(normalised))
                .findFirst();
    }
}
