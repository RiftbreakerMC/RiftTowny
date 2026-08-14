package net.riftbreaker.rifttowny.storage;

import net.riftbreaker.rifttowny.domain.civic.CivicCache;
import net.riftbreaker.rifttowny.domain.naming.NamePolicy;
import net.riftbreaker.rifttowny.domain.org.ChangeDenial;
import net.riftbreaker.rifttowny.domain.org.MapColour;
import net.riftbreaker.rifttowny.domain.org.Resident;
import net.riftbreaker.rifttowny.domain.org.ResidentId;
import net.riftbreaker.rifttowny.domain.org.Town;
import net.riftbreaker.rifttowny.domain.org.TownProfile;
import net.riftbreaker.rifttowny.domain.role.Permission;
import net.riftbreaker.rifttowny.domain.service.CivicCacheService;
import net.riftbreaker.rifttowny.domain.service.TownRoleService;
import net.riftbreaker.rifttowny.domain.service.TownService;
import net.riftbreaker.rifttowny.domain.territory.TerritoryIndex;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a town says about itself, through storage.
 *
 * <p>The domain tests cover the rules. What only a real database can show is the round trip: six
 * values added to a table that already had seven, written by an upsert whose column list and
 * placeholder list are maintained by hand, and read back through a restore that has two overloads.
 * Every one of those is a place a value can be silently dropped, and a dropped board looks exactly
 * like a board nobody set.</p>
 */
class TownProfileTest extends SqliteFixture {

    private static final Instant NOW = Instant.parse("2026-08-12T09:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static final ResidentId MAYOR = ResidentId.of(UUID.randomUUID());
    private static final ResidentId OUTSIDER = ResidentId.of(UUID.randomUUID());

    private final CivicCache civicCache = CivicCache.empty();

    private JdbcCivicStore store;
    private TownService towns;
    private TownRoleService roles;
    private JdbcResidentRepository residents;

    @BeforeEach
    void createServices() {
        store = new JdbcCivicStore(database, DIRECT, CLOCK);
        towns = new TownService(store, NamePolicy.defaults(), CLOCK, TerritoryIndex.empty(),
                new CivicCacheService(store, civicCache, warning -> { }));
        roles = new TownRoleService(store, CLOCK, java.util.Set.of(),
                new CivicCacheService(store, civicCache, warning -> { }));
        residents = new JdbcResidentRepository(database, DIRECT);
    }

    private Town riftholm() {
        residents.save(Resident.newcomer(MAYOR, "Mayor", NOW)).join();
        residents.save(Resident.newcomer(OUTSIDER, "Outsider", NOW)).join();
        return towns.found(MAYOR, "Mayor", "Riftholm").join().value().orElseThrow();
    }

    @Nested
    @DisplayName("the round trip")
    class RoundTrip {

        @Test
        @DisplayName("every setting survives being written and read back")
        void everySettingRoundTrips() {
            final Town town = riftholm();

            towns.setProfile(MAYOR, town.id(), profile -> profile
                    .withBoard("Welcome to Riftholm")
                    .withTag("RIFT")
                    .withColour(MapColour.parse("#a1b2c3").orElseThrow())
                    .withNeutral(true)
                    .withOpen(true)
                    .withPublicSpawn(true)).join();

            final Town reloaded =
                    store.inTransaction(t -> t.towns().find(town.id()).orElseThrow()).join();

            assertThat(reloaded.profile().board()).isEqualTo("Welcome to Riftholm");
            assertThat(reloaded.profile().tag()).isEqualTo("RIFT");
            assertThat(reloaded.profile().mapColour()).contains(MapColour.parse("#a1b2c3").orElseThrow());
            assertThat(reloaded.profile().neutral()).isTrue();
            assertThat(reloaded.profile().open()).isTrue();
            assertThat(reloaded.profile().publicSpawn()).isTrue();
        }

        @Test
        @DisplayName("a town that has said nothing reads back as having said nothing")
        void defaultsRoundTrip() {
            final Town town = riftholm();

            final Town reloaded =
                    store.inTransaction(t -> t.towns().find(town.id()).orElseThrow()).join();

            // Not null, and not a stray "null" string from a NULL column read as text.
            assertThat(reloaded.profile()).isEqualTo(TownProfile.empty());
            assertThat(reloaded.profile().board()).isEmpty();
        }

        @Test
        @DisplayName("clearing a value really clears it, rather than leaving the old one")
        void clearingPersists() {
            final Town town = riftholm();
            towns.setProfile(MAYOR, town.id(), profile -> profile.withBoard("Temporary")).join();

            towns.setProfile(MAYOR, town.id(), profile -> profile.withBoard("")).join();

            final Town reloaded =
                    store.inTransaction(t -> t.towns().find(town.id()).orElseThrow()).join();
            assertThat(reloaded.profile().hasBoard()).isFalse();
        }

        @Test
        @DisplayName("a rename does not lose the profile")
        void renameKeepsTheProfile() {
            // The upsert lists board and tag in its update clause; a rename that forgot them would
            // reset a town's board every time it changed its name.
            final Town town = riftholm();
            towns.setProfile(MAYOR, town.id(), profile -> profile.withBoard("Welcome")).join();

            towns.rename(MAYOR, town.id(), "Highholm").join();

            final Town reloaded =
                    store.inTransaction(t -> t.towns().find(town.id()).orElseThrow()).join();
            assertThat(reloaded.name().display()).isEqualTo("Highholm");
            assertThat(reloaded.profile().board()).isEqualTo("Welcome");
        }
    }

    @Nested
    @DisplayName("who may change it")
    class Authority {

        @Test
        @DisplayName("a resident without MANAGE_SETTINGS is refused")
        void needsThePermission() {
            final Town town = riftholm();
            towns.invite(MAYOR, town.id(), OUTSIDER).join();
            towns.acceptInvitation(OUTSIDER, town.id()).join();

            // The member baseline does not carry MANAGE_SETTINGS, so an ordinary resident cannot
            // rewrite what the town says about itself.
            final var refused =
                    towns.setProfile(OUTSIDER, town.id(), profile -> profile.withBoard("mine now"))
                            .join();

            assertThat(refused.succeeded()).isFalse();
            assertThat(refused.denial()).contains(ChangeDenial.MISSING_PERMISSION);
        }

        @Test
        @DisplayName("a member the town has granted MANAGE_SETTINGS may change it")
        void grantedMembersMay() {
            final Town town = riftholm();
            towns.invite(MAYOR, town.id(), OUTSIDER).join();
            towns.acceptInvitation(OUTSIDER, town.id()).join();

            // Found by its system role rather than by name: a town's member role is called
            // "Resident", and a nation's is called "Citizen".
            final var member = roles.list(town.id()).join().stream()
                    .filter(role -> role.isSystem()
                            && role.name().equalsIgnoreCase(
                                    net.riftbreaker.rifttowny.domain.role.SystemRole.MEMBER
                                            .defaultDisplayName(false)))
                    .findFirst()
                    .orElseThrow();
            roles.grant(MAYOR, town.id(), member.id(), Permission.MANAGE_SETTINGS).join();

            assertThat(towns.setProfile(OUTSIDER, town.id(), p -> p.withBoard("ours")).join()
                    .succeeded()).isTrue();
        }
    }

    @Nested
    @DisplayName("an open town")
    class Openness {

        @Test
        @DisplayName("nobody may walk into a closed town")
        void closedTownsRefuse() {
            final Town town = riftholm();

            final var refused = towns.joinOpenTown(OUTSIDER, town.id()).join();

            assertThat(refused.succeeded()).isFalse();
            assertThat(refused.denial()).contains(ChangeDenial.TOWN_IS_NOT_OPEN);
        }

        @Test
        @DisplayName("anybody may walk into an open one, with no invitation at all")
        void openTownsAdmit() {
            final Town town = riftholm();
            towns.setProfile(MAYOR, town.id(), profile -> profile.withOpen(true)).join();

            assertThat(towns.joinOpenTown(OUTSIDER, town.id()).join().succeeded()).isTrue();
            assertThat(store.inTransaction(t -> t.residents().find(OUTSIDER).orElseThrow().town())
                    .join())
                    .contains(town.id());
        }

        @Test
        @DisplayName("closing the town again shuts the door")
        void closingWorks() {
            final Town town = riftholm();
            towns.setProfile(MAYOR, town.id(), profile -> profile.withOpen(true)).join();
            towns.setProfile(MAYOR, town.id(), profile -> profile.withOpen(false)).join();

            assertThat(towns.joinOpenTown(OUTSIDER, town.id()).join().denial())
                    .contains(ChangeDenial.TOWN_IS_NOT_OPEN);
        }

        @Test
        @DisplayName("walking in clears the invitation that was already outstanding")
        void walkingInConsumesAnyInvitation() {
            // Otherwise somebody invited to an open town keeps an offer they can accept a second
            // time, which is a second admit for a resident who is already there.
            final Town town = riftholm();
            towns.setProfile(MAYOR, town.id(), profile -> profile.withOpen(true)).join();
            towns.invite(MAYOR, town.id(), OUTSIDER).join();

            towns.joinOpenTown(OUTSIDER, town.id()).join();

            assertThat(towns.invitationsFor(OUTSIDER).join()).isEmpty();
        }

        @Test
        @DisplayName("somebody already in the town cannot join it twice")
        void residentsCannotRejoin() {
            final Town town = riftholm();
            towns.setProfile(MAYOR, town.id(), profile -> profile.withOpen(true)).join();

            assertThat(towns.joinOpenTown(MAYOR, town.id()).join().succeeded()).isFalse();
        }
    }
}
