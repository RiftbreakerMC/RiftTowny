package net.riftbreaker.rifttowny.domain.org;

/**
 * What a person is actually entitled to in a town, by virtue of how they relate to it.
 *
 * <p>This type exists to make one specification rule impossible to get wrong by accident:
 * <em>external trust never grants membership, voting rights, taxes, bank authority, or nation
 * membership.</em> Written as scattered {@code if (isTrusted || isResident)} checks that rule
 * survives exactly until somebody adds a convenience method. Written as a value with three named
 * factories, granting a trusted outsider a vote requires deliberately editing
 * {@link #forTrustedOutsider()}, which a reviewer will notice.</p>
 *
 * @param member counted as belonging to the town
 * @param mayVote may cast a ballot in town elections
 * @param countsTowardResidentCount included in resident counts, claim allowances and rankings
 * @param liableForTax charged the town's resident tax
 * @param mayHoldBankAuthority eligible to be granted withdrawal or payment authority
 * @param carriesToNation membership flows up to the town's nation
 */
public record MembershipRights(
        boolean member,
        boolean mayVote,
        boolean countsTowardResidentCount,
        boolean liableForTax,
        boolean mayHoldBankAuthority,
        boolean carriesToNation
) {

    /** A resident of the town. The only relationship that is membership. */
    public static MembershipRights forResident() {
        return new MembershipRights(true, true, true, true, true, true);
    }

    /**
     * An outsider the town has trusted.
     *
     * <p>Every field is false. Trust grants specific permission flags on specific territory; it is
     * not a lesser form of membership, and nothing here may ever become true.</p>
     */
    public static MembershipRights forTrustedOutsider() {
        return new MembershipRights(false, false, false, false, false, false);
    }

    /** Anyone else. */
    public static MembershipRights forVisitor() {
        return new MembershipRights(false, false, false, false, false, false);
    }
}
