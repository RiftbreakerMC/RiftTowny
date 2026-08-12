package net.riftbreaker.rifttowny.domain.service;

import net.riftbreaker.rifttowny.domain.org.ChangeDenial;
import net.riftbreaker.rifttowny.domain.org.OrganisationScope;
import net.riftbreaker.rifttowny.domain.org.Outcome;
import net.riftbreaker.rifttowny.domain.org.ResidentId;
import net.riftbreaker.rifttowny.domain.org.Town;
import net.riftbreaker.rifttowny.domain.org.TownId;
import net.riftbreaker.rifttowny.domain.role.Permission;
import net.riftbreaker.rifttowny.domain.role.Role;
import net.riftbreaker.rifttowny.domain.role.RoleBook;
import net.riftbreaker.rifttowny.domain.role.RoleId;
import net.riftbreaker.rifttowny.domain.role.SystemRole;
import net.riftbreaker.rifttowny.domain.store.ChangeRefusedException;
import net.riftbreaker.rifttowny.domain.store.CivicStore;
import net.riftbreaker.rifttowny.domain.store.CivicTransaction;

import java.time.Clock;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Editing and handing out a town's roles.
 *
 * <p>Separate from {@link TownService} because the interesting rules are different. Town lifecycle
 * is about membership invariants; this is about privilege escalation, and it has three guards that
 * matter more than the permission check itself:</p>
 *
 * <ol>
 *   <li><strong>You may only touch a role you outrank.</strong> Otherwise an officer edits the role
 *       above them and promotes themselves.</li>
 *   <li><strong>You may only put permissions into a role that you already hold.</strong> Otherwise
 *       an officer with nothing but {@code MANAGE_ROLES} writes {@code DISBAND} into a role and
 *       assigns it to themselves.</li>
 *   <li><strong>You may not create a role at or above your own rank.</strong> Same escalation
 *       arriving by a different door.</li>
 * </ol>
 *
 * <p>Nations are not handled here. A nation's standing depends on residency in one of its member
 * towns, which is a different lookup, and guessing at it would be worse than leaving it to
 * {@code NationService}.</p>
 */
public final class TownRoleService {

    private final CivicStore store;
    private final Clock clock;
    private final Set<Permission> lockedByAdmin;
    private final CivicCacheRefresher civic;

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
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.lockedByAdmin = lockedByAdmin == null || lockedByAdmin.isEmpty()
                ? Set.of()
                : Set.copyOf(lockedByAdmin);
        this.civic = Objects.requireNonNull(civic, "civic");
    }

    /** Creates a configurable role. Requires {@link Permission#MANAGE_ROLES}. */
    public CompletableFuture<ServiceResult<Role>> create(
            final ResidentId actor,
            final TownId townId,
            final String name,
            final int priority,
            final Set<Permission> permissions
    ) {
        Objects.requireNonNull(name, "name");
        final Set<Permission> wanted = permissions == null ? Set.of() : Set.copyOf(permissions);

        return transaction(townId, actor, Permission.MANAGE_ROLES, (transaction, context) -> {
            requireRankAbove(context, priority);
            requireHolds(context, wanted);

            final Role role = Role.custom(
                    RoleId.random(), OrganisationScope.TOWN, townId.value(), name, priority,
                    wanted, clock.instant());
            final Outcome<RoleBook> created = context.book().create(role, lockedByAdmin);
            save(transaction, townId, created);
            return role;
        });
    }

    /** Copies an existing role under a new name and rank. Requires {@link Permission#MANAGE_ROLES}. */
    public CompletableFuture<ServiceResult<Role>> clone(
            final ResidentId actor,
            final TownId townId,
            final RoleId source,
            final String newName,
            final int priority
    ) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(newName, "newName");

        return transaction(townId, actor, Permission.MANAGE_ROLES, (transaction, context) -> {
            final Role original = role(context.book(), source);
            requireManages(context, source);
            requireRankAbove(context, priority);
            // Cloning copies permissions, so it is a grant and carries the same bound as one.
            requireHolds(context, original.permissions());

            final Role copy = original.copyAs(RoleId.random(), newName, priority);
            save(transaction, townId, context.book().create(copy, lockedByAdmin));
            return copy;
        });
    }

    /** Deletes a configurable role. Requires {@link Permission#MANAGE_ROLES}. */
    public CompletableFuture<ServiceResult<RoleId>> delete(
            final ResidentId actor, final TownId townId, final RoleId roleId) {
        return transaction(townId, actor, Permission.MANAGE_ROLES, (transaction, context) -> {
            requireManages(context, roleId);
            save(transaction, townId, context.book().delete(roleId));
            return roleId;
        });
    }

    /** Renames a role. Requires {@link Permission#MANAGE_ROLES}. */
    public CompletableFuture<ServiceResult<RoleId>> rename(
            final ResidentId actor, final TownId townId, final RoleId roleId, final String newName) {
        return transaction(townId, actor, Permission.MANAGE_ROLES, (transaction, context) -> {
            requireManages(context, roleId);
            save(transaction, townId, context.book().rename(roleId, newName));
            return roleId;
        });
    }

    /** Moves a role in the ranking. Requires {@link Permission#MANAGE_ROLES}. */
    public CompletableFuture<ServiceResult<RoleId>> reprioritise(
            final ResidentId actor, final TownId townId, final RoleId roleId, final int priority) {
        return transaction(townId, actor, Permission.MANAGE_ROLES, (transaction, context) -> {
            requireManages(context, roleId);
            // Checked on the destination too: moving a role you outrank to a rank above yourself is
            // the same escalation as creating one there.
            requireRankAbove(context, priority);
            save(transaction, townId, context.book().reprioritise(roleId, priority));
            return roleId;
        });
    }

    /** Adds a permission to a role. Requires {@link Permission#MANAGE_ROLES} and holding it yourself. */
    public CompletableFuture<ServiceResult<RoleId>> grant(
            final ResidentId actor,
            final TownId townId,
            final RoleId roleId,
            final Permission permission
    ) {
        Objects.requireNonNull(permission, "permission");
        return transaction(townId, actor, Permission.MANAGE_ROLES, (transaction, context) -> {
            requireManages(context, roleId);
            requireHolds(context, Set.of(permission));
            save(transaction, townId, context.book().grant(roleId, permission, lockedByAdmin));
            return roleId;
        });
    }

    /**
     * Removes a permission from a role. Requires {@link Permission#MANAGE_ROLES}.
     *
     * <p>Not bounded by what the actor holds. Taking a permission away cannot escalate anybody, and
     * requiring the actor to hold it first would stop them cleaning up a role granted by a
     * predecessor with more authority than they have.</p>
     */
    public CompletableFuture<ServiceResult<RoleId>> revoke(
            final ResidentId actor,
            final TownId townId,
            final RoleId roleId,
            final Permission permission
    ) {
        Objects.requireNonNull(permission, "permission");
        return transaction(townId, actor, Permission.MANAGE_ROLES, (transaction, context) -> {
            requireManages(context, roleId);
            save(transaction, townId, context.book().revoke(roleId, permission));
            return roleId;
        });
    }

    /** Grants a role to a resident. Requires {@link Permission#ASSIGN_ROLES}. */
    public CompletableFuture<ServiceResult<RoleId>> assign(
            final ResidentId actor,
            final TownId townId,
            final ResidentId target,
            final RoleId roleId
    ) {
        Objects.requireNonNull(target, "target");
        return transaction(townId, actor, Permission.ASSIGN_ROLES, (transaction, context) -> {
            requireManages(context, roleId);
            requireMember(context, target);
            // Bounded by what the actor holds, exactly as writing a permission into a role is.
            // Handing out authority is the same escalation as authoring it: without this an officer
            // with ASSIGN_ROLES could assign themselves a lower-ranked role that a previous mayor
            // had loaded with DISBAND, and reach in one legal call the permission set that
            // requireHolds exists to keep out of their hands. Unconditional rather than
            // self-assignment only, since handing it to an accomplice is the same outcome.
            requireHolds(context, role(context.book(), roleId).permissions());
            save(transaction, townId, context.book().assign(target, roleId));
            return roleId;
        });
    }

    /** Revokes a role from a resident. Requires {@link Permission#ASSIGN_ROLES}. */
    public CompletableFuture<ServiceResult<RoleId>> unassign(
            final ResidentId actor,
            final TownId townId,
            final ResidentId target,
            final RoleId roleId
    ) {
        Objects.requireNonNull(target, "target");
        return transaction(townId, actor, Permission.ASSIGN_ROLES, (transaction, context) -> {
            requireManages(context, roleId);
            save(transaction, townId, context.book().unassign(target, roleId));
            return roleId;
        });
    }

    /** The town's roles, highest rank first. */
    public CompletableFuture<java.util.List<Role>> list(final TownId townId) {
        Objects.requireNonNull(townId, "townId");
        return store.inTransaction(transaction -> book(transaction, townId).ordered());
    }

    // --- guards ------------------------------------------------------------------------------

    private static void requireManages(final Context context, final RoleId target) {
        role(context.book(), target);
        if (!context.book().mayManage(context.actor(), context.standing(), target)) {
            throw new ChangeRefusedException(ChangeDenial.INSUFFICIENT_ROLE_PRIORITY);
        }
    }

    private static void requireRankAbove(final Context context, final int priority) {
        if (context.standing() == SystemRole.LEADER) {
            return;
        }
        if (context.rank() <= priority) {
            throw new ChangeRefusedException(ChangeDenial.CANNOT_CREATE_ROLE_ABOVE_SELF);
        }
    }

    private static void requireHolds(final Context context, final Set<Permission> wanted) {
        if (context.standing() == SystemRole.LEADER || wanted.isEmpty()) {
            return;
        }
        final Set<Permission> held = context.book()
                .effectivePermissions(context.actor(), context.standing());
        final EnumSet<Permission> missing = EnumSet.noneOf(Permission.class);
        for (final Permission permission : wanted) {
            if (!held.contains(permission)) {
                missing.add(permission);
            }
        }
        if (!missing.isEmpty()) {
            throw new ChangeRefusedException(ChangeDenial.CANNOT_GRANT_UNHELD_PERMISSION);
        }
    }

    private static void requireMember(final Context context, final ResidentId target) {
        if (!context.town().hasResident(target)) {
            throw new ChangeRefusedException(ChangeDenial.NOT_A_RESIDENT_OF_THIS_TOWN);
        }
    }

    private static Role role(final RoleBook book, final RoleId roleId) {
        return book.find(roleId)
                .orElseThrow(() -> new ChangeRefusedException(ChangeDenial.ROLE_NOT_FOUND));
    }

    // --- plumbing ----------------------------------------------------------------------------

    private static void save(
            final CivicTransaction transaction, final TownId townId, final Outcome<RoleBook> outcome) {
        final RoleBook updated = outcome.value().orElseThrow(() ->
                new ChangeRefusedException(outcome.denial().orElseThrow()));
        transaction.roles().save(updated);
        transaction.publishAll(outcome.events(), "roles:" + townId.value());
    }

    private static RoleBook book(final CivicTransaction transaction, final TownId townId) {
        return transaction.roles().find(OrganisationScope.TOWN, townId.value())
                .orElseThrow(() -> new ChangeRefusedException(ChangeDenial.ROLE_NOT_FOUND));
    }

    private <T> CompletableFuture<ServiceResult<T>> transaction(
            final TownId townId,
            final ResidentId actor,
            final Permission required,
            final Work<T> work
    ) {
        Objects.requireNonNull(townId, "townId");
        Objects.requireNonNull(actor, "actor");

        return store.<ServiceResult<T>>inTransaction(transaction -> {
            final Town town = transaction.towns().find(townId)
                    .orElseThrow(() -> new ChangeRefusedException(ChangeDenial.TOWN_NOT_FOUND));
            final RoleBook roleBook = book(transaction, townId);
            final SystemRole standing = town.standingOf(actor);
            if (!roleBook.allows(actor, required, standing)) {
                throw new ChangeRefusedException(ChangeDenial.MISSING_PERMISSION);
            }
            final Context context = new Context(
                    town, roleBook, actor, standing, roleBook.rankOf(actor, standing));
            return ServiceResult.success(work.perform(transaction, context));
        }).exceptionally(failure -> {
            final Throwable cause =
                    failure instanceof CompletionException ? failure.getCause() : failure;
            if (cause instanceof ChangeRefusedException refused) {
                return ServiceResult.refused(refused.denial());
            }
            throw failure instanceof CompletionException completion
                    ? completion
                    : new CompletionException(failure);
        }).thenCompose(result -> {
            // Every mutating method funnels through here, so the cache refresh does too. Putting it
            // on each method instead would leave the next one added to be the one that forgets, and
            // a forgotten refresh is a permission check answering from a role that no longer exists.
            if (!result.succeeded()) {
                return CompletableFuture.completedFuture(result);
            }
            return civic.refresh(townId).thenApply(ignored -> result);
        });
    }

    /** Everything the guards need, resolved once per operation rather than re-read per check. */
    private record Context(
            Town town, RoleBook book, ResidentId actor, SystemRole standing, int rank) {
    }

    @FunctionalInterface
    private interface Work<T> {
        T perform(CivicTransaction transaction, Context context);
    }
}
