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

class TownTest {

    private static final Instant NOW = Instant.parse("2026-08-10T12:00:00Z");
    private static final UUID BANK = UUID.randomUUID();

    private static final ResidentId MAYOR = ResidentId.of(UUID.randomUUID());
    private static final ResidentId CITIZEN = ResidentId.of(UUID.randomUUID());
    private static final ResidentId OUTSIDER = ResidentId.of(UUID.randomUUID());

    private static OrganisationName name(final String raw) {
        return NamePolicy.defaults().check(raw).accepted().orElseThrow();
    }

    private static Town riftholm() {
        return Town.found(TownId.random(), name("Riftholm"), MAYOR, BANK, NOW);
    }

    private static Town riftholmWith(final ResidentId... extra) {
        Town town = riftholm();
        for (final ResidentId resident : extra) {
            town = town.admit(resident).orElseThrow();
        }
        return town;
    }

    @Nested
    @DisplayName("founding")
    class Founding {

        @Test
        @DisplayName("a founded town has its founder as sole resident and mayor")
        void founderIsMayorAndResident() {
            final Town town = riftholm();

            assertThat(town.mayor()).isEqualTo(MAYOR);
            assertThat(town.residents()).containsExactly(MAYOR);
            assertThat(town.nation()).isEmpty();
            assertThat(town.bankAccountId()).isEqualTo(BANK);
        }

        @Test
        @DisplayName("restoring a town whose mayor is not a resident fails loudly")
        void corruptRestoreIsRefused() {
            assertThatThrownBy(() -> Town.restore(
                    TownId.random(), name("Riftholm"), MAYOR, null, BANK,
                    java.util.Set.of(CITIZEN), java.util.Set.of(), NOW))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("mayor");
        }
    }

    @Nested
    @DisplayName("membership")
    class Membership {

        @Test
        @DisplayName("admitting adds the resident and says so")
        void admitAddsAndEmits() {
            final Outcome<Town> outcome = riftholm().admit(CITIZEN);

            assertThat(outcome.wasApplied()).isTrue();
            assertThat(outcome.orElseThrow().residents()).containsExactly(MAYOR, CITIZEN);
            assertThat(outcome.events())
                    .singleElement()
                    .isEqualTo(new DomainEvent.ResidentAdmitted(outcome.orElseThrow().id(), CITIZEN));
        }

        @Test
        @DisplayName("the original town is unchanged, so a denied write cannot leak")
        void aggregatesAreImmutable() {
            final Town before = riftholm();

            before.admit(CITIZEN);

            assertThat(before.residents()).containsExactly(MAYOR);
        }

        @Test
        @DisplayName("admitting someone already here is refused, not silently repeated")
        void admitTwiceIsRefused() {
            assertThat(riftholmWith(CITIZEN).admit(CITIZEN).denial())
                    .contains(ChangeDenial.ALREADY_IN_THIS_TOWN);
        }

        @Test
        @DisplayName("an ordinary resident may be released")
        void ordinaryResidentMayLeave() {
            final Outcome<Town> outcome = riftholmWith(CITIZEN).release(CITIZEN, true);

            assertThat(outcome.orElseThrow().residents()).containsExactly(MAYOR);
            assertThat(outcome.events()).singleElement()
                    .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories
                            .type(DomainEvent.ResidentReleased.class))
                    .satisfies(event -> assertThat(event.voluntary()).isTrue());
        }

        @Test
        @DisplayName("a kick is recorded as involuntary, so an audit can tell them apart")
        void kickIsRecordedAsInvoluntary() {
            final Outcome<Town> outcome = riftholmWith(CITIZEN).release(CITIZEN, false);

            assertThat(outcome.events()).singleElement()
                    .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories
                            .type(DomainEvent.ResidentReleased.class))
                    .satisfies(event -> assertThat(event.voluntary()).isFalse());
        }

        @Test
        @DisplayName("releasing a non-member is refused")
        void nonMemberCannotBeReleased() {
            assertThat(riftholm().release(OUTSIDER, true).denial())
                    .contains(ChangeDenial.NOT_A_RESIDENT_OF_THIS_TOWN);
        }

        @Test
        @DisplayName("the last resident is told to disband rather than to transfer to nobody")
        void lastResidentIsToldToDisband() {
            assertThat(riftholm().release(MAYOR, true).denial())
                    .contains(ChangeDenial.LAST_RESIDENT_MUST_DISBAND_INSTEAD);
        }

        @Test
        @DisplayName("the mayor must transfer first, so the town is never leaderless")
        void mayorMustTransferFirst() {
            assertThat(riftholmWith(CITIZEN).release(MAYOR, true).denial())
                    .contains(ChangeDenial.MAYOR_MUST_TRANSFER_FIRST);
        }

        @Test
        @DisplayName("a mayor cannot be kicked out either")
        void mayorCannotBeKicked() {
            assertThat(riftholmWith(CITIZEN).release(MAYOR, false).denial())
                    .contains(ChangeDenial.MAYOR_MUST_TRANSFER_FIRST);
        }
    }

    @Nested
    @DisplayName("leadership")
    class Leadership {

        @Test
        @DisplayName("the mayoralty passes to a resident, keeping the id and bank account")
        void transferKeepsIdentityAndAccount() {
            final Town before = riftholmWith(CITIZEN);

            final Town after = before.transferLeadership(CITIZEN).orElseThrow();

            assertThat(after.mayor()).isEqualTo(CITIZEN);
            assertThat(after.id()).isEqualTo(before.id());
            assertThat(after.bankAccountId()).isEqualTo(before.bankAccountId());
        }

        @Test
        @DisplayName("an outsider may never be made mayor")
        void outsiderMayNotLead() {
            assertThat(riftholm().transferLeadership(OUTSIDER).denial())
                    .contains(ChangeDenial.LEADER_MUST_BE_A_MEMBER);
        }

        @Test
        @DisplayName("transferring to the sitting mayor is refused rather than a silent no-op")
        void transferToSelfIsRefused() {
            assertThat(riftholm().transferLeadership(MAYOR).denial())
                    .contains(ChangeDenial.ALREADY_THE_LEADER);
        }
    }

    @Nested
    @DisplayName("renaming")
    class Renaming {

        @Test
        @DisplayName("renaming keeps the id and the bank account")
        void renameKeepsIdentityAndAccount() {
            final Town before = riftholm();

            final Town after = before.renameTo(name("Ashford")).orElseThrow();

            assertThat(after.name().display()).isEqualTo("Ashford");
            assertThat(after.id()).isEqualTo(before.id());
            assertThat(after.bankAccountId()).isEqualTo(before.bankAccountId());
        }

        @Test
        @DisplayName("recapitalising is a real change, because the display name is what players see")
        void recapitalisationIsAllowed() {
            assertThat(riftholm().renameTo(name("RIFTHOLM")).wasApplied()).isTrue();
        }

        @Test
        @DisplayName("renaming to the identical name is refused")
        void identicalRenameIsRefused() {
            assertThat(riftholm().renameTo(name("Riftholm")).denial())
                    .contains(ChangeDenial.NAME_UNCHANGED);
        }
    }

    @Nested
    @DisplayName("nation membership")
    class NationMembership {

        private static final NationId VALEN = NationId.random();
        private static final NationId KORATH = NationId.random();

        @Test
        @DisplayName("an unaligned town may join a nation")
        void unalignedMayJoin() {
            assertThat(riftholm().joinNation(VALEN).orElseThrow().nation()).contains(VALEN);
        }

        @Test
        @DisplayName("a town already in another nation is refused")
        void alreadyAligned() {
            final Town aligned = riftholm().joinNation(KORATH).orElseThrow();

            assertThat(aligned.joinNation(VALEN).denial())
                    .contains(ChangeDenial.TOWN_ALREADY_IN_ANOTHER_NATION);
        }

        @Test
        @DisplayName("leaving carries whether the nation dissolved, so a feed needs no second query")
        void leavingCarriesDissolution() {
            final Town aligned = riftholm().joinNation(VALEN).orElseThrow();

            final Outcome<Town> outcome = aligned.leaveNation(true);

            assertThat(outcome.orElseThrow().nation()).isEmpty();
            assertThat(outcome.events()).singleElement()
                    .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories
                            .type(DomainEvent.TownLeftNation.class))
                    .satisfies(event -> assertThat(event.dissolvesNation()).isTrue());
        }

        @Test
        @DisplayName("an unaligned town cannot leave a nation")
        void unalignedCannotLeave() {
            assertThat(riftholm().leaveNation(false).denial())
                    .contains(ChangeDenial.TOWN_NOT_IN_THIS_NATION);
        }
    }

    @Nested
    @DisplayName("trust is not membership")
    class Trust {

        @Test
        @DisplayName("a trusted outsider gets nothing that membership gives")
        void trustGrantsNothing() {
            final Town town = riftholm().trust(OUTSIDER).orElseThrow();

            final MembershipRights rights = town.rightsOf(OUTSIDER);
            assertThat(rights.member()).isFalse();
            assertThat(rights.mayVote()).isFalse();
            assertThat(rights.countsTowardResidentCount()).isFalse();
            assertThat(rights.liableForTax()).isFalse();
            assertThat(rights.mayHoldBankAuthority()).isFalse();
            assertThat(rights.carriesToNation()).isFalse();
        }

        @Test
        @DisplayName("a trusted outsider is rights-identical to a stranger")
        void trustIsNotALesserMembership() {
            final Town town = riftholm().trust(OUTSIDER).orElseThrow();

            assertThat(town.rightsOf(OUTSIDER))
                    .isEqualTo(town.rightsOf(ResidentId.of(UUID.randomUUID())));
        }

        @Test
        @DisplayName("trusting a resident is refused, so there is only one path to member rights")
        void residentsCannotBeTrusted() {
            assertThat(riftholm().trust(MAYOR).denial())
                    .contains(ChangeDenial.CANNOT_TRUST_A_RESIDENT);
        }

        @Test
        @DisplayName("admitting a trusted outsider clears their trust, leaving no overlap")
        void admissionClearsTrust() {
            final Town trusted = riftholm().trust(OUTSIDER).orElseThrow();

            final Town admitted = trusted.admit(OUTSIDER).orElseThrow();

            assertThat(admitted.trustedOutsiders()).isEmpty();
            assertThat(admitted.rightsOf(OUTSIDER)).isEqualTo(MembershipRights.forResident());
        }

        @Test
        @DisplayName("trust can be revoked, and only once")
        void trustIsRevocable() {
            final Town trusted = riftholm().trust(OUTSIDER).orElseThrow();

            final Town revoked = trusted.untrust(OUTSIDER).orElseThrow();

            assertThat(revoked.trustedOutsiders()).isEmpty();
            assertThat(revoked.untrust(OUTSIDER).denial()).contains(ChangeDenial.NOT_TRUSTED);
        }

        @Test
        @DisplayName("trusting twice is refused")
        void trustTwiceIsRefused() {
            final Town trusted = riftholm().trust(OUTSIDER).orElseThrow();

            assertThat(trusted.trust(OUTSIDER).denial()).contains(ChangeDenial.ALREADY_TRUSTED);
        }
    }

    @Nested
    @DisplayName("outcome composition")
    class Composition {

        @Test
        @DisplayName("chained changes accumulate their events")
        void chainingAccumulatesEvents() {
            final Outcome<Town> outcome = riftholm()
                    .admit(CITIZEN)
                    .then(town -> town.transferLeadership(CITIZEN));

            assertThat(outcome.wasApplied()).isTrue();
            assertThat(outcome.orElseThrow().mayor()).isEqualTo(CITIZEN);
            assertThat(outcome.events()).hasSize(2);
        }

        @Test
        @DisplayName("a denial short-circuits the chain, so nothing partial can be persisted")
        void denialShortCircuits() {
            final Outcome<Town> outcome = riftholm()
                    .admit(MAYOR)
                    .then(town -> town.transferLeadership(CITIZEN));

            assertThat(outcome.denial()).contains(ChangeDenial.ALREADY_IN_THIS_TOWN);
            assertThat(outcome.events()).isEmpty();
            assertThat(outcome.value()).isEmpty();
        }

        @Test
        @DisplayName("orElseThrow on a denial names the reason rather than throwing an empty error")
        void orElseThrowNamesTheReason() {
            assertThatThrownBy(() -> riftholm().release(OUTSIDER, true).orElseThrow())
                    .hasMessageContaining("NOT_A_RESIDENT_OF_THIS_TOWN");
        }
    }
}
