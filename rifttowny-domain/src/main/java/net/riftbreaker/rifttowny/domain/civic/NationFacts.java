package net.riftbreaker.rifttowny.domain.civic;

import net.riftbreaker.rifttowny.domain.org.Nation;
import net.riftbreaker.rifttowny.domain.org.NationId;
import net.riftbreaker.rifttowny.domain.org.OrganisationScope;
import net.riftbreaker.rifttowny.domain.org.ResidentId;
import net.riftbreaker.rifttowny.domain.org.TownId;
import net.riftbreaker.rifttowny.domain.role.Permission;
import net.riftbreaker.rifttowny.domain.role.Role;
import net.riftbreaker.rifttowny.domain.role.RoleBook;
import net.riftbreaker.rifttowny.domain.role.SystemRole;

import java.util.Objects;
import java.util.Optional;

/**
 * One nation and its roles, the counterpart to {@link TownFacts}.
 *
 * <p>Added later than the town version, and deliberately so. A town's book is read on every block a
 * player touches, which makes caching it unavoidable; a nation's is not read by protection at all,
 * so for a long time nothing needed it in memory and the honest answer was to leave it out.</p>
 *
 * <p>What changed is chat. {@code Permission.CHAT_NATION} has to be answered inside
 * {@code AsyncChatEvent}, where a query would put every line typed on the server behind the
 * database, and a nation role's chat prefix has the same problem. Both were checked once at the
 * command and then not again, which left a window where somebody kept an audience their nation had
 * taken back.</p>
 *
 * <h2>Standing needs a third thing</h2>
 *
 * <p>A nation's citizens are not its own residents — they are residents of its member towns — so
 * {@link Nation#standingOf} needs the town the person is in as well as the person. Every caller
 * here has to supply it, which is why the signatures are wider than {@link TownFacts}'. Passing
 * null means "in no town", and answers {@code VISITOR}, which is the safe direction.</p>
 */
public final class NationFacts {

    private final Nation nation;
    private final RoleBook roles;

    private NationFacts(final Nation nation, final RoleBook roles) {
        this.nation = nation;
        this.roles = roles;
    }

    /**
     * Pairs a nation with its roles.
     *
     * @throws IllegalArgumentException if the book belongs to a different organisation, for the
     *         reason {@link TownFacts#of} gives: answering from another organisation's rules is a
     *         fault that reads as a permission bug for weeks
     */
    public static NationFacts of(final Nation nation, final RoleBook roles) {
        Objects.requireNonNull(nation, "nation");
        Objects.requireNonNull(roles, "roles");
        if (roles.scope() != OrganisationScope.NATION
                || !roles.organisationId().equals(nation.id().value())) {
            throw new IllegalArgumentException(
                    "Role book " + roles.scope() + ' ' + roles.organisationId()
                            + " does not belong to nation " + nation.id().value());
        }
        return new NationFacts(nation, roles);
    }

    public NationId id() {
        return nation.id();
    }

    public Nation nation() {
        return nation;
    }

    public RoleBook roles() {
        return roles;
    }

    /** Where this person stands in the nation, given the town they belong to. */
    public SystemRole standingOf(final ResidentId who, final TownId theirTown) {
        return who == null ? SystemRole.VISITOR : nation.standingOf(who, theirTown);
    }

    /** May this person do this, as far as the nation's roles are concerned. */
    public boolean allows(
            final ResidentId who, final Permission permission, final TownId theirTown) {
        if (who == null || permission == null) {
            return false;
        }
        return roles.allows(who, permission, standingOf(who, theirTown));
    }

    /** The chat prefix of the nation role this person is shown as, if it has one. */
    public Optional<String> chatPrefixOf(final ResidentId who, final TownId theirTown) {
        if (who == null) {
            return Optional.empty();
        }
        return roles.highestRole(who, standingOf(who, theirTown)).flatMap(Role::chatPrefix);
    }

    @Override
    public boolean equals(final Object other) {
        return other instanceof NationFacts facts
                && nation.equals(facts.nation) && roles.equals(facts.roles);
    }

    @Override
    public int hashCode() {
        return Objects.hash(nation, roles);
    }

    @Override
    public String toString() {
        return "NationFacts[" + nation.id().value() + ']';
    }
}
