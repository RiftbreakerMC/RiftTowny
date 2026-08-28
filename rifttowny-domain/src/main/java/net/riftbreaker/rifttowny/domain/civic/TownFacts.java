package net.riftbreaker.rifttowny.domain.civic;

import net.riftbreaker.rifttowny.domain.org.NationId;
import net.riftbreaker.rifttowny.domain.org.OrganisationScope;
import net.riftbreaker.rifttowny.domain.org.ResidentId;
import net.riftbreaker.rifttowny.domain.org.Town;
import net.riftbreaker.rifttowny.domain.org.TownId;
import net.riftbreaker.rifttowny.domain.role.Permission;
import net.riftbreaker.rifttowny.domain.role.Role;
import net.riftbreaker.rifttowny.domain.role.RoleBook;
import net.riftbreaker.rifttowny.domain.role.SystemRole;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * One town and its roles, held together so a protection check can be answered from memory.
 *
 * <p>It holds the {@link Town} aggregate itself rather than copies of its fields. Copying would have
 * meant re-deriving "is this person a member", "who leads" and "who is trusted" here, in a second
 * place, with a second chance of drifting from the aggregate that decides them. The town is
 * immutable, so keeping a reference costs nothing and cannot go stale in place — a changed town is a
 * new instance, and the cache is handed the new one.</p>
 *
 * <p>The role book travels with it because the two are only useful together: standing comes from the
 * town, permissions come from the book, and every question worth asking needs both.</p>
 */
public final class TownFacts {

    private final Town town;
    private final RoleBook roles;

    private TownFacts(final Town town, final RoleBook roles) {
        this.town = town;
        this.roles = roles;
    }

    /**
     * Pairs a town with its roles.
     *
     * @throws IllegalArgumentException if the role book belongs to a different organisation. Mixing
     *         two towns' books would answer permission questions from the wrong town's rules, which
     *         is the kind of fault that looks like a permission bug for weeks
     */
    public static TownFacts of(final Town town, final RoleBook roles) {
        Objects.requireNonNull(town, "town");
        Objects.requireNonNull(roles, "roles");
        if (roles.scope() != OrganisationScope.TOWN
                || !roles.organisationId().equals(town.id().value())) {
            throw new IllegalArgumentException(
                    "Role book " + roles.scope() + ' ' + roles.organisationId()
                            + " does not belong to town " + town.id().value());
        }
        return new TownFacts(town, roles);
    }

    // --- identity ------------------------------------------------------------------------------

    public TownId id() {
        return town.id();
    }

    public String displayName() {
        return town.name().display();
    }

    public Optional<NationId> nation() {
        return town.nation();
    }

    public Town town() {
        return town;
    }

    public RoleBook roles() {
        return roles;
    }

    // --- questions -----------------------------------------------------------------------------

    public boolean hasResident(final ResidentId who) {
        return who != null && town.hasResident(who);
    }

    public boolean trusts(final ResidentId who) {
        return who != null && town.trustedOutsiders().contains(who);
    }

    public Set<ResidentId> residents() {
        return town.residents();
    }

    /** Leader, member or outsider, as the town itself sees it. */
    public SystemRole standingOf(final ResidentId who) {
        return who == null ? SystemRole.VISITOR : town.standingOf(who);
    }


    /**
     * The chat prefix of the role this person is shown as, if it has one.
     *
     * <p>Answered from the cache, which is what makes it usable from the chat listener: that runs
     * inside {@code AsyncChatEvent}, where a database read would put the whole server's chat behind
     * a query. A town's role book is here because protection already needs it on every block.</p>
     */
    public Optional<String> chatPrefixOf(final ResidentId who) {
        if (who == null) {
            return Optional.empty();
        }
        return roles.highestRole(who, town.standingOf(who)).flatMap(Role::chatPrefix);
    }
    /**
     * May this person do this here, as far as their roles are concerned.
     *
     * <p>Only half the answer: territory flags decide the other half, and both must agree. This is
     * the half the town's own rules govern.</p>
     */
    public boolean allows(final ResidentId who, final Permission permission) {
        if (who == null || permission == null) {
            return false;
        }
        return roles.allows(who, permission, town.standingOf(who));
    }

    @Override
    public boolean equals(final Object other) {
        return other instanceof TownFacts facts && town.id().equals(facts.town.id());
    }

    @Override
    public int hashCode() {
        return town.id().hashCode();
    }

    @Override
    public String toString() {
        return "TownFacts[" + displayName() + ", " + town.residentCount() + " resident(s)]";
    }
}
