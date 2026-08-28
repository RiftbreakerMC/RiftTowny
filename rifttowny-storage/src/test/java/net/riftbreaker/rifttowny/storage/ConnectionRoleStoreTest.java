package net.riftbreaker.rifttowny.storage;

import net.riftbreaker.rifttowny.domain.org.OrganisationScope;
import net.riftbreaker.rifttowny.domain.org.ResidentId;
import net.riftbreaker.rifttowny.domain.role.Permission;
import net.riftbreaker.rifttowny.domain.role.Role;
import net.riftbreaker.rifttowny.domain.role.RoleBook;
import net.riftbreaker.rifttowny.domain.role.RoleId;
import net.riftbreaker.rifttowny.domain.role.SystemRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectionRoleStoreTest extends SqliteFixture {

    private static final Instant NOW = Instant.parse("2026-08-10T12:00:00Z");
    private static final UUID TOWN = UUID.randomUUID();
    private static final UUID OTHER_TOWN = UUID.randomUUID();
    private static final ResidentId OFFICER = ResidentId.of(UUID.randomUUID());

    private JdbcCivicStore store;

    @BeforeEach
    void createStore() {
        store = new JdbcCivicStore(database, DIRECT);
    }

    private static Role officer(final int priority, final Permission... permissions) {
        return Role.custom(
                RoleId.random(), OrganisationScope.TOWN, TOWN, "Officer", priority,
                Set.of(permissions), NOW);
    }

    private RoleBook saveAndReload(final RoleBook book) {
        store.inTransaction(transaction -> {
            transaction.roles().save(book);
            return null;
        }).join();
        return store.inTransaction(transaction ->
                transaction.roles().find(OrganisationScope.TOWN, TOWN).orElseThrow()).join();
    }



    @Test
    @DisplayName("holders load in the order they were granted, not by id")
    void holdersLoadInGrantOrder() throws Exception {
        // rt_role_member.granted_at is load-bearing and does not look it: it is never selected into
        // a field and never surfaced, so it reads as bookkeeping. The only thing that says
        // otherwise is "ORDER BY granted_at, resident_id" in the assignment load, and nothing
        // pinned it - a sweep for write-only columns nearly deleted it on that basis.
        //
        // The grant times are set by hand here because save() stamps them with Instant.now(), and
        // three assignments in one test land in the same millisecond: the tie falls through to
        // resident_id and the ordering this is about never shows.
        final Role role = officer(500);
        final java.util.List<ResidentId> holders = new java.util.ArrayList<>(java.util.List.of(
                ResidentId.of(UUID.randomUUID()),
                ResidentId.of(UUID.randomUUID()),
                ResidentId.of(UUID.randomUUID())));
        // Sorted by id, so "granted in reverse id order" is a real distinction to detect.
        holders.sort(java.util.Comparator.comparing(id -> id.value().toString()));

        RoleBook book = RoleBook.defaultsFor(OrganisationScope.TOWN, TOWN, NOW)
                .create(role, Set.of()).orElseThrow();
        for (final ResidentId holder : holders) {
            book = book.assign(holder, role.id()).orElseThrow();
        }
        saveAndReload(book);

        final java.util.List<ResidentId> granted = new java.util.ArrayList<>(holders);
        java.util.Collections.reverse(granted);
        for (int i = 0; i < granted.size(); i++) {
            stampGrant(role.id(), granted.get(i), NOW.toEpochMilli() + i);
        }

        final RoleBook reloaded = store.inTransaction(transaction ->
                transaction.roles().find(OrganisationScope.TOWN, TOWN).orElseThrow()).join();

        assertThat(reloaded.holdersOf(role.id()))
                .as("first granted, first listed - and deliberately not id order")
                .containsExactlyElementsOf(granted);

        // And it survives an edit that has nothing to do with the assignments. save() replaces
        // every row, so without re-reading each holder's original time first they would all be
        // stamped with one instant and the order would collapse back to id order.
        final RoleBook afterRename =
                saveAndReload(reloaded.rename(role.id(), "Marshal").orElseThrow());

        assertThat(afterRename.holdersOf(afterRename.findByName("Marshal").orElseThrow().id()))
                .as("a rename must not reshuffle who holds the role")
                .containsExactlyElementsOf(granted);
    }

    /** Sets one assignment's grant time, which save() would otherwise stamp with the clock. */
    private void stampGrant(final RoleId role, final ResidentId holder, final long at)
            throws Exception {
        database.write(connection -> {
            try (java.sql.PreparedStatement statement = connection.prepareStatement(
                    "UPDATE rt_role_member SET granted_at = ? "
                            + "WHERE role_id = ? AND resident_id = ?")) {
                statement.setLong(1, at);
                statement.setString(2, role.value().toString());
                statement.setString(3, holder.value().toString());
                statement.executeUpdate();
            }
            return null;
        });
    }
    void missingBookIsEmpty() {
        final var found = store.inTransaction(transaction ->
                transaction.roles().find(OrganisationScope.TOWN, TOWN)).join();

        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("the three system roles round-trip with their identities intact")
    void systemRolesRoundTrip() {
        final RoleBook reloaded = saveAndReload(
                RoleBook.defaultsFor(OrganisationScope.TOWN, TOWN, NOW));

        assertThat(reloaded.size()).isEqualTo(3);
        assertThat(reloaded.systemRole(SystemRole.LEADER)).isPresent();
        assertThat(reloaded.systemRole(SystemRole.MEMBER)).isPresent();
        assertThat(reloaded.systemRole(SystemRole.VISITOR)).isPresent();
        assertThat(reloaded.ordered()).extracting(Role::name)
                .containsExactly("Mayor", "Resident", "Visitor");
    }

    @Test
    @DisplayName("the leader is still recognisable as the leader after a reload")
    void leaderSurvivesAsALeader() {
        final RoleBook reloaded = saveAndReload(
                RoleBook.defaultsFor(OrganisationScope.TOWN, TOWN, NOW));

        final Role leader = reloaded.systemRole(SystemRole.LEADER).orElseThrow();
        assertThat(leader.isLeader()).isTrue();
        assertThat(leader.permissions()).containsExactlyInAnyOrder(Permission.values());
        assertThat(reloaded.allows(OFFICER, Permission.DISBAND, SystemRole.LEADER)).isTrue();
    }

    @Test
    @DisplayName("a renamed system role keeps its type, so it is still undeletable")
    void renamedSystemRoleKeepsItsType() {
        final RoleBook book = RoleBook.defaultsFor(OrganisationScope.TOWN, TOWN, NOW);
        final RoleId leader = book.systemRole(SystemRole.LEADER).orElseThrow().id();

        final RoleBook reloaded = saveAndReload(book.rename(leader, "Jarl").orElseThrow());

        assertThat(reloaded.systemRole(SystemRole.LEADER).orElseThrow().name()).isEqualTo("Jarl");
        assertThat(reloaded.delete(leader).denial())
                .contains(net.riftbreaker.rifttowny.domain.org.ChangeDenial
                        .SYSTEM_ROLE_CANNOT_BE_DELETED);
    }

    @Test
    @DisplayName("a configurable role round-trips with its permissions and rank")
    void customRoleRoundTrips() {
        final Role role = officer(500, Permission.CLAIM_LAND, Permission.INVITE_RESIDENT);
        final RoleBook book = RoleBook.defaultsFor(OrganisationScope.TOWN, TOWN, NOW)
                .create(role, Set.of()).orElseThrow();

        final RoleBook reloaded = saveAndReload(book);

        final Role loaded = reloaded.findByName("Officer").orElseThrow();
        assertThat(loaded.priority()).isEqualTo(500);
        assertThat(loaded.isSystem()).isFalse();
        assertThat(loaded.permissions())
                .containsExactlyInAnyOrder(Permission.CLAIM_LAND, Permission.INVITE_RESIDENT);
    }

    @Test
    @DisplayName("assignments round-trip and resolve to the same permissions")
    void assignmentsRoundTrip() {
        final Role role = officer(500, Permission.CLAIM_LAND);
        final RoleBook book = RoleBook.defaultsFor(OrganisationScope.TOWN, TOWN, NOW)
                .create(role, Set.of()).orElseThrow()
                .assign(OFFICER, role.id()).orElseThrow();

        final RoleBook reloaded = saveAndReload(book);

        assertThat(reloaded.rolesOf(OFFICER)).containsExactly(role.id());
        assertThat(reloaded.allows(OFFICER, Permission.CLAIM_LAND, SystemRole.MEMBER)).isTrue();
        assertThat(reloaded.rankOf(OFFICER, SystemRole.MEMBER)).isEqualTo(500);
    }

    @Test
    @DisplayName("saving replaces the previous set rather than accumulating rows")
    void saveReplacesRatherThanAccumulates() {
        final Role role = officer(500);
        final RoleBook withRole = RoleBook.defaultsFor(OrganisationScope.TOWN, TOWN, NOW)
                .create(role, Set.of()).orElseThrow();
        saveAndReload(withRole);

        final RoleBook reloaded = saveAndReload(withRole.delete(role.id()).orElseThrow());

        assertThat(reloaded.size())
                .as("a replace, not an append: the deleted role must not survive")
                .isEqualTo(3);
        assertThat(reloaded.findByName("Officer")).isEmpty();
    }

    @Test
    @DisplayName("deleting a role removes its assignments by cascade")
    void deletingCascadesAssignments() {
        final Role role = officer(500);
        final RoleBook book = RoleBook.defaultsFor(OrganisationScope.TOWN, TOWN, NOW)
                .create(role, Set.of()).orElseThrow()
                .assign(OFFICER, role.id()).orElseThrow();
        saveAndReload(book);

        final RoleBook reloaded = saveAndReload(book.delete(role.id()).orElseThrow());

        assertThat(reloaded.rolesOf(OFFICER)).isEmpty();
    }

    @Test
    @DisplayName("one organisation's roles are invisible to another")
    void booksAreScopedToTheirOrganisation() {
        saveAndReload(RoleBook.defaultsFor(OrganisationScope.TOWN, TOWN, NOW));

        final var other = store.inTransaction(transaction ->
                transaction.roles().find(OrganisationScope.TOWN, OTHER_TOWN)).join();

        assertThat(other).isEmpty();
    }

    @Test
    @DisplayName("a town and a nation may share an id without sharing roles")
    void scopeSeparatesTownAndNationBooks() {
        saveAndReload(RoleBook.defaultsFor(OrganisationScope.TOWN, TOWN, NOW));

        final var asNation = store.inTransaction(transaction ->
                transaction.roles().find(OrganisationScope.NATION, TOWN)).join();

        assertThat(asNation)
                .as("scope is part of the key, so the same UUID in both scopes is two books")
                .isEmpty();
    }

    @Test
    @DisplayName("deleting an organisation's book removes every role")
    void deleteRemovesTheWholeBook() {
        saveAndReload(RoleBook.defaultsFor(OrganisationScope.TOWN, TOWN, NOW));

        final boolean removed = store.inTransaction(transaction ->
                transaction.roles().delete(OrganisationScope.TOWN, TOWN)).join();

        assertThat(removed).isTrue();
        assertThat(store.inTransaction(transaction ->
                transaction.roles().find(OrganisationScope.TOWN, TOWN)).join()).isEmpty();
    }

    @Test
    @DisplayName("deleting a book that is not there reports false")
    void deletingAbsentBookIsFalse() {
        assertThat(store.inTransaction(transaction ->
                transaction.roles().delete(OrganisationScope.TOWN, TOWN)).join()).isFalse();
    }

    @Test
    @DisplayName("decoration round-trips, so a renamed leader keeps its icon and prefix")
    void decorationRoundTrips() {
        final RoleBook book = RoleBook.defaultsFor(OrganisationScope.TOWN, TOWN, NOW);
        final Role leader = book.systemRole(SystemRole.LEADER).orElseThrow();
        final Role decorated = leader.decorate("The Jarl", "NETHER_STAR", "<gold>[Jarl]</gold>");
        final RoleBook updated = RoleBook.restore(
                OrganisationScope.TOWN, TOWN,
                java.util.List.of(
                        decorated,
                        book.systemRole(SystemRole.MEMBER).orElseThrow(),
                        book.systemRole(SystemRole.VISITOR).orElseThrow()),
                java.util.Map.of());

        final RoleBook reloaded = saveAndReload(updated);

        final Role loaded = reloaded.systemRole(SystemRole.LEADER).orElseThrow();
        assertThat(loaded.displayName()).isEqualTo("The Jarl");
        assertThat(loaded.icon()).contains("NETHER_STAR");
        assertThat(loaded.chatPrefix()).contains("<gold>[Jarl]</gold>");
    }
}
