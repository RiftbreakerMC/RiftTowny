package net.riftbreaker.rifttowny.domain.service;

import net.riftbreaker.rifttowny.domain.org.NationId;
import net.riftbreaker.rifttowny.domain.org.OrganisationScope;
import net.riftbreaker.rifttowny.domain.org.Outcome;
import net.riftbreaker.rifttowny.domain.org.ResidentId;
import net.riftbreaker.rifttowny.domain.role.RoleBook;
import net.riftbreaker.rifttowny.domain.store.CivicTransaction;

import java.util.Collection;
import java.util.List;

/**
 * Taking back the nation roles somebody stops being entitled to.
 *
 * <p>Nothing ties {@code rt_role_member} to citizenship, and a nation's citizens are not its own
 * residents — they are residents of its member towns. So citizenship ends in three different places
 * and none of them is a single event: a resident leaves their town, a town leaves the nation, or a
 * town is disbanded out from under them. Every one of those has to reach here, or the person keeps
 * an officer's authority over a nation they no longer belong to.</p>
 *
 * <p>A town's own roles had the same hole and it was closed with {@code RoleBook.unassignAll} on
 * release. This is the nation-shaped version of that fix, gathered in one place precisely because
 * there are three callers and the fourth one added later is the one that would forget.</p>
 *
 * <p><strong>Roles are not restored by coming back.</strong> Somebody who leaves a member town and
 * joins another town in the same nation loses their nation role and does not get it back. The role
 * was granted to them personally; re-granting it is a decision the nation makes again, and the safe
 * direction to be wrong in is the one that hands out less authority.</p>
 */
final class CitizenRoles {

    private CitizenRoles() {
    }

    /**
     * Revokes every nation role these people hold.
     *
     * <p>Silent when the nation has no role book: one that is missing has no assignments to revoke,
     * and refusing a departure over it would trap the resident.</p>
     *
     * @return the events produced, for the caller to publish under its own correlation id
     */
    static List<net.riftbreaker.rifttowny.domain.event.DomainEvent> revoke(
            final CivicTransaction transaction,
            final NationId nation,
            final Collection<ResidentId> people
    ) {
        if (nation == null || people == null || people.isEmpty()) {
            return List.of();
        }
        final java.util.Optional<RoleBook> found =
                transaction.roles().find(OrganisationScope.NATION, nation.value());
        if (found.isEmpty()) {
            return List.of();
        }

        RoleBook book = found.get();
        final List<net.riftbreaker.rifttowny.domain.event.DomainEvent> events =
                new java.util.ArrayList<>();
        boolean changed = false;
        for (final ResidentId who : people) {
            final Outcome<RoleBook> stripped = book.unassignAll(who);
            final java.util.Optional<RoleBook> updated = stripped.value();
            if (updated.isPresent()) {
                // unassignAll applies even when there was nothing to take, so the events are what
                // says whether anything actually moved.
                changed |= !stripped.events().isEmpty();
                book = updated.get();
                events.addAll(stripped.events());
            }
        }
        if (changed) {
            transaction.roles().save(book);
        }
        return List.copyOf(events);
    }
}
