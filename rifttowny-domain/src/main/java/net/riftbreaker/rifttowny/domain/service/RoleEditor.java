package net.riftbreaker.rifttowny.domain.service;

import net.riftbreaker.rifttowny.domain.org.ChangeDenial;
import net.riftbreaker.rifttowny.domain.org.Nation;
import net.riftbreaker.rifttowny.domain.org.NationId;
import net.riftbreaker.rifttowny.domain.org.OrganisationId;
import net.riftbreaker.rifttowny.domain.org.OrganisationScope;
import net.riftbreaker.rifttowny.domain.org.Outcome;
import net.riftbreaker.rifttowny.domain.org.Resident;
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
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Role editing, for any organisation.
 *
 * <p>The rules here are about privilege escalation and they do not vary by scope. Three guards
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
 * <p>Shared rather than copied per scope, because a copy is how one of the three quietly stops
 * matching the other and only the weaker one is ever tested. What genuinely differs between a town
 * and a nation is only {@link Authority}: how standing is worked out, and what counts as
 * membership.</p>
 *
 * <p>Package-private. {@link TownRoleService} and {@link NationRoleService} are the typed faces of
 * this, so a caller cannot hand a nation id to a method meaning a town's.</p>
 */
final class RoleEditor {

    private final CivicStore store;
    private final Clock clock;
    private final Set<Permission> lockedByAdmin;
    private final CivicCacheRefresher civic;

    RoleEditor(
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

    // --- editing roles -------------------------------------------------------------------------

    CompletableFuture<ServiceResult<Role>> create(
            final ResidentId actor,
            final OrganisationId organisation,
            final String name,
            final int priority,
            final Set<Permission> permissions
    ) {
        Objects.requireNonNull(name, "name");
        final Set<Permission> wanted = permissions == null ? Set.of() : Set.copyOf(permissions);

        return transaction(organisation, actor, Permission.MANAGE_ROLES, (transaction, context) -> {
            requireRankAbove(context, priority);
            requireHolds(context, wanted);

            final Role role = Role.custom(
                    RoleId.random(), organisation.scope(), organisation.value(), name, priority,
                    wanted, clock.instant());
            save(transaction, organisation, context.book().create(role, lockedByAdmin));
            return role;
        });
    }

    CompletableFuture<ServiceResult<Role>> clone(
            final ResidentId actor,
            final OrganisationId organisation,
            final RoleId source,
            final String newName,
            final int priority
    ) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(newName, "newName");

        return transaction(organisation, actor, Permission.MANAGE_ROLES, (transaction, context) -> {
            final Role original = role(context.book(), source);
            requireManages(context, source);
            requireRankAbove(context, priority);
            // Cloning copies permissions, so it is a grant and carries the same bound as one.
            requireHolds(context, original.permissions());

            final Role copy = original.copyAs(RoleId.random(), newName, priority);
            save(transaction, organisation, context.book().create(copy, lockedByAdmin));
            return copy;
        });
    }

    CompletableFuture<ServiceResult<RoleId>> delete(
            final ResidentId actor, final OrganisationId organisation, final RoleId roleId) {
        return transaction(organisation, actor, Permission.MANAGE_ROLES, (transaction, context) -> {
            requireManages(context, roleId);
            save(transaction, organisation, context.book().delete(roleId));
            return roleId;
        });
    }

    CompletableFuture<ServiceResult<RoleId>> rename(
            final ResidentId actor,
            final OrganisationId organisation,
            final RoleId roleId,
            final String newName
    ) {
        return transaction(organisation, actor, Permission.MANAGE_ROLES, (transaction, context) -> {
            requireManages(context, roleId);
            save(transaction, organisation, context.book().rename(roleId, newName));
            return roleId;
        });
    }


    /**
     * Sets a role's display name, icon and chat prefix.
     *
     * <p>Guarded by {@code requireManages} like every other edit, so a role you do not outrank
     * cannot be relabelled — a leader's rank being renamed by somebody below them would be a way to
     * confuse a town about who is in charge without touching a single permission.</p>
     */
    CompletableFuture<ServiceResult<RoleId>> decorate(
            final ResidentId actor,
            final OrganisationId organisation,
            final RoleId roleId,
            final String displayName,
            final String icon,
            final String chatPrefix
    ) {
        return transaction(organisation, actor, Permission.MANAGE_ROLES, (transaction, context) -> {
            requireManages(context, roleId);
            save(transaction, organisation,
                    context.book().decorate(roleId, displayName, icon, chatPrefix));
            return roleId;
        });
    }
    CompletableFuture<ServiceResult<RoleId>> reprioritise(
            final ResidentId actor,
            final OrganisationId organisation,
            final RoleId roleId,
            final int priority
    ) {
        return transaction(organisation, actor, Permission.MANAGE_ROLES, (transaction, context) -> {
            requireManages(context, roleId);
            // Checked on the destination too: moving a role you outrank to a rank above yourself is
            // the same escalation as creating one there.
            requireRankAbove(context, priority);
            save(transaction, organisation, context.book().reprioritise(roleId, priority));
            return roleId;
        });
    }

    CompletableFuture<ServiceResult<RoleId>> grant(
            final ResidentId actor,
            final OrganisationId organisation,
            final RoleId roleId,
            final Permission permission
    ) {
        Objects.requireNonNull(permission, "permission");
        return transaction(organisation, actor, Permission.MANAGE_ROLES, (transaction, context) -> {
            requireManages(context, roleId);
            requireHolds(context, Set.of(permission));
            save(transaction, organisation,
                    context.book().grant(roleId, permission, lockedByAdmin));
            return roleId;
        });
    }

    /**
     * Removes a permission from a role.
     *
     * <p>Not bounded by what the actor holds. Taking a permission away cannot escalate anybody, and
     * requiring the actor to hold it first would stop them cleaning up a role granted by a
     * predecessor with more authority than they have.</p>
     */
    CompletableFuture<ServiceResult<RoleId>> revoke(
            final ResidentId actor,
            final OrganisationId organisation,
            final RoleId roleId,
            final Permission permission
    ) {
        Objects.requireNonNull(permission, "permission");
        return transaction(organisation, actor, Permission.MANAGE_ROLES, (transaction, context) -> {
            requireManages(context, roleId);
            save(transaction, organisation, context.book().revoke(roleId, permission));
            return roleId;
        });
    }

    CompletableFuture<ServiceResult<RoleId>> assign(
            final ResidentId actor,
            final OrganisationId organisation,
            final ResidentId target,
            final RoleId roleId
    ) {
        Objects.requireNonNull(target, "target");
        return transaction(organisation, actor, Permission.ASSIGN_ROLES, (transaction, context) -> {
            requireManages(context, roleId);
            if (!context.authority().isMember(transaction, target)) {
                throw new ChangeRefusedException(context.authority().notAMemberDenial());
            }
            // Bounded by what the actor holds, exactly as writing a permission into a role is.
            // Handing out authority is the same escalation as authoring it: without this an officer
            // with ASSIGN_ROLES could assign themselves a lower-ranked role that a previous leader
            // had loaded with DISBAND, and reach in one legal call the permission set that
            // requireHolds exists to keep out of their hands. Unconditional rather than
            // self-assignment only, since handing it to an accomplice is the same outcome.
            requireHolds(context, role(context.book(), roleId).permissions());
            save(transaction, organisation, context.book().assign(target, roleId));
            return roleId;
        });
    }

    CompletableFuture<ServiceResult<RoleId>> unassign(
            final ResidentId actor,
            final OrganisationId organisation,
            final ResidentId target,
            final RoleId roleId
    ) {
        Objects.requireNonNull(target, "target");
        return transaction(organisation, actor, Permission.ASSIGN_ROLES, (transaction, context) -> {
            requireManages(context, roleId);
            save(transaction, organisation, context.book().unassign(target, roleId));
            return roleId;
        });
    }

    /** The organisation's roles, highest rank first. */
    CompletableFuture<List<Role>> list(final OrganisationId organisation) {
        Objects.requireNonNull(organisation, "organisation");
        return store.inTransaction(transaction -> book(transaction, organisation).ordered());
    }

    /** What one person may do here, for a GUI or a listing. */
    CompletableFuture<Set<Permission>> permissionsOf(
            final OrganisationId organisation, final ResidentId who) {
        Objects.requireNonNull(organisation, "organisation");
        Objects.requireNonNull(who, "who");
        return store.inTransaction(transaction -> book(transaction, organisation)
                .effectivePermissions(who, authorityFor(transaction, organisation).standingOf(who)));
    }

    // --- guards --------------------------------------------------------------------------------

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

    private static Role role(final RoleBook book, final RoleId roleId) {
        return book.find(roleId)
                .orElseThrow(() -> new ChangeRefusedException(ChangeDenial.ROLE_NOT_FOUND));
    }

    // --- authority -----------------------------------------------------------------------------

    /**
     * The two questions an organisation has to answer for role editing.
     *
     * <p>All that genuinely differs between a town and a nation. A town knows its residents
     * directly; a nation's citizens are residents of its member towns, so both answers there need a
     * second lookup.</p>
     */
    private interface Authority {

        /** Leader, member or outsider. */
        SystemRole standingOf(ResidentId who);

        /** Whether a role may be handed to this person at all. */
        boolean isMember(CivicTransaction transaction, ResidentId who);

        /** How to say no when they are not one. */
        ChangeDenial notAMemberDenial();
    }

    private static Authority authorityFor(
            final CivicTransaction transaction, final OrganisationId organisation) {
        return switch (organisation) {
            case TownId townId -> {
                final Town town = transaction.towns().find(townId)
                        .orElseThrow(() -> new ChangeRefusedException(ChangeDenial.TOWN_NOT_FOUND));
                yield new Authority() {
                    @Override
                    public SystemRole standingOf(final ResidentId who) {
                        return town.standingOf(who);
                    }

                    @Override
                    public boolean isMember(final CivicTransaction ignored, final ResidentId who) {
                        return town.hasResident(who);
                    }

                    @Override
                    public ChangeDenial notAMemberDenial() {
                        return ChangeDenial.NOT_A_RESIDENT_OF_THIS_TOWN;
                    }
                };
            }
            case NationId nationId -> {
                final Nation nation = transaction.nations().find(nationId)
                        .orElseThrow(() ->
                                new ChangeRefusedException(ChangeDenial.NATION_NOT_FOUND));
                yield new Authority() {
                    @Override
                    public SystemRole standingOf(final ResidentId who) {
                        return nation.standingOf(who, townOf(transaction, who).orElse(null));
                    }

                    @Override
                    public boolean isMember(
                            final CivicTransaction inside, final ResidentId who) {
                        return townOf(inside, who).map(nation::hasTown).orElse(false);
                    }

                    @Override
                    public ChangeDenial notAMemberDenial() {
                        return ChangeDenial.NOT_A_CITIZEN_OF_THIS_NATION;
                    }
                };
            }
        };
    }

    private static Optional<TownId> townOf(
            final CivicTransaction transaction, final ResidentId who) {
        return transaction.residents().find(who).flatMap(Resident::town);
    }

    // --- plumbing ------------------------------------------------------------------------------

    private static void save(
            final CivicTransaction transaction,
            final OrganisationId organisation,
            final Outcome<RoleBook> outcome
    ) {
        final RoleBook updated = outcome.value().orElseThrow(() ->
                new ChangeRefusedException(outcome.denial().orElseThrow()));
        transaction.roles().save(updated);
        transaction.publishAll(outcome.events(), "roles:" + organisation.value());
    }

    private static RoleBook book(
            final CivicTransaction transaction, final OrganisationId organisation) {
        return transaction.roles().find(organisation.scope(), organisation.value())
                .orElseThrow(() -> new ChangeRefusedException(ChangeDenial.ROLE_NOT_FOUND));
    }

    private <T> CompletableFuture<ServiceResult<T>> transaction(
            final OrganisationId organisation,
            final ResidentId actor,
            final Permission required,
            final Work<T> work
    ) {
        Objects.requireNonNull(organisation, "organisation");
        Objects.requireNonNull(actor, "actor");

        return store.<ServiceResult<T>>inTransaction(transaction -> {
            final Authority authority = authorityFor(transaction, organisation);
            final RoleBook roleBook = book(transaction, organisation);
            final SystemRole standing = authority.standingOf(actor);
            if (!roleBook.allows(actor, required, standing)) {
                throw new ChangeRefusedException(ChangeDenial.MISSING_PERMISSION);
            }
            final Context context = new Context(
                    authority, roleBook, actor, standing, roleBook.rankOf(actor, standing));
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
            //
            // Both scopes now. A town's book was always cached because protection reads it on every
            // block a player touches; a nation's is cached too since chat began asking CHAT_NATION
            // and a nation role's prefix inside AsyncChatEvent, where a query is not available.
            if (!result.succeeded()) {
                return CompletableFuture.completedFuture(result);
            }
            return switch (organisation.scope()) {
                case TOWN -> civic.refresh((TownId) organisation).thenApply(ignored -> result);
                case NATION -> civic.refreshNation((net.riftbreaker.rifttowny.domain.org.NationId)
                        organisation).thenApply(ignored -> result);
            };
        });
    }

    /** Everything the guards need, resolved once per operation rather than re-read per check. */
    private record Context(
            Authority authority, RoleBook book, ResidentId actor, SystemRole standing, int rank) {
    }

    @FunctionalInterface
    private interface Work<T> {
        T perform(CivicTransaction transaction, Context context);
    }
}
