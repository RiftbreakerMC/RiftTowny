package net.riftbreaker.rifttowny.domain.org;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MembershipRuleTest {

    private static final TownId RIFTHOLM = TownId.random();
    private static final TownId ASHFORD = TownId.random();
    private static final NationId VALEN = NationId.random();
    private static final NationId KORATH = NationId.random();
    private static final ResidentId MAYOR = ResidentId.of(java.util.UUID.randomUUID());
    private static final ResidentId CITIZEN = ResidentId.of(java.util.UUID.randomUUID());

    @Nested
    @DisplayName("a resident belongs to exactly one town")
    class TownMembership {

        @Test
        @DisplayName("someone with no town may join")
        void townlessMayJoin() {
            assertThat(MembershipRule.mayJoinTown(Optional.empty(), RIFTHOLM)).isEmpty();
        }

        @Test
        @DisplayName("someone in another town is refused, and told which case it is")
        void alreadyElsewhere() {
            assertThat(MembershipRule.mayJoinTown(Optional.of(ASHFORD), RIFTHOLM))
                    .contains(MembershipDenial.ALREADY_IN_ANOTHER_TOWN);
        }

        @Test
        @DisplayName("someone already in the target town gets a distinct denial, not a generic one")
        void alreadyHere() {
            assertThat(MembershipRule.mayJoinTown(Optional.of(RIFTHOLM), RIFTHOLM))
                    .contains(MembershipDenial.ALREADY_IN_THIS_TOWN);
        }

        @Test
        @DisplayName("an ordinary resident may leave")
        void ordinaryResidentMayLeave() {
            assertThat(MembershipRule.mayLeaveTown(Optional.of(RIFTHOLM), RIFTHOLM, 5, false)).isEmpty();
        }

        @Test
        @DisplayName("a non-member cannot leave a town they are not in")
        void nonMemberCannotLeave() {
            assertThat(MembershipRule.mayLeaveTown(Optional.of(ASHFORD), RIFTHOLM, 5, false))
                    .contains(MembershipDenial.NOT_A_RESIDENT_OF_THIS_TOWN);
            assertThat(MembershipRule.mayLeaveTown(Optional.empty(), RIFTHOLM, 5, false))
                    .contains(MembershipDenial.NOT_A_RESIDENT_OF_THIS_TOWN);
        }

        @Test
        @DisplayName("the mayor must transfer before leaving, so a town is never leaderless")
        void mayorMustTransferFirst() {
            assertThat(MembershipRule.mayLeaveTown(Optional.of(RIFTHOLM), RIFTHOLM, 5, true))
                    .contains(MembershipDenial.MAYOR_MUST_TRANSFER_FIRST);
        }

        @Test
        @DisplayName("the last resident is told to disband, not to transfer to nobody")
        void lastResidentIsToldToDisband() {
            assertThat(MembershipRule.mayLeaveTown(Optional.of(RIFTHOLM), RIFTHOLM, 1, true))
                    .contains(MembershipDenial.LAST_RESIDENT_MUST_DISBAND_INSTEAD);
        }

        @Test
        @DisplayName("a resident count below one is a programming error, not a denial")
        void impossibleResidentCountThrows() {
            assertThatThrownBy(() -> MembershipRule.mayLeaveTown(Optional.of(RIFTHOLM), RIFTHOLM, 0, false))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("leadership transfer")
    class Leadership {

        @Test
        @DisplayName("a member may take leadership")
        void memberMayLead() {
            assertThat(MembershipRule.mayTransferLeadership(true, Optional.of(MAYOR), CITIZEN)).isEmpty();
        }

        @Test
        @DisplayName("an outsider may never be made leader")
        void outsiderMayNotLead() {
            assertThat(MembershipRule.mayTransferLeadership(false, Optional.of(MAYOR), CITIZEN))
                    .contains(MembershipDenial.LEADER_MUST_BE_A_MEMBER);
        }

        @Test
        @DisplayName("transferring to the current leader is refused rather than being a silent no-op")
        void transferToSelfIsRefused() {
            assertThat(MembershipRule.mayTransferLeadership(true, Optional.of(MAYOR), MAYOR))
                    .contains(MembershipDenial.ALREADY_THE_LEADER);
        }

        @Test
        @DisplayName("a vacancy may be filled by any member")
        void vacancyMayBeFilled() {
            assertThat(MembershipRule.mayTransferLeadership(true, Optional.empty(), CITIZEN)).isEmpty();
        }
    }

    @Nested
    @DisplayName("a town belongs to at most one nation")
    class NationMembership {

        @Test
        @DisplayName("an unaligned town may join")
        void unalignedMayJoin() {
            assertThat(MembershipRule.mayTownJoinNation(Optional.empty(), VALEN)).isEmpty();
        }

        @Test
        @DisplayName("a town in another nation is refused")
        void alreadyAligned() {
            assertThat(MembershipRule.mayTownJoinNation(Optional.of(KORATH), VALEN))
                    .contains(MembershipDenial.TOWN_ALREADY_IN_ANOTHER_NATION);
        }

        @Test
        @DisplayName("an ordinary member town may leave")
        void memberTownMayLeave() {
            assertThat(MembershipRule.mayTownLeaveNation(Optional.of(VALEN), VALEN, 4, false)).isEmpty();
        }

        @Test
        @DisplayName("the capital must move before leaving, while other towns remain")
        void capitalMustMoveFirst() {
            assertThat(MembershipRule.mayTownLeaveNation(Optional.of(VALEN), VALEN, 4, true))
                    .contains(MembershipDenial.CAPITAL_MUST_MOVE_FIRST);
        }

        @Test
        @DisplayName("the capital of a one-town nation may leave, dissolving it")
        void lastTownMayLeaveAndDissolveTheNation() {
            assertThat(MembershipRule.mayTownLeaveNation(Optional.of(VALEN), VALEN, 1, true)).isEmpty();
        }

        @Test
        @DisplayName("a member town may become capital")
        void memberMayBecomeCapital() {
            assertThat(MembershipRule.mayBecomeCapital(Optional.of(VALEN), VALEN)).isEmpty();
        }

        @Test
        @DisplayName("a town outside the nation may never be its capital")
        void outsiderMayNotBecomeCapital() {
            assertThat(MembershipRule.mayBecomeCapital(Optional.of(KORATH), VALEN))
                    .contains(MembershipDenial.CAPITAL_MUST_BE_A_MEMBER_TOWN);
            assertThat(MembershipRule.mayBecomeCapital(Optional.empty(), VALEN))
                    .contains(MembershipDenial.CAPITAL_MUST_BE_A_MEMBER_TOWN);
        }
    }

    @Nested
    @DisplayName("external trust is not membership")
    class Trust {

        @Test
        @DisplayName("a trusted outsider gets nothing that membership gives")
        void trustGrantsNothing() {
            final MembershipRights rights = MembershipRule.rightsOf(false, true);

            assertThat(rights.member()).isFalse();
            assertThat(rights.mayVote()).isFalse();
            assertThat(rights.countsTowardResidentCount()).isFalse();
            assertThat(rights.liableForTax()).isFalse();
            assertThat(rights.mayHoldBankAuthority()).isFalse();
            assertThat(rights.carriesToNation()).isFalse();
        }

        @Test
        @DisplayName("a trusted outsider is indistinguishable from a visitor, rights-wise")
        void trustIsNotALesserMembership() {
            assertThat(MembershipRule.rightsOf(false, true))
                    .isEqualTo(MembershipRule.rightsOf(false, false));
        }

        @Test
        @DisplayName("a resident who is also trusted is still a full member")
        void residencyWinsOverTrust() {
            assertThat(MembershipRule.rightsOf(true, true)).isEqualTo(MembershipRights.forResident());
        }

        @Test
        @DisplayName("a resident has every right")
        void residentHasEveryRight() {
            final MembershipRights rights = MembershipRule.rightsOf(true, false);

            assertThat(rights.member()).isTrue();
            assertThat(rights.mayVote()).isTrue();
            assertThat(rights.countsTowardResidentCount()).isTrue();
            assertThat(rights.liableForTax()).isTrue();
            assertThat(rights.mayHoldBankAuthority()).isTrue();
            assertThat(rights.carriesToNation()).isTrue();
        }
    }

    @Nested
    @DisplayName("typed identity")
    class Identity {

        @Test
        @DisplayName("a town id and a nation id built from the same UUID are not equal")
        void idsAreNotInterchangeable() {
            final java.util.UUID shared = java.util.UUID.randomUUID();

            assertThat((Object) new TownId(shared)).isNotEqualTo(new NationId(shared));
            assertThat(new TownId(shared).scope()).isEqualTo(OrganisationScope.TOWN);
            assertThat(new NationId(shared).scope()).isEqualTo(OrganisationScope.NATION);
        }

        @Test
        @DisplayName("scope round-trips through its stored form")
        void scopeRoundTrips() {
            for (final OrganisationScope scope : OrganisationScope.values()) {
                assertThat(OrganisationScope.fromStorage(scope.storageValue())).isEqualTo(scope);
            }
        }

        @Test
        @DisplayName("an unknown stored scope fails loudly rather than defaulting to TOWN")
        void unknownScopeThrows() {
            assertThatThrownBy(() -> OrganisationScope.fromStorage("FEDERATION"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
