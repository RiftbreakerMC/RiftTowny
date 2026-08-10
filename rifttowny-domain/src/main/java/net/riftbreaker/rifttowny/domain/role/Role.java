package net.riftbreaker.rifttowny.domain.role;

import net.riftbreaker.rifttowny.domain.org.ChangeDenial;
import net.riftbreaker.rifttowny.domain.org.OrganisationScope;
import net.riftbreaker.rifttowny.domain.org.Outcome;

import java.time.Instant;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * One role: a name, some decoration, a rank, and a set of permissions.
 *
 * <p>Immutable, like the organisation aggregates. Rules that concern only this role live here;
 * anything that needs to compare two roles — who may manage whom, whether a priority is free —
 * belongs to {@link RoleBook}, because a single role cannot see the others.</p>
 */
public final class Role {

    private final RoleId id;
    private final OrganisationScope scope;
    private final UUID organisationId;
    private final String name;
    private final String nameNormalised;
    private final String displayName;
    private final String icon;
    private final String chatPrefix;
    private final int priority;
    private final SystemRole systemRole;
    private final Set<Permission> permissions;
    private final Instant createdAt;

    private Role(
            final RoleId id,
            final OrganisationScope scope,
            final UUID organisationId,
            final String name,
            final String displayName,
            final String icon,
            final String chatPrefix,
            final int priority,
            final SystemRole systemRole,
            final Set<Permission> permissions,
            final Instant createdAt
    ) {
        this.id = id;
        this.scope = scope;
        this.organisationId = organisationId;
        this.name = name;
        this.nameNormalised = name.toLowerCase(Locale.ROOT);
        this.displayName = displayName;
        this.icon = icon;
        this.chatPrefix = chatPrefix;
        this.priority = priority;
        this.systemRole = systemRole;
        // A system role that holds everything is materialised as the full set rather than as a flag
        // checked at every call site. One representation, so a permission added later is picked up
        // by the leader automatically on reload.
        this.permissions = systemRole != null && systemRole.holdsEveryPermission()
                ? Collections.unmodifiableSet(EnumSet.allOf(Permission.class))
                : Collections.unmodifiableSet(copyOf(permissions));
        this.createdAt = createdAt;
    }

    /** Creates one of the three roles every organisation starts with. */
    public static Role system(
            final SystemRole systemRole,
            final OrganisationScope scope,
            final UUID organisationId,
            final Instant now
    ) {
        Objects.requireNonNull(systemRole, "systemRole");
        Objects.requireNonNull(scope, "scope");
        final String display = systemRole.defaultDisplayName(scope == OrganisationScope.NATION);
        return new Role(
                RoleId.random(), scope, Objects.requireNonNull(organisationId, "organisationId"),
                display, display, null, null, systemRole.priority(), systemRole,
                systemRole.defaultPermissions(), Objects.requireNonNull(now, "now"));
    }

    /** Creates a configurable role. */
    public static Role custom(
            final RoleId id,
            final OrganisationScope scope,
            final UUID organisationId,
            final String name,
            final int priority,
            final Set<Permission> permissions,
            final Instant now
    ) {
        return new Role(
                Objects.requireNonNull(id, "id"),
                Objects.requireNonNull(scope, "scope"),
                Objects.requireNonNull(organisationId, "organisationId"),
                requireName(name), requireName(name), null, null, priority, null,
                permissions, Objects.requireNonNull(now, "now"));
    }

    /** Rebuilds a role from storage. */
    public static Role restore(
            final RoleId id,
            final OrganisationScope scope,
            final UUID organisationId,
            final String name,
            final String displayName,
            final String icon,
            final String chatPrefix,
            final int priority,
            final SystemRole systemRole,
            final Set<Permission> permissions,
            final Instant createdAt
    ) {
        return new Role(
                Objects.requireNonNull(id, "id"), Objects.requireNonNull(scope, "scope"),
                Objects.requireNonNull(organisationId, "organisationId"), requireName(name),
                displayName == null ? requireName(name) : displayName, icon, chatPrefix,
                priority, systemRole, permissions, Objects.requireNonNull(createdAt, "createdAt"));
    }

    /**
     * Renames the role.
     *
     * <p>Permitted on system roles: a server may call its leader Jarl. Uniqueness within the
     * organisation is {@link RoleBook}'s to check, since this role cannot see its siblings.</p>
     */
    public Outcome<Role> renameTo(final String newName) {
        final String trimmed = requireName(newName);
        if (trimmed.equals(name)) {
            return Outcome.denied(ChangeDenial.NAME_UNCHANGED);
        }
        return Outcome.applied(new Role(
                id, scope, organisationId, trimmed, displayName, icon, chatPrefix, priority,
                systemRole, permissions, createdAt));
    }

    /** Changes the display name, icon and chat prefix. Always allowed, including on system roles. */
    public Role decorate(final String newDisplayName, final String newIcon, final String newPrefix) {
        return new Role(
                id, scope, organisationId, name,
                newDisplayName == null || newDisplayName.isBlank() ? name : newDisplayName,
                newIcon, newPrefix, priority, systemRole, permissions, createdAt);
    }

    /** Grants a permission. */
    public Outcome<Role> grant(final Permission permission) {
        Objects.requireNonNull(permission, "permission");
        if (isLeader()) {
            return Outcome.denied(ChangeDenial.LEADER_PERMISSIONS_ARE_FIXED);
        }
        if (permissions.contains(permission)) {
            return Outcome.denied(ChangeDenial.PERMISSION_ALREADY_GRANTED);
        }
        final EnumSet<Permission> updated = copyOf(permissions);
        updated.add(permission);
        return Outcome.applied(withPermissions(updated));
    }

    /** Revokes a permission. */
    public Outcome<Role> revoke(final Permission permission) {
        Objects.requireNonNull(permission, "permission");
        if (isLeader()) {
            return Outcome.denied(ChangeDenial.LEADER_PERMISSIONS_ARE_FIXED);
        }
        if (!permissions.contains(permission)) {
            return Outcome.denied(ChangeDenial.PERMISSION_NOT_GRANTED);
        }
        final EnumSet<Permission> updated = copyOf(permissions);
        updated.remove(permission);
        return Outcome.applied(withPermissions(updated));
    }

    /** Moves the role in the ranking. System-role priorities are fixed. */
    public Outcome<Role> reprioritise(final int newPriority) {
        if (systemRole != null) {
            return Outcome.denied(ChangeDenial.SYSTEM_ROLE_PRIORITY_IS_FIXED);
        }
        if (newPriority >= SystemRole.LEADER.priority()) {
            return Outcome.denied(ChangeDenial.PRIORITY_RESERVED_FOR_LEADER);
        }
        return Outcome.applied(new Role(
                id, scope, organisationId, name, displayName, icon, chatPrefix, newPriority,
                null, permissions, createdAt));
    }

    /** A copy of this role under a new id and name, keeping its permissions. */
    public Role copyAs(final RoleId newId, final String newName, final int newPriority) {
        return new Role(
                Objects.requireNonNull(newId, "newId"), scope, organisationId, requireName(newName),
                requireName(newName), icon, chatPrefix, newPriority, null, permissions, createdAt);
    }

    public boolean allows(final Permission permission) {
        return permissions.contains(permission);
    }

    /** Whether this role outranks another. Strict: equal priority is not enough. */
    public boolean outranks(final Role other) {
        return other != null && priority > other.priority;
    }

    public boolean isSystem() {
        return systemRole != null;
    }

    public boolean isLeader() {
        return systemRole == SystemRole.LEADER;
    }

    public RoleId id() {
        return id;
    }

    public OrganisationScope scope() {
        return scope;
    }

    public UUID organisationId() {
        return organisationId;
    }

    public String name() {
        return name;
    }

    /** Lower-cased name, the uniqueness key within an organisation. */
    public String nameNormalised() {
        return nameNormalised;
    }

    public String displayName() {
        return displayName;
    }

    public Optional<String> icon() {
        return Optional.ofNullable(icon);
    }

    public Optional<String> chatPrefix() {
        return Optional.ofNullable(chatPrefix);
    }

    public int priority() {
        return priority;
    }

    public Optional<SystemRole> systemRole() {
        return Optional.ofNullable(systemRole);
    }

    public Set<Permission> permissions() {
        return permissions;
    }

    public Instant createdAt() {
        return createdAt;
    }

    private Role withPermissions(final Set<Permission> updated) {
        return new Role(
                id, scope, organisationId, name, displayName, icon, chatPrefix, priority,
                systemRole, updated, createdAt);
    }

    private static EnumSet<Permission> copyOf(final Set<Permission> source) {
        return source == null || source.isEmpty()
                ? EnumSet.noneOf(Permission.class)
                : EnumSet.copyOf(source);
    }

    private static String requireName(final String value) {
        Objects.requireNonNull(value, "name");
        final String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("a role name must not be blank");
        }
        if (trimmed.length() > 32) {
            throw new IllegalArgumentException(
                    "a role name must fit the 32-character column, got " + trimmed.length());
        }
        return trimmed;
    }

    @Override
    public boolean equals(final Object other) {
        return other instanceof Role role && id.equals(role.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "Role[" + name + " @" + priority + (systemRole == null ? "" : " " + systemRole) + ']';
    }
}
