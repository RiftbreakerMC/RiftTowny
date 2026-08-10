package net.riftbreaker.rifttowny.domain.org;

import net.riftbreaker.rifttowny.domain.event.DomainEvent;
import net.riftbreaker.rifttowny.domain.naming.NamePolicy;
import net.riftbreaker.rifttowny.domain.naming.OrganisationName;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResidentAndNationTest {

    private static final Instant NOW = Instant.parse("2026-08-10T12:00:00Z");
    private static final ResidentId ALDER = ResidentId.of(UUID.randomUUID());
    private static final ResidentId BRIAR = ResidentId.of(UUID.randomUUID());
    private static final TownId RIFTHOLM = TownId.random();
    private static final TownId ASHFORD = TownId.random();

    private static OrganisationName name(final String raw) {
        return NamePolicy.defaults().check(raw).accepted().orElseThrow();
    }

    @Nested
    @DisplayName("a resident belongs to exactly one town")
    class ResidentMembership {

        private Resident newcomer() {
            return Resident.newcomer(ALDER, "Alder", NOW);
        }

        @Test
        @DisplayName("a newcomer belongs to no town")
        void newcomerHasNoTown() {
            assertThat(newcomer().town()).isEmpty();
            assertThat(newcomer().hasTown()).isFalse();
        }

        @Test
        @DisplayName("joining records the town")
        void joiningRecordsTheTown() {
            final Resident joined = newcomer().joinTown(RIFTHOLM).orElseThrow();

            assertThat(joined.town()).contains(RIFTHOLM);
            assertThat(joined.isResidentOf(RIFTHOLM)).isTrue();
            assertThat(joined.isResidentOf(ASHFORD)).isFalse();
        }

        @Test
        @DisplayName("joining a second town is refused, which is the one-town invariant")
        void secondTownIsRefused() {
            final Resident joined = newcomer().joinTown(RIFTHOLM).orElseThrow();

            assertThat(joined.joinTown(ASHFORD).denial())
                    .contains(ChangeDenial.ALREADY_IN_ANOTHER_TOWN);
        }

        @Test
        @DisplayName("rejoining the same town gets a distinct denial")
        void rejoiningSameTown() {
            final Resident joined = newcomer().joinTown(RIFTHOLM).orElseThrow();

            assertThat(joined.joinTown(RIFTHOLM).denial())
                    .contains(ChangeDenial.ALREADY_IN_THIS_TOWN);
        }

        @Test
        @DisplayName("a townless resident cannot leave")
        void townlessCannotLeave() {
            assertThat(newcomer().leaveTown().denial())
                    .contains(ChangeDenial.NOT_A_RESIDENT_OF_THIS_TOWN);
        }

        @Test
        @DisplayName("leaving clears the town and allows joining another")
        void leavingFreesTheResident() {
            final Resident free = newcomer().joinTown(RIFTHOLM).orElseThrow()
                    .leaveTown().orElseThrow();

            assertThat(free.town()).isEmpty();
            assertThat(free.joinTown(ASHFORD).wasApplied()).isTrue();
        }

        @Test
        @DisplayName("a Minecraft name change keeps the identity and the town")
        void renameKeepsIdentity() {
            final Resident joined = newcomer().joinTown(RIFTHOLM).orElseThrow();

            final Resident renamed = joined.renamedTo("Alder_Two");

            assertThat(renamed.id()).isEqualTo(ALDER);
            assertThat(renamed.town()).contains(RIFTHOLM);
            assertThat(renamed.lastKnownName()).isEqualTo("Alder_Two");
        }

        @Test
        @DisplayName("equality is identity, so a stale copy is still the same resident")
        void equalityIsIdentity() {
            assertThat(newcomer()).isEqualTo(newcomer().joinTown(RIFTHOLM).orElseThrow());
        }

        @Test
        @DisplayName("a blank name is refused at construction")
        void blankNameRefused() {
            assertThatThrownBy(() -> Resident.newcomer(ALDER, "  ", NOW))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("a nation's capital is always one of its towns")
    class NationMembership {

        private static final UUID BANK = UUID.randomUUID();

        private Nation valen() {
            return Nation.found(NationId.random(), name("Valen"), ALDER, RIFTHOLM, BANK, NOW);
        }

        @Test
        @DisplayName("a founded nation has its capital as its only town")
        void foundedNationHasOneTown() {
            final Nation nation = valen();

            assertThat(nation.towns()).containsExactly(RIFTHOLM);
            assertThat(nation.capital()).isEqualTo(RIFTHOLM);
            assertThat(nation.leader()).isEqualTo(ALDER);
        }

        @Test
        @DisplayName("restoring a nation whose capital is not a member fails loudly")
        void corruptRestoreIsRefused() {
            assertThatThrownBy(() -> Nation.restore(
                    NationId.random(), name("Valen"), ALDER, ASHFORD, BANK,
                    java.util.Set.of(RIFTHOLM), NOW))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("capital");
        }

        @Test
        @DisplayName("admitting a town adds it")
        void admitAddsTown() {
            final Nation nation = valen().admit(ASHFORD).orElseThrow();

            assertThat(nation.towns()).containsExactly(RIFTHOLM, ASHFORD);
        }

        @Test
        @DisplayName("admitting a member town twice is refused")
        void admitTwiceIsRefused() {
            assertThat(valen().admit(RIFTHOLM).denial())
                    .contains(ChangeDenial.TOWN_ALREADY_IN_THIS_NATION);
        }

        @Test
        @DisplayName("an ordinary member town may leave without dissolving the nation")
        void ordinaryTownMayLeave() {
            final Nation nation = valen().admit(ASHFORD).orElseThrow();

            final Outcome<Nation> outcome = nation.release(ASHFORD);

            assertThat(outcome.orElseThrow().towns()).containsExactly(RIFTHOLM);
            assertThat(outcome.events()).singleElement()
                    .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories
                            .type(DomainEvent.TownLeftNation.class))
                    .satisfies(event -> assertThat(event.dissolvesNation()).isFalse());
        }

        @Test
        @DisplayName("the capital must move before it can leave, while other towns remain")
        void capitalMustMoveFirst() {
            final Nation nation = valen().admit(ASHFORD).orElseThrow();

            assertThat(nation.release(RIFTHOLM).denial())
                    .contains(ChangeDenial.CAPITAL_MUST_MOVE_FIRST);
        }

        @Test
        @DisplayName("the last town may leave, dissolving the nation rather than stranding towns")
        void lastTownDissolvesTheNation() {
            final Nation nation = valen();

            assertThat(nation.wouldDissolveOnLeaving(RIFTHOLM)).isTrue();
            final Outcome<Nation> outcome = nation.release(RIFTHOLM);

            assertThat(outcome.wasApplied()).isTrue();
            assertThat(outcome.orElseThrow().towns()).isEmpty();
            assertThat(outcome.events()).singleElement()
                    .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories
                            .type(DomainEvent.TownLeftNation.class))
                    .satisfies(event -> assertThat(event.dissolvesNation()).isTrue());
        }

        @Test
        @DisplayName("a non-member town cannot leave")
        void nonMemberCannotLeave() {
            assertThat(valen().release(ASHFORD).denial())
                    .contains(ChangeDenial.TOWN_NOT_IN_THIS_NATION);
        }

        @Test
        @DisplayName("the capital moves to another member town")
        void capitalMovesToMember() {
            final Nation nation = valen().admit(ASHFORD).orElseThrow();

            final Outcome<Nation> outcome = nation.moveCapital(ASHFORD);

            assertThat(outcome.orElseThrow().capital()).isEqualTo(ASHFORD);
            assertThat(outcome.events()).singleElement()
                    .isEqualTo(new DomainEvent.CapitalMoved(nation.id(), RIFTHOLM, ASHFORD));
        }

        @Test
        @DisplayName("a town outside the nation may never become its capital")
        void outsiderCannotBecomeCapital() {
            assertThat(valen().moveCapital(ASHFORD).denial())
                    .contains(ChangeDenial.CAPITAL_MUST_BE_A_MEMBER_TOWN);
        }

        @Test
        @DisplayName("moving the capital to itself is refused")
        void movingCapitalToItselfIsRefused() {
            assertThat(valen().moveCapital(RIFTHOLM).denial())
                    .contains(ChangeDenial.ALREADY_THE_CAPITAL);
        }

        @Test
        @DisplayName("leadership passes to a member, keeping the id and bank account")
        void leadershipTransferKeepsIdentity() {
            final Nation before = valen();

            final Nation after = before.transferLeadership(BRIAR, true).orElseThrow();

            assertThat(after.leader()).isEqualTo(BRIAR);
            assertThat(after.id()).isEqualTo(before.id());
            assertThat(after.bankAccountId()).isEqualTo(before.bankAccountId());
        }

        @Test
        @DisplayName("a non-member may never lead the nation")
        void nonMemberMayNotLead() {
            assertThat(valen().transferLeadership(BRIAR, false).denial())
                    .contains(ChangeDenial.LEADER_MUST_BE_A_MEMBER);
        }

        @Test
        @DisplayName("renaming keeps the id and the bank account")
        void renameKeepsIdentity() {
            final Nation before = valen();

            final Nation after = before.renameTo(name("Korath")).orElseThrow();

            assertThat(after.name().display()).isEqualTo("Korath");
            assertThat(after.id()).isEqualTo(before.id());
            assertThat(after.bankAccountId()).isEqualTo(before.bankAccountId());
        }
    }
}
