package net.riftbreaker.rifttowny.storage;

import net.riftbreaker.rifttowny.domain.civic.CivicCache;
import net.riftbreaker.rifttowny.domain.naming.NamePolicy;
import net.riftbreaker.rifttowny.domain.org.ChangeDenial;
import net.riftbreaker.rifttowny.domain.org.Resident;
import net.riftbreaker.rifttowny.domain.org.ResidentId;
import net.riftbreaker.rifttowny.domain.org.Town;
import net.riftbreaker.rifttowny.domain.service.CivicCacheService;
import net.riftbreaker.rifttowny.domain.service.TownService;
import net.riftbreaker.rifttowny.domain.territory.TerritoryIndex;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Joining a town takes the player's consent as well as the town's.
 *
 * <p>The unilateral path still exists for administration and imports, and is tested here too — the
 * point being that it is the exception rather than what a command reaches.
 */
class TownInvitationTest extends SqliteFixture {

    private static final Instant NOW = Instant.parse("2026-08-12T09:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static final ResidentId MAYOR = ResidentId.of(UUID.randomUUID());
    private static final ResidentId NEWCOMER = ResidentId.of(UUID.randomUUID());
    private static final ResidentId CITIZEN = ResidentId.of(UUID.randomUUID());

    private final CivicCache civicCache = CivicCache.empty();

    private JdbcCivicStore store;
    private TownService towns;
    private JdbcResidentRepository residents;

    @BeforeEach
    void createServices() {
        store = new JdbcCivicStore(database, DIRECT, CLOCK);
        towns = new TownService(store, NamePolicy.defaults(), CLOCK, TerritoryIndex.empty(),
                new CivicCacheService(store, civicCache, warning -> { }));
        residents = new JdbcResidentRepository(database, DIRECT);
    }

    private Town riftholm() {
        residents.save(Resident.newcomer(MAYOR, "Mayor", NOW)).join();
        residents.save(Resident.newcomer(NEWCOMER, "Newcomer", NOW)).join();
        return towns.found(MAYOR, "Mayor", "Riftholm").join().value().orElseThrow();
    }

    @Nested
    @DisplayName("the offer")
    class Offering {

        @Test
        @DisplayName("an invitation alone does not move the player")
        void invitingIsNotJoining() {
            final Town town = riftholm();

            assertThat(towns.invite(MAYOR, town.id(), NEWCOMER).join().succeeded()).isTrue();

            assertThat(store.inTransaction(t -> t.residents().find(NEWCOMER).orElseThrow().town())
                    .join())
                    .as("a town that could conscript somebody would be deciding for them")
                    .isEmpty();
            assertThat(towns.invitationsFor(NEWCOMER).join()).hasSize(1);
            assertThat(towns.invitationsFrom(town.id()).join()).hasSize(1);
        }

        @Test
        @DisplayName("inviting needs INVITE_RESIDENT")
        void invitingNeedsPermission() {
            final Town town = riftholm();
            residents.save(Resident.newcomer(CITIZEN, "Citizen", NOW)).join();
            towns.join(MAYOR, CITIZEN, town.id()).join();

            assertThat(towns.invite(CITIZEN, town.id(), NEWCOMER).join().denial())
                    .contains(ChangeDenial.MISSING_PERMISSION);
        }

        @Test
        @DisplayName("somebody who already has a town is not invited into another")
        void invitingSomebodyWithATown() {
            final Town town = riftholm();
            towns.found(NEWCOMER, "Newcomer", "Ashford").join();

            assertThat(towns.invite(MAYOR, town.id(), NEWCOMER).join().denial())
                    .contains(ChangeDenial.ALREADY_IN_ANOTHER_TOWN);
        }

        @Test
        @DisplayName("an existing resident is not invited again")
        void invitingAResident() {
            final Town town = riftholm();

            assertThat(towns.invite(MAYOR, town.id(), MAYOR).join().denial())
                    .contains(ChangeDenial.ALREADY_IN_THIS_TOWN);
        }

        @Test
        @DisplayName("re-inviting refreshes the offer rather than stacking a second one")
        void reInvitingRefreshes() {
            final Town town = riftholm();

            towns.invite(MAYOR, town.id(), NEWCOMER).join();
            towns.invite(MAYOR, town.id(), NEWCOMER).join();

            assertThat(towns.invitationsFor(NEWCOMER).join()).hasSize(1);
        }

        @Test
        @DisplayName("an offer can be withdrawn before it is taken")
        void withdrawing() {
            final Town town = riftholm();
            towns.invite(MAYOR, town.id(), NEWCOMER).join();

            assertThat(towns.withdrawInvitation(MAYOR, town.id(), NEWCOMER).join().succeeded())
                    .isTrue();

            assertThat(towns.invitationsFor(NEWCOMER).join()).isEmpty();
            assertThat(towns.acceptInvitation(NEWCOMER, town.id()).join().denial())
                    .contains(ChangeDenial.NO_INVITATION);
        }
    }

    @Nested
    @DisplayName("the answer")
    class Answering {

        @Test
        @DisplayName("accepting joins the town")
        void accepting() {
            final Town town = riftholm();
            towns.invite(MAYOR, town.id(), NEWCOMER).join();

            final Town updated =
                    towns.acceptInvitation(NEWCOMER, town.id()).join().value().orElseThrow();

            assertThat(updated.hasResident(NEWCOMER)).isTrue();
            assertThat(civicCache.townOf(NEWCOMER))
                    .as("and the cache follows, or protection would still treat them as an outsider")
                    .contains(town.id());
        }

        @Test
        @DisplayName("accepting consumes the offer, so it cannot be used twice")
        void acceptingConsumesTheOffer() {
            final Town town = riftholm();
            towns.invite(MAYOR, town.id(), NEWCOMER).join();
            towns.acceptInvitation(NEWCOMER, town.id()).join();
            towns.leave(NEWCOMER, town.id()).join();

            assertThat(towns.acceptInvitation(NEWCOMER, town.id()).join().denial())
                    .contains(ChangeDenial.NO_INVITATION);
        }

        @Test
        @DisplayName("nobody joins a town that never asked them")
        void acceptingWithoutAnOffer() {
            final Town town = riftholm();

            assertThat(towns.acceptInvitation(NEWCOMER, town.id()).join().denial())
                    .contains(ChangeDenial.NO_INVITATION);
        }

        @Test
        @DisplayName("declining removes the offer without joining")
        void declining() {
            final Town town = riftholm();
            towns.invite(MAYOR, town.id(), NEWCOMER).join();

            assertThat(towns.declineInvitation(NEWCOMER, town.id()).join().succeeded()).isTrue();

            assertThat(towns.invitationsFor(NEWCOMER).join()).isEmpty();
            assertThat(store.inTransaction(t -> t.residents().find(NEWCOMER).orElseThrow().town())
                    .join()).isEmpty();
        }

        @Test
        @DisplayName("a lapsed offer is refused, and hidden from the player's list")
        void expiredOffers() {
            final Town town = riftholm();
            towns.invite(MAYOR, town.id(), NEWCOMER).join();

            final TownService later = new TownService(
                    store, NamePolicy.defaults(),
                    Clock.fixed(NOW.plus(Duration.ofDays(30)), ZoneOffset.UTC),
                    TerritoryIndex.empty(),
                    new CivicCacheService(store, civicCache, warning -> { }));

            assertThat(later.acceptInvitation(NEWCOMER, town.id()).join().denial())
                    .contains(ChangeDenial.INVITATION_EXPIRED);
            assertThat(later.invitationsFor(NEWCOMER).join())
                    .as("offering something that cannot be accepted reads as a bug to a player")
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("a disbanded town takes its outstanding offers with it")
    void disbandSweepsOffers() {
        final Town town = riftholm();
        towns.invite(MAYOR, town.id(), NEWCOMER).join();

        towns.disband(MAYOR, town.id()).join();

        assertThat(towns.invitationsFor(NEWCOMER).join())
                .as("an offer into a town that no longer exists fails confusingly rather than "
                        + "simply not being there")
                .isEmpty();
    }

    @Test
    @DisplayName("the forced path still exists, and still needs the permission")
    void theForcedPathIsStillThere() {
        final Town town = riftholm();

        // No invitation involved. Kept for administration and migration imports, where the consent
        // happened elsewhere; no command reaches it.
        assertThat(towns.join(MAYOR, NEWCOMER, town.id()).join().succeeded()).isTrue();
        assertThat(civicCache.townOf(NEWCOMER)).contains(town.id());
    }
}
