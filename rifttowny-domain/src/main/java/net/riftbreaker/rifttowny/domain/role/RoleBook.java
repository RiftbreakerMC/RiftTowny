package net.riftbreaker.rifttowny.domain.role;

import net.riftbreaker.rifttowny.domain.event.DomainEvent;
import net.riftbreaker.rifttowny.domain.org.ChangeDenial;
import net.riftbreaker.rifttowny.domain.org.OrganisationScope;
import net.riftbreaker.rifttowny.domain.org.Outcome;
import net.riftbreaker.rifttowny.domain.org.ResidentId;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Every role in one organisation, plus who holds which.
 *
 * <p>The rules that need to see more than one role live here: that names are unique, that
 * priorities do not collide, that nothing outranks the leader, and — the important one — that a
 * role may only manage roles of strictly lower priority. A single {@link Role} cannot enforce any
 * of those, because it cannot see its siblings.</p>
 *
 * <p>Assignments live here too rather than on {@link Role}, because the interesting questions are
 * about a person across roles: what may they do, and do they outrank the role they are trying to
 * edit. Splitting the two would mean loading both to answer either.</p>
 *
 * <p>Immutable. Every change returns a new book inside an {@link Outcome}.</p>
 */
public final class RoleBook {

    private final OrganisationScope scope;
    private final UUID organisationId;
    private final Map<RoleId, Role> roles;
    private final Map<ResidentId, Set<RoleId>> assignments;

    private RoleBook(
            final OrganisationScope scope,
            final UUID organisationId,
            final Map<RoleId, Role> roles,
            final Map<ResidentId, Set<RoleId>> assignments
    ) {
        this.scope = scope;
        this.organisationId = organisationId;
        this.roles = Collections.unmodifiableMap(new LinkedHashMap<>(roles));
        final Map<ResidentId, Set<RoleId>> copied = new LinkedHashMap<>();
        assignments.forEach((resident, held) ->
                copied.put(resident, Collections.unmodifiableSet(new LinkedHashSet<>(held))));
        this.assignments = Collections.unmodifiableMap(copied);
    }

    /** A new organisation's roles: leader, member and visitor, and nothing else. */
    public static RoleBook defaultsFor(
            final OrganisationScope scope, final UUID organisationId, final Instant now) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(organisationId, "organisationId");
        final Map<RoleId, Role> roles = new LinkedHashMap<>();
        for (final SystemRole system : SystemRole.values()) {
            final Role role = Role.system(system, scope, organisationId, now);
            roles.put(role.id(), role);
        }
        return new RoleBook(scope, organisationId, roles, Map.of());
    }

    /** Rebuilds from storage. */
    public static RoleBook restore(
            final OrganisationScope scope,
            final UUID organisationId,
            final List<Role> roles,
            final Map<ResidentId, Set<RoleId>> assignments
    ) {
        Objects.requireNonNull(roles, "roles");
        final Map<RoleId, Role> byId = new LinkedHashMap<>();
        for (final Role role : sortedByRank(roles)) {
            byId.put(role.id(), role);
        }
        for (final SystemRole system : SystemRole.values()) {
            final boolean present = byId.values().stream()
                    .anyMatch(role -> role.systemRole().filter(system::equals).isPresent());
            if (!present) {
                // Loading an organisation with no leader role would leave it unable to grant itself
                // one. Failing here names the problem; failing later looks like a permission bug.
                throw new IllegalArgumentException(
                        "Organisation " + organisationId + " was restored without its " + system
                                + " role");
            }
        }
        return new RoleBook(
                Objects.requireNonNull(scope, "scope"),
                Objects.requireNonNull(organisationId, "organisationId"),
                byId,
                Objects.requireNonNullElse(assignments, Map.of()));
    }

    // --- editing roles ---------------------------------------------------------------------

    /**
     * Adds a configurable role.
     *
     * @param lockedByAdmin permissions the server administrator has forbidden to configurable roles
     */
    public Outcome<RoleBook> create(final Role role, final Set<Permission> lockedByAdmin) {
        Objects.requireNonNull(role, "role");
        if (findByName(role.name()).isPresent()) {
            return Outcome.denied(ChangeDenial.ROLE_NAME_TAKEN);
        }
        if (role.priority() >= SystemRole.LEADER.priority()) {
            return Outcome.denied(ChangeDenial.PRIORITY_RESERVED_FOR_LEADER);
        }
        if (priorityTaken(role.priority(), null)) {
            return Outcome.denied(ChangeDenial.PRIORITY_ALREADY_USED);
        }
        final Optional<Permission> locked = firstLocked(role.permissions(), lockedByAdmin);
        if (locked.isPresent()) {
            return Outcome.denied(ChangeDenial.PERMISSION_LOCKED_BY_ADMIN);
        }

        final Map<RoleId, Role> updated = new LinkedHashMap<>(roles);
        updated.put(role.id(), role);
        return Outcome.applied(
                new RoleBook(scope, organisationId, updated, assignments),
                changed(role, DomainEvent.RoleAction.CREATED, ""));
    }

    /** Removes a configurable role and revokes it from everyone holding it. */
    public Outcome<RoleBook> delete(final RoleId roleId) {
        final Optional<Role> found = find(roleId);
        if (found.isEmpty()) {
            return Outcome.denied(ChangeDenial.ROLE_NOT_FOUND);
        }
        final Role role = found.get();
        if (role.isSystem()) {
            return Outcome.denied(ChangeDenial.SYSTEM_ROLE_CANNOT_BE_DELETED);
        }

        final Map<RoleId, Role> updated = new LinkedHashMap<>(roles);
        updated.remove(roleId);

        // Assignments are cleaned up here rather than left as orphans. A dangling assignment would
        // silently grant nothing today and grant something unexpected the moment a new role reused
        // the id.
        final Map<ResidentId, Set<RoleId>> remaining = new LinkedHashMap<>();
        assignments.forEach((resident, held) -> {
            final Set<RoleId> without = new LinkedHashSet<>(held);
            without.remove(roleId);
            if (!without.isEmpty()) {
                remaining.put(resident, without);
            }
        });

        return Outcome.applied(
                new RoleBook(scope, organisationId, updated, remaining),
                changed(role, DomainEvent.RoleAction.DELETED, ""));
    }

    /** Renames a role. Permitted on system roles — a server may call its leader Jarl. */
    public Outcome<RoleBook> rename(final RoleId roleId, final String newName) {
        final Optional<Role> found = find(roleId);
        if (found.isEmpty()) {
            return Outcome.denied(ChangeDenial.ROLE_NOT_FOUND);
        }
        final Optional<Role> holder = findByName(newName);
        if (holder.isPresent() && !holder.get().id().equals(roleId)) {
            return Outcome.denied(ChangeDenial.ROLE_NAME_TAKEN);
        }

        final Role role = found.get();
        final Outcome<Role> renamed = role.renameTo(newName);
        return renamed.value()
                .map(updated -> replace(updated,
                        changed(updated, DomainEvent.RoleAction.RENAMED, role.name())))
                .orElseGet(() -> Outcome.denied(renamed.denial().orElseThrow()));
    }

    /** Moves a configurable role in the ranking. */
    public Outcome<RoleBook> reprioritise(final RoleId roleId, final int priority) {
        final Optional<Role> found = find(roleId);
        if (found.isEmpty()) {
            return Outcome.denied(ChangeDenial.ROLE_NOT_FOUND);
        }
        if (priorityTaken(priority, roleId)) {
            return Outcome.denied(ChangeDenial.PRIORITY_ALREADY_USED);
        }
        final Outcome<Role> moved = found.get().reprioritise(priority);
        return moved.value()
                .map(updated -> replace(updated, changed(
                        updated, DomainEvent.RoleAction.REPRIORITISED, String.valueOf(priority))))
                .orElseGet(() -> Outcome.denied(moved.denial().orElseThrow()));
    }

    /** Grants a permission to a role. */
    public Outcome<RoleBook> grant(
            final RoleId roleId, final Permission permission, final Set<Permission> lockedByAdmin) {
        if (lockedByAdmin != null && lockedByAdmin.contains(permission)) {
            return Outcome.denied(ChangeDenial.PERMISSION_LOCKED_BY_ADMIN);
        }
        return editPermission(roleId, role -> role.grant(permission),
                DomainEvent.RoleAction.PERMISSION_GRANTED, permission);
    }

    /** Revokes a permission from a role. */
    public Outcome<RoleBook> revoke(final RoleId roleId, final Permission permission) {
        return editPermission(roleId, role -> role.revoke(permission),
                DomainEvent.RoleAction.PERMISSION_REVOKED, permission);
    }

    // --- assigning roles -------------------------------------------------------------------

    /**
     * Grants a role to a resident.
     *
     * <p>System roles are refused. The leader role comes from a leadership transfer, which performs
     * the atomic bank and capital updates that assignment would skip; the member role comes from
     * being a resident at all. Two paths to either would let an organisation acquire a second
     * leader, or a member who is not a resident.</p>
     */
    public Outcome<RoleBook> assign(final ResidentId who, final RoleId roleId) {
        Objects.requireNonNull(who, "who");
        final Optional<Role> found = find(roleId);
        if (found.isEmpty()) {
            return Outcome.denied(ChangeDenial.ROLE_NOT_FOUND);
        }
        final Role role = found.get();
        final Optional<SystemRole> system = role.systemRole();
        if (system.filter(SystemRole.LEADER::equals).isPresent()) {
            return Outcome.denied(ChangeDenial.LEADER_ROLE_IS_NOT_ASSIGNABLE);
        }
        if (system.isPresent()) {
            return Outcome.denied(ChangeDenial.BASELINE_ROLE_IS_NOT_ASSIGNABLE);
        }
        if (rolesOf(who).contains(roleId)) {
            return Outcome.denied(ChangeDenial.ALREADY_HAS_ROLE);
        }

        final Map<ResidentId, Set<RoleId>> updated = new LinkedHashMap<>(assignments);
        final Set<RoleId> held = new LinkedHashSet<>(rolesOf(who));
        held.add(roleId);
        updated.put(who, held);
        return Outcome.applied(
                new RoleBook(scope, organisationId, roles, updated),
                changed(role, DomainEvent.RoleAction.ASSIGNED, who.value().toString()));
    }

    /** Revokes a role from a resident. */
    public Outcome<RoleBook> unassign(final ResidentId who, final RoleId roleId) {
        Objects.requireNonNull(who, "who");
        final Optional<Role> found = find(roleId);
        if (found.isEmpty()) {
            return Outcome.denied(ChangeDenial.ROLE_NOT_FOUND);
        }
        if (!rolesOf(who).contains(roleId)) {
            return Outcome.denied(ChangeDenial.DOES_NOT_HAVE_ROLE);
        }

        final Map<ResidentId, Set<RoleId>> updated = new LinkedHashMap<>(assignments);
        final Set<RoleId> held = new LinkedHashSet<>(rolesOf(who));
        held.remove(roleId);
        if (held.isEmpty()) {
            updated.remove(who);
        } else {
            updated.put(who, held);
        }
        return Outcome.applied(
                new RoleBook(scope, organisationId, roles, updated),
                changed(found.get(), DomainEvent.RoleAction.UNASSIGNED, who.value().toString()));
    }

    // --- resolution ------------------------------------------------------------------------

    /**
     * May this resident perform this action here?
     *
     * <p>Ordered, and short-circuiting at the first step:</p>
     * <ol>
     *   <li>The leader holds every permission. No further lookup.</li>
     *   <li>Otherwise the answer is the union of every assigned role's permissions and the
     *       baseline role for their standing — member for a resident, visitor for anyone else.</li>
     * </ol>
     *
     * <p>Union rather than highest-role-wins: roles are additive, so a town can hand out a narrow
     * "farmer" role without it silently removing what membership already granted.</p>
     *
     * @param baseline the resident's standing, which the organisation knows and the role book does
     *        not — passed in rather than looked up, so the dependency is visible
     */
    public boolean allows(
            final ResidentId who, final Permission permission, final SystemRole baseline) {
        Objects.requireNonNull(permission, "permission");
        Objects.requireNonNull(baseline, "baseline");
        if (baseline == SystemRole.LEADER) {
            return true;
        }
        return effectivePermissions(who, baseline).contains(permission);
    }

    /** Everything this resident may do, for a GUI that wants to show the whole set. */
    public Set<Permission> effectivePermissions(final ResidentId who, final SystemRole baseline) {
        Objects.requireNonNull(baseline, "baseline");
        if (baseline == SystemRole.LEADER) {
            return Collections.unmodifiableSet(EnumSet.allOf(Permission.class));
        }
        final EnumSet<Permission> effective = EnumSet.noneOf(Permission.class);
        systemRole(baseline).ifPresent(role -> effective.addAll(role.permissions()));
        for (final RoleId held : rolesOf(who)) {
            find(held).ifPresent(role -> effective.addAll(role.permissions()));
        }
        return Collections.unmodifiableSet(effective);
    }

    /**
     * The rank a resident carries: the highest of their assigned roles and their baseline.
     *
     * <p>Highest rather than union, unlike permissions. Rank is a position in an ordering, and
     * adding a low-ranked role to someone must not demote them.</p>
     */
    public int rankOf(final ResidentId who, final SystemRole baseline) {
        int rank = baseline.priority();
        for (final RoleId held : rolesOf(who)) {
            final Optional<Role> role = find(held);
            if (role.isPresent() && role.get().priority() > rank) {
                rank = role.get().priority();
            }
        }
        return rank;
    }

    /**
     * May this resident edit or hand out this role?
     *
     * <p>Strictly outrank, not merely match: two officers of equal rank able to manage each other's
     * role could demote one another in a loop, and the last one to click would win.</p>
     */
    public boolean mayManage(final ResidentId actor, final SystemRole baseline, final RoleId target) {
        if (baseline == SystemRole.LEADER) {
            return true;
        }
        return find(target)
                .map(role -> rankOf(actor, baseline) > role.priority())
                .orElse(false);
    }

    // --- reading -----------------------------------------------------------------------------

    public Optional<Role> find(final RoleId roleId) {
        return roleId == null ? Optional.empty() : Optional.ofNullable(roles.get(roleId));
    }

    public Optional<Role> findByName(final String name) {
        if (name == null) {
            return Optional.empty();
        }
        final String normalised = name.trim().toLowerCase(Locale.ROOT);
        return roles.values().stream()
                .filter(role -> role.nameNormalised().equals(normalised))
                .findFirst();
    }

    public Optional<Role> systemRole(final SystemRole system) {
        return roles.values().stream()
                .filter(role -> role.systemRole().filter(system::equals).isPresent())
                .findFirst();
    }

    /** Every role, highest rank first. */
    public List<Role> ordered() {
        return sortedByRank(new ArrayList<>(roles.values()));
    }

    public Set<RoleId> rolesOf(final ResidentId who) {
        return assignments.getOrDefault(who, Set.of());
    }

    public Set<ResidentId> holdersOf(final RoleId roleId) {
        final Set<ResidentId> holders = new LinkedHashSet<>();
        assignments.forEach((resident, held) -> {
            if (held.contains(roleId)) {
                holders.add(resident);
            }
        });
        return Collections.unmodifiableSet(holders);
    }

    public OrganisationScope scope() {
        return scope;
    }

    public UUID organisationId() {
        return organisationId;
    }

    public int size() {
        return roles.size();
    }

    // --- internals ---------------------------------------------------------------------------

    private Outcome<RoleBook> editPermission(
            final RoleId roleId,
            final java.util.function.Function<Role, Outcome<Role>> edit,
            final DomainEvent.RoleAction action,
            final Permission permission
    ) {
        final Optional<Role> found = find(roleId);
        if (found.isEmpty()) {
            return Outcome.denied(ChangeDenial.ROLE_NOT_FOUND);
        }
        final Outcome<Role> edited = edit.apply(found.get());
        return edited.value()
                .map(updated -> replace(updated, changed(updated, action, permission.name())))
                .orElseGet(() -> Outcome.denied(edited.denial().orElseThrow()));
    }

    private Outcome<RoleBook> replace(final Role role, final DomainEvent event) {
        final Map<RoleId, Role> updated = new LinkedHashMap<>(roles);
        updated.put(role.id(), role);
        return Outcome.applied(new RoleBook(scope, organisationId, updated, assignments), event);
    }

    private DomainEvent changed(
            final Role role, final DomainEvent.RoleAction action, final String detail) {
        return new DomainEvent.RoleChanged(
                scope, organisationId, role.id().value(), role.name(), action, detail);
    }

    private boolean priorityTaken(final int priority, final RoleId ignoring) {
        return roles.values().stream()
                .filter(role -> ignoring == null || !role.id().equals(ignoring))
                .anyMatch(role -> role.priority() == priority);
    }

    private static Optional<Permission> firstLocked(
            final Set<Permission> wanted, final Set<Permission> lockedByAdmin) {
        if (lockedByAdmin == null || lockedByAdmin.isEmpty()) {
            return Optional.empty();
        }
        return wanted.stream().filter(lockedByAdmin::contains).findFirst();
    }

    private static List<Role> sortedByRank(final List<Role> source) {
        final List<Role> sorted = new ArrayList<>(source);
        // Name is the tiebreak so the order is total and stable; without it two roles at the same
        // priority would swap places between reloads and a GUI list would appear to shuffle.
        sorted.sort(Comparator.comparingInt(Role::priority).reversed()
                .thenComparing(Role::nameNormalised));
        return List.copyOf(sorted);
    }
}
