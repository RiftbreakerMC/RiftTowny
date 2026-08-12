package net.riftbreaker.rifttowny.domain.civic;

import net.riftbreaker.rifttowny.domain.naming.NamePolicy;
import net.riftbreaker.rifttowny.domain.naming.OrganisationName;
import net.riftbreaker.rifttowny.domain.org.OrganisationScope;
import net.riftbreaker.rifttowny.domain.org.ResidentId;
import net.riftbreaker.rifttowny.domain.org.Town;
import net.riftbreaker.rifttowny.domain.org.TownId;
import net.riftbreaker.rifttowny.domain.role.Permission;
import net.riftbreaker.rifttowny.domain.role.RoleBook;
import net.riftbreaker.rifttowny.domain.role.RoleId;
import net.riftbreaker.rifttowny.domain.role.SystemRole;

import java.time.Instant;
import java.util.UUID;

/**
 * Towns and role books for the tests that need them, built the way the real code builds them.
 *
 * <p>Shared rather than duplicated per test class so a change to how a town is founded breaks in one
 * place instead of five.</p>
 */
public final class CivicFixture {

    public static final Instant NOW = Instant.parse("2026-08-12T09:00:00Z");

    private CivicFixture() {
    }

    public static ResidentId resident() {
        return ResidentId.of(UUID.randomUUID());
    }

    public static OrganisationName name(final String raw) {
        return NamePolicy.defaults().check(raw).accepted().orElseThrow();
    }

    /** A town with the given mayor, admitting every extra resident. */
    public static Town town(final String display, final ResidentId mayor, final ResidentId... extra) {
        Town town = Town.found(TownId.random(), name(display), mayor, UUID.randomUUID(), NOW);
        for (final ResidentId resident : extra) {
            town = town.admit(resident).orElseThrow();
        }
        return town;
    }

    /** The three system roles, as a freshly founded town would have them. */
    public static RoleBook roles(final Town town) {
        return RoleBook.defaultsFor(OrganisationScope.TOWN, town.id().value(), NOW);
    }

    /** The same, with one permission taken away from the member baseline. */
    public static RoleBook rolesWithoutMemberPermission(
            final Town town, final Permission permission) {
        final RoleBook book = roles(town);
        final RoleId member = book.systemRole(SystemRole.MEMBER).orElseThrow().id();
        return book.revoke(member, permission).orElseThrow();
    }

    public static TownFacts facts(final Town town) {
        return TownFacts.of(town, roles(town));
    }
}
