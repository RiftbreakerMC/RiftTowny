package net.riftbreaker.rifttowny.domain.service;

import net.riftbreaker.rifttowny.domain.org.NationId;
import net.riftbreaker.rifttowny.domain.org.ResidentId;
import net.riftbreaker.rifttowny.domain.role.Permission;
import net.riftbreaker.rifttowny.domain.role.Role;
import net.riftbreaker.rifttowny.domain.role.RoleId;
import net.riftbreaker.rifttowny.domain.store.CivicStore;

import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Editing and handing out a nation's roles.
 *
 * <p>Same rules as a town's, from the same {@link RoleEditor}: privilege escalation does not change
 * shape between the two, and the three guards that stop an officer promoting themselves are the
 * ones that would drift if they were copied.</p>
 *
 * <p>One thing genuinely differs, and it is why this could not simply reuse the town service.
 * <strong>A nation has no residents.</strong> Citizenship is residency in one of its member towns,
 * so both "what is this person's standing" and "may this role be handed to them" need a second
 * lookup — the resident's town, and whether that town is a member. {@code RoleEditor} asks the
 * organisation those two questions rather than assuming a town's answer.</p>
 *
 * <p>A consequence worth stating: a citizen who leaves their town, or whose town leaves the nation,
 * keeps any nation role they hold. Nothing ties {@code rt_role_member} to citizenship, and unlike a
 * town's departure there is no single event that ends it. Until that is closed, a nation should
 * treat its roles as needing manual revocation.</p>
 */
public final class NationRoleService {

    private final RoleEditor editor;

    /**
     * @param lockedByAdmin permissions the server administrator has forbidden to configurable roles.
     *        The same set as a town's: a permission dangerous enough to lock away from a town's
     *        officers is not less dangerous in a nation's
     * @param civic told after every accepted edit. This used to be hard-coded to
     *        {@link CivicCacheRefresher#none()}, on the grounds that only a town's book was cached
     *        because protection reads a town's roles on every block and never a nation's. That
     *        stopped being true when the nation cache began holding role books for chat, and the
     *        refresher being absent made the refresh in {@code RoleEditor} unreachable for nations -
     *        so a revoked nation permission was answered from memory until the next restart
     */
    public NationRoleService(
            final CivicStore store,
            final Clock clock,
            final Set<Permission> lockedByAdmin,
            final CivicCacheRefresher civic) {
        this.editor = new RoleEditor(store, clock, lockedByAdmin, civic);
    }

    /** Creates a configurable role. Requires {@link Permission#MANAGE_ROLES} in the nation. */
    public CompletableFuture<ServiceResult<Role>> create(
            final ResidentId actor,
            final NationId nationId,
            final String name,
            final int priority,
            final Set<Permission> permissions
    ) {
        return editor.create(actor, nationId, name, priority, permissions);
    }

    /** Copies an existing role under a new name and rank. */
    public CompletableFuture<ServiceResult<Role>> clone(
            final ResidentId actor,
            final NationId nationId,
            final RoleId source,
            final String newName,
            final int priority
    ) {
        return editor.clone(actor, nationId, source, newName, priority);
    }

    /** Deletes a configurable role. */
    public CompletableFuture<ServiceResult<RoleId>> delete(
            final ResidentId actor, final NationId nationId, final RoleId roleId) {
        return editor.delete(actor, nationId, roleId);
    }

    /** Renames a role. Permitted on system roles — a nation may call its leader Emperor. */
    public CompletableFuture<ServiceResult<RoleId>> rename(
            final ResidentId actor,
            final NationId nationId,
            final RoleId roleId,
            final String newName
    ) {
        return editor.rename(actor, nationId, roleId, newName);
    }


    /** Sets a role's display name, icon and chat prefix. Requires {@link Permission#MANAGE_ROLES}. */
    public CompletableFuture<ServiceResult<RoleId>> decorate(
            final ResidentId actor,
            final NationId nationId,
            final RoleId roleId,
            final String displayName,
            final String icon,
            final String chatPrefix
    ) {
        return editor.decorate(actor, nationId, roleId, displayName, icon, chatPrefix);
    }
    /** Moves a role in the ranking. */
    public CompletableFuture<ServiceResult<RoleId>> reprioritise(
            final ResidentId actor, final NationId nationId, final RoleId roleId, final int priority) {
        return editor.reprioritise(actor, nationId, roleId, priority);
    }

    /** Adds a permission to a role. Requires holding it yourself. */
    public CompletableFuture<ServiceResult<RoleId>> grant(
            final ResidentId actor,
            final NationId nationId,
            final RoleId roleId,
            final Permission permission
    ) {
        return editor.grant(actor, nationId, roleId, permission);
    }

    /** Removes a permission from a role. */
    public CompletableFuture<ServiceResult<RoleId>> revoke(
            final ResidentId actor,
            final NationId nationId,
            final RoleId roleId,
            final Permission permission
    ) {
        return editor.revoke(actor, nationId, roleId, permission);
    }

    /**
     * Grants a role to a citizen.
     *
     * <p>Requires {@link Permission#ASSIGN_ROLES}, and the target must be a resident of one of the
     * nation's member towns. Handing a nation role to somebody outside it would give a stranger
     * authority over towns they do not belong to.</p>
     */
    public CompletableFuture<ServiceResult<RoleId>> assign(
            final ResidentId actor,
            final NationId nationId,
            final ResidentId target,
            final RoleId roleId
    ) {
        return editor.assign(actor, nationId, target, roleId);
    }

    /** Revokes a role from a citizen. */
    public CompletableFuture<ServiceResult<RoleId>> unassign(
            final ResidentId actor,
            final NationId nationId,
            final ResidentId target,
            final RoleId roleId
    ) {
        return editor.unassign(actor, nationId, target, roleId);
    }

    /** The nation's roles, highest rank first. */
    public CompletableFuture<List<Role>> list(final NationId nationId) {
        return editor.list(nationId);
    }

    /** What one citizen may do in the nation. */
    public CompletableFuture<Set<Permission>> permissionsOf(
            final NationId nationId, final ResidentId who) {
        return editor.permissionsOf(nationId, who);
    }
}
