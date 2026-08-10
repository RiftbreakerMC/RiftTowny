package net.riftbreaker.rifttowny.domain.org;

import java.util.Objects;
import java.util.Optional;

/**
 * The membership invariants, as pure decisions.
 *
 * <p>Every method answers "may this happen", never "make this happen". Keeping the decision separate
 * from the mutation is what lets the awkward cases — the last resident, the mayor leaving, the
 * capital leaving — be exhausted in tests rather than discovered in production.</p>
 *
 * <p>The service layer calls these <em>inside</em> the transaction that performs the change, not
 * before it. Checking outside would leave a window in which two servers both see a permitted
 * change and both apply it.</p>
 */
public final class MembershipRule {

    private MembershipRule() {
    }

    /**
     * May a resident join a town?
     *
     * @param currentTown the resident's town, or empty if they have none
     * @param target the town they wish to join
     */
    public static Optional<MembershipDenial> mayJoinTown(
            final Optional<TownId> currentTown, final TownId target) {
        Objects.requireNonNull(currentTown, "currentTown");
        Objects.requireNonNull(target, "target");

        if (currentTown.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(currentTown.get().equals(target)
                ? MembershipDenial.ALREADY_IN_THIS_TOWN
                : MembershipDenial.ALREADY_IN_ANOTHER_TOWN);
    }

    /**
     * May a resident leave a town?
     *
     * @param currentTown the resident's town, or empty
     * @param target the town they wish to leave
     * @param residentCount how many residents the town has, including this one
     * @param isMayor whether this resident currently leads the town
     */
    public static Optional<MembershipDenial> mayLeaveTown(
            final Optional<TownId> currentTown,
            final TownId target,
            final int residentCount,
            final boolean isMayor
    ) {
        Objects.requireNonNull(currentTown, "currentTown");
        Objects.requireNonNull(target, "target");
        if (residentCount < 1) {
            throw new IllegalArgumentException(
                    "a town being left must have at least one resident, got " + residentCount);
        }

        if (currentTown.isEmpty() || !currentTown.get().equals(target)) {
            return Optional.of(MembershipDenial.NOT_A_RESIDENT_OF_THIS_TOWN);
        }
        if (residentCount == 1) {
            // Checked before the mayor rule: the sole resident is necessarily the mayor, and
            // "transfer to whom?" is not a useful thing to tell them.
            return Optional.of(MembershipDenial.LAST_RESIDENT_MUST_DISBAND_INSTEAD);
        }
        if (isMayor) {
            return Optional.of(MembershipDenial.MAYOR_MUST_TRANSFER_FIRST);
        }
        return Optional.empty();
    }

    /**
     * May leadership of an organisation pass to this candidate?
     *
     * <p>Used for both mayoralty and kingship. For a nation the "member" test is whether the
     * candidate is a resident of one of its towns.</p>
     *
     * @param candidateIsMember whether the candidate already belongs to the organisation
     * @param currentLeader the present leader, or empty during a vacancy
     * @param candidate the proposed leader
     */
    public static Optional<MembershipDenial> mayTransferLeadership(
            final boolean candidateIsMember,
            final Optional<ResidentId> currentLeader,
            final ResidentId candidate
    ) {
        Objects.requireNonNull(currentLeader, "currentLeader");
        Objects.requireNonNull(candidate, "candidate");

        if (!candidateIsMember) {
            return Optional.of(MembershipDenial.LEADER_MUST_BE_A_MEMBER);
        }
        if (currentLeader.filter(candidate::equals).isPresent()) {
            return Optional.of(MembershipDenial.ALREADY_THE_LEADER);
        }
        return Optional.empty();
    }

    /**
     * May a town join a nation?
     *
     * @param currentNation the town's nation, or empty
     * @param target the nation it wishes to join
     */
    public static Optional<MembershipDenial> mayTownJoinNation(
            final Optional<NationId> currentNation, final NationId target) {
        Objects.requireNonNull(currentNation, "currentNation");
        Objects.requireNonNull(target, "target");

        if (currentNation.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(currentNation.get().equals(target)
                ? MembershipDenial.TOWN_ALREADY_IN_THIS_NATION
                : MembershipDenial.TOWN_ALREADY_IN_ANOTHER_NATION);
    }

    /**
     * May a town leave a nation?
     *
     * @param currentNation the town's nation, or empty
     * @param target the nation it wishes to leave
     * @param nationTownCount how many towns the nation has, including this one
     * @param isCapital whether this town is the nation's capital
     */
    public static Optional<MembershipDenial> mayTownLeaveNation(
            final Optional<NationId> currentNation,
            final NationId target,
            final int nationTownCount,
            final boolean isCapital
    ) {
        Objects.requireNonNull(currentNation, "currentNation");
        Objects.requireNonNull(target, "target");
        if (nationTownCount < 1) {
            throw new IllegalArgumentException(
                    "a nation being left must have at least one town, got " + nationTownCount);
        }

        if (currentNation.isEmpty() || !currentNation.get().equals(target)) {
            return Optional.of(MembershipDenial.TOWN_NOT_IN_THIS_NATION);
        }
        if (isCapital && nationTownCount > 1) {
            return Optional.of(MembershipDenial.CAPITAL_MUST_MOVE_FIRST);
        }
        // The capital of a one-town nation may leave: doing so dissolves the nation, which is a
        // legitimate and reversible act, unlike stranding towns under a nation with no capital.
        return Optional.empty();
    }

    /**
     * May a town become a nation's capital?
     *
     * @param townNation the town's nation, or empty
     * @param nation the nation whose capital it would become
     */
    public static Optional<MembershipDenial> mayBecomeCapital(
            final Optional<NationId> townNation, final NationId nation) {
        Objects.requireNonNull(townNation, "townNation");
        Objects.requireNonNull(nation, "nation");

        return townNation.filter(nation::equals).isPresent()
                ? Optional.empty()
                : Optional.of(MembershipDenial.CAPITAL_MUST_BE_A_MEMBER_TOWN);
    }

    /**
     * What someone is entitled to, given how they relate to a town.
     *
     * <p>Trust is checked last and grants nothing, so no combination of arguments can produce
     * membership rights for a non-resident.</p>
     */
    public static MembershipRights rightsOf(final boolean isResident, final boolean isTrusted) {
        if (isResident) {
            return MembershipRights.forResident();
        }
        return isTrusted ? MembershipRights.forTrustedOutsider() : MembershipRights.forVisitor();
    }
}
