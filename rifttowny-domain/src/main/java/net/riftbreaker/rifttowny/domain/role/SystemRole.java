package net.riftbreaker.rifttowny.domain.role;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The three roles every organisation has and cannot delete.
 *
 * <p>They exist because the answers to "who runs this", "who belongs to this" and "who is everyone
 * else" have to exist before any configurable role can be defined in terms of them. A town whose
 * only role had been deleted would have no way to grant itself another.</p>
 *
 * <p>They may be renamed and re-decorated — a server can call its leader role Jarl — but not
 * deleted, not reordered, and in the leader's case not stripped of authority.</p>
 */
public enum SystemRole {

    /**
     * Mayor or king. Holds every permission, always.
     *
     * <p>Its permission set is not editable. A leader who could be stripped of
     * {@link Permission#ASSIGN_ROLES} would be unable to undo the change, which turns a
     * misconfiguration into a permanently broken town.</p>
     */
    LEADER(1_000, true),

    /** An ordinary resident. The baseline every member holds. */
    MEMBER(100, false),

    /** Anyone who is not a member. Holds whatever the organisation chooses to allow publicly. */
    VISITOR(0, false);

    private final int priority;
    private final boolean holdsEverything;

    SystemRole(final int priority, final boolean holdsEverything) {
        this.priority = priority;
        this.holdsEverything = holdsEverything;
    }

    /**
     * The fixed priority.
     *
     * <p>Configurable roles live in the gaps: above {@link #MEMBER} for officers, below it for
     * probationers. Nothing may sit at or above {@link #LEADER}, which is what keeps the leader able
     * to manage every other role.</p>
     */
    public int priority() {
        return priority;
    }

    /** Whether this role's permission set is fixed at "all of them". */
    public boolean holdsEveryPermission() {
        return holdsEverything;
    }

    /** The permissions a new organisation's copy of this role starts with. */
    public Set<Permission> defaultPermissions() {
        return switch (this) {
            case LEADER -> Set.of(Permission.values());
            case MEMBER -> Set.of(
                    Permission.BUILD, Permission.BREAK, Permission.SWITCH, Permission.CONTAINER,
                    Permission.ENTITY_INTERACT, Permission.VEHICLE, Permission.FARMLAND,
                    Permission.SPAWNER_USE, Permission.SHOP_USE, Permission.TOWN_SPAWN,
                    Permission.FLIGHT, Permission.CHAT_TOWN, Permission.CHAT_NATION,
                    Permission.BANK_DEPOSIT);
            // Visitors may look and trade, nothing else. Deliberately not SWITCH: an unlocked door
            // is a decision a town should make on purpose, through a flag or an area override.
            case VISITOR -> Set.of(Permission.SHOP_USE);
        };
    }

    /** The shipped display name, which an organisation may change. */
    public String defaultDisplayName(final boolean nation) {
        return switch (this) {
            case LEADER -> nation ? "King" : "Mayor";
            case MEMBER -> nation ? "Citizen" : "Resident";
            case VISITOR -> "Visitor";
        };
    }

    public static Optional<SystemRole> parse(final String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        final String normalised = raw.trim().toUpperCase(Locale.ROOT);
        for (final SystemRole role : values()) {
            if (role.name().equals(normalised)) {
                return Optional.of(role);
            }
        }
        return Optional.empty();
    }
}
