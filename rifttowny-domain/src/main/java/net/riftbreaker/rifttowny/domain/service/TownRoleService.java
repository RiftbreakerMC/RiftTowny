package net.riftbreaker.rifttowny.domain.service;

import net.riftbreaker.rifttowny.domain.org.ResidentId;
import net.riftbreaker.rifttowny.domain.org.TownId;
import net.riftbreaker.rifttowny.domain.role.Permission;
import net.riftbreaker.rifttowny.domain.role.Role;
import net.riftbreaker.rifttowny.domain.role.RoleId;
import net.riftbreaker.rifttowny.domain.store.CivicStore;

import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Editing and handing out a town's roles.
 *
 * <p>The rules live in {@link RoleEditor}, which serves nations too: privilege escalation does not
 * change shape between a town and a nation, and a second copy of the three guards is how one of them
 * quietly stops matching the other.</p>
 *
 * <p>What this class adds is the type. Taking a {@link TownId} rather than an
 * {@code OrganisationId} means a nation's id cannot reach a method meaning a town's — both are
 * thirty-six characters of hex, and the mistake would edit the wrong organisation's roles.</p>
 */
public final class TownRoleService {

    private final RoleEditor editor;

    /**
     * @param lockedByAdmin permissions the server administrator has forbidden to configurable roles,
     *        from configuration. Empty means nothing is locked
     */
    public TownRoleService(
            final CivicStore store, final Clock clock, final Set<Permission> lockedByAdmin) {
        this(store, clock, lockedByAdmin, CivicCacheRefresher.none());
    }

    /**
     * @param civic told after every successful change. A role edit that did not reach the cache
     *        would leave protection answering from the permissions the role used to have
     */
    public TownRoleService(
            final CivicStore store,
            final Clock clock,
            final Set<Permission> lockedByAdmin,
            final CivicCacheRefresher civic
    ) {
        this.editor = new RoleEditor(store, clock, lockedByAdmin, civic);
    }

    /** Creates a configurable role. Requires {@link Permission#MANAGE_ROLES}. */
    public CompletableFuture<ServiceResult<Role>> create(
            final ResidentId actor,
            final TownId townId,
            final String name,
            final int priority,
            final Set<Permission> permissions
    ) {
        return editor.create(actor, townId, name, priority, permissions);
    }

    /** Copies an existing role under a new name and rank. Requires {@link Permission#MANAGE_ROLES}. */
    public CompletableFuture<ServiceResult<Role>> clone(
            final ResidentId actor,
            final TownId townId,
            final RoleId source,
            final String newName,
            final int priority
    ) {
        return editor.clone(actor, townId, source, newName, priority);
    }

    /** Deletes a configurable role. Requires {@link Permission#MANAGE_ROLES}. */
    public CompletableFuture<ServiceResult<RoleId>> delete(
            final ResidentId actor, final TownId townId, final RoleId roleId) {
        return editor.delete(actor, townId, roleId);
    }

    /** Renames a role. Requires {@link Permission#MANAGE_ROLES}. */
    public CompletableFuture<ServiceResult<RoleId>> rename(
            final ResidentId actor, final TownId townId, final RoleId roleId, final String newName) {
        return editor.rename(actor, townId, roleId, newName);
    }

    /** Moves a role in the ranking. Requires {@link Permission#MANAGE_ROLES}. */
    public CompletableFuture<ServiceResult<RoleId>> reprioritise(
            final ResidentId actor, final TownId townId, final RoleId roleId, final int priority) {
        return editor.reprioritise(actor, townId, roleId, priority);
    }

    /** Adds a permission to a role. Requires {@link Permission#MANAGE_ROLES} and holding it yourself. */
    public CompletableFuture<ServiceResult<RoleId>> grant(
            final ResidentId actor,
            final TownId townId,
            final RoleId roleId,
            final Permission permission
    ) {
        return editor.grant(actor, townId, roleId, permission);
    }

    /** Removes a permission from a role. Requires {@link Permission#MANAGE_ROLES}. */
    public CompletableFuture<ServiceResult<RoleId>> revoke(
            final ResidentId actor,
            final TownId townId,
            final RoleId roleId,
            final Permission permission
    ) {
        return editor.revoke(actor, townId, roleId, permission);
    }

    /** Grants a role to a resident. Requires {@link Permission#ASSIGN_ROLES}. */
    public CompletableFuture<ServiceResult<RoleId>> assign(
            final ResidentId actor,
            final TownId townId,
            final ResidentId target,
            final RoleId roleId
    ) {
        return editor.assign(actor, townId, target, roleId);
    }

    /** Revokes a role from a resident. Requires {@link Permission#ASSIGN_ROLES}. */
    public CompletableFuture<ServiceResult<RoleId>> unassign(
            final ResidentId actor,
            final TownId townId,
            final ResidentId target,
            final RoleId roleId
    ) {
        return editor.unassign(actor, townId, target, roleId);
    }

    /** The town's roles, highest rank first. */
    public CompletableFuture<List<Role>> list(final TownId townId) {
        return editor.list(townId);
    }
}
