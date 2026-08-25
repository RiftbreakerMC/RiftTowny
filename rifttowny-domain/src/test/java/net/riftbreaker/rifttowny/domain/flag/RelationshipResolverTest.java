package net.riftbreaker.rifttowny.domain.flag;

import net.riftbreaker.rifttowny.domain.flag.RelationshipResolver.TerritoryView;
import net.riftbreaker.rifttowny.domain.org.NationId;
import net.riftbreaker.rifttowny.domain.org.TownId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RelationshipResolverTest {

    private static final TownId RIFTHOLM = TownId.random();
    private static final TownId ASHFORD = TownId.random();
    private static final NationId VALEN = NationId.random();
    private static final NationId KORATH = NationId.random();

    @Test
    @DisplayName("unclaimed land is wilderness whoever is standing on it")
    void unclaimedIsWilderness() {
        assertThat(RelationshipResolver.resolve(TerritoryView.wilderness()))
                .isEqualTo(Relationship.WILDERNESS);
    }

    @Test
    @DisplayName("a member of the owning town is TOWN")
    void ownTownIsTown() {
        assertThat(RelationshipResolver.resolve(
                TerritoryView.claim(RIFTHOLM, VALEN, RIFTHOLM, VALEN, false)))
                .isEqualTo(Relationship.TOWN);
    }

    @Test
    @DisplayName("a member of another town in the same nation is NATION")
    void sameNationIsNation() {
        assertThat(RelationshipResolver.resolve(
                TerritoryView.claim(RIFTHOLM, VALEN, ASHFORD, VALEN, false)))
                .isEqualTo(Relationship.NATION);
    }

    @Test
    @DisplayName("someone from an unrelated town is a visitor")
    void strangerIsVisitor() {
        assertThat(RelationshipResolver.resolve(
                TerritoryView.claim(RIFTHOLM, VALEN, ASHFORD, KORATH, false)))
                .isEqualTo(Relationship.VISITOR);
    }

    @Test
    @DisplayName("someone with no town at all is a visitor")
    void townlessIsVisitor() {
        assertThat(RelationshipResolver.resolve(
                TerritoryView.claim(RIFTHOLM, null, null, null, false)))
                .isEqualTo(Relationship.VISITOR);
    }

    @Test
    @DisplayName("a trusted outsider outranks a visitor but nothing more")
    void trustSitsAboveVisitor() {
        assertThat(RelationshipResolver.resolve(
                TerritoryView.claim(RIFTHOLM, VALEN, ASHFORD, KORATH, true)))
                .isEqualTo(Relationship.TRUSTED);
    }

    @Test
    @DisplayName("a member who is also on the trust list is still a member")
    void trustNeverDemotesAMember() {
        assertThat(RelationshipResolver.resolve(
                TerritoryView.claim(RIFTHOLM, VALEN, RIFTHOLM, VALEN, true)))
                .as("otherwise adding somebody to the trust list would take rights away")
                .isEqualTo(Relationship.TOWN);
    }

    @Test
    @DisplayName("a plot owner outranks an ordinary member of the same town")
    void plotOwnerIsResident() {
        final TerritoryView view = new TerritoryView(
                true, RIFTHOLM, VALEN, RIFTHOLM, VALEN, false, true, false, false);

        assertThat(RelationshipResolver.resolve(view)).isEqualTo(Relationship.RESIDENT);
    }

    @Test
    @DisplayName("an ally sits between nation and trusted")
    void allyIsBetweenNationAndTrusted() {
        final TerritoryView view = new TerritoryView(
                true, RIFTHOLM, VALEN, ASHFORD, KORATH, false, false, false, true);

        assertThat(RelationshipResolver.resolve(view)).isEqualTo(Relationship.ALLY);
    }

    @Test
    @DisplayName("nation membership beats an alliance, since it is the closer tie")
    void nationBeatsAlly() {
        final TerritoryView view = new TerritoryView(
                true, RIFTHOLM, VALEN, ASHFORD, VALEN, false, false, false, true);

        assertThat(RelationshipResolver.resolve(view)).isEqualTo(Relationship.NATION);
    }

    @Test
    @DisplayName("two townless players do not become nation-mates through null matching")
    void nullAffiliationsDoNotMatch() {
        final TerritoryView view = new TerritoryView(
                true, RIFTHOLM, null, null, null, false, false, false, false);

        assertThat(RelationshipResolver.resolve(view))
                .as("a null nation on both sides must not read as the same nation")
                .isEqualTo(Relationship.VISITOR);
    }


    /** A view with the outlaw flag set, since the convenience factories do not take it. */
    private static TerritoryView outlawedIn(
            final TownId owner, final NationId ownerNation,
            final TownId actorTown, final NationId actorNation, final boolean trusted) {
        return new TerritoryView(
                true, owner, ownerNation, actorTown, actorNation, trusted, false, true, false);
    }

    @Test
    @DisplayName("an outlawed stranger is an outlaw, not a visitor")
    void outlawedStranger() {
        assertThat(RelationshipResolver.resolve(outlawedIn(RIFTHOLM, null, null, null, false)))
                .isEqualTo(Relationship.OUTLAW);
    }

    @Test
    @DisplayName("outlawry beats every rung an outsider could otherwise claim")
    void outlawryBeatsOutsiderRungs() {
        // The case the placement exists for. An outlaw who is in the owning town's own nation, or
        // in an allied one, or on its trust list, would otherwise keep that rung and walk in - and
        // an ally who cannot be barred is an outlawry that does not work.
        assertThat(RelationshipResolver.resolve(outlawedIn(RIFTHOLM, VALEN, ASHFORD, VALEN, false)))
                .as("same nation")
                .isEqualTo(Relationship.OUTLAW);
        assertThat(RelationshipResolver.resolve(outlawedIn(RIFTHOLM, VALEN, ASHFORD, KORATH, true)))
                .as("on the trust list")
                .isEqualTo(Relationship.OUTLAW);

        final TerritoryView alliedOutlaw = new TerritoryView(
                true, RIFTHOLM, VALEN, ASHFORD, KORATH, false, false, true, true);
        assertThat(RelationshipResolver.resolve(alliedOutlaw))
                .as("allied nation")
                .isEqualTo(Relationship.OUTLAW);
    }

    @Test
    @DisplayName("but a member of the town is not stripped by it")
    void membershipSupersedes() {
        // The other half of the placement. Membership is the same town's later and more specific
        // decision, so it wins - and an admitted player left with no rights would be a state
        // nothing in the join path would notice.
        assertThat(RelationshipResolver.resolve(outlawedIn(RIFTHOLM, VALEN, RIFTHOLM, VALEN, false)))
                .as("a member of the owning town")
                .isEqualTo(Relationship.TOWN);

        final TerritoryView plotHolder = new TerritoryView(
                true, RIFTHOLM, VALEN, RIFTHOLM, VALEN, false, true, true, false);
        assertThat(RelationshipResolver.resolve(plotHolder))
                .as("holding their own plot")
                .isEqualTo(Relationship.RESIDENT);
    }

    @Test
    @DisplayName("an outlaw ranks under everyone, so the shipped defaults already refuse them")
    void outlawIsTheLowestRung() {
        // No default had to change for this rung: the defaults ask isMember and isAtLeast, and an
        // outlaw fails both. What that buys is that adding the constant cannot have granted
        // anything anywhere.
        assertThat(Relationship.OUTLAW.isMember()).isFalse();
        assertThat(Relationship.OUTLAW.isAtLeast(Relationship.VISITOR)).isFalse();
        assertThat(Relationship.OUTLAW.isAtLeast(Relationship.WILDERNESS)).isFalse();
        assertThat(Relationship.VISITOR.isAtLeast(Relationship.OUTLAW)).isTrue();

        for (final ProtectionFlag flag : ProtectionFlag.values()) {
            if (flag.allowedByDefault(Relationship.OUTLAW, LandState.CLAIMED)) {
                // Only the flags that are on for everybody, and none of them moves a block.
                assertThat(flag)
                        .as("%s is allowed to an outlaw by default", flag)
                        .isIn(ProtectionFlag.SHOP_USE, ProtectionFlag.REDSTONE,
                                ProtectionFlag.MOB_SPAWNING);
            }
        }
    }

    @Test
    @DisplayName("OUTLAW is declared first, which is what the monotonicity check reads")
    void declaredFirst() {
        // firstNonMonotonic walks values() in declaration order rather than by rank, so a rung
        // ranked lowest but declared elsewhere would be compared in the wrong place.
        assertThat(Relationship.values()[0]).isEqualTo(Relationship.OUTLAW);
    }
    @Test
    @DisplayName("the ladder is ordered, so a higher rung really does outrank a lower one")
    void ladderIsOrdered() {
        assertThat(Relationship.RESIDENT.isAtLeast(Relationship.TOWN)).isTrue();
        assertThat(Relationship.TOWN.isAtLeast(Relationship.NATION)).isTrue();
        assertThat(Relationship.VISITOR.isAtLeast(Relationship.TRUSTED)).isFalse();
        assertThat(Relationship.TOWN.isMember()).isTrue();
        assertThat(Relationship.TRUSTED.isMember())
                .as("trust is never membership")
                .isFalse();
    }
}
