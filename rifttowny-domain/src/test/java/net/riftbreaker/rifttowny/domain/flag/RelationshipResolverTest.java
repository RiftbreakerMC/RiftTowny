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
                true, RIFTHOLM, VALEN, RIFTHOLM, VALEN, false, true, false);

        assertThat(RelationshipResolver.resolve(view)).isEqualTo(Relationship.RESIDENT);
    }

    @Test
    @DisplayName("an ally sits between nation and trusted")
    void allyIsBetweenNationAndTrusted() {
        final TerritoryView view = new TerritoryView(
                true, RIFTHOLM, VALEN, ASHFORD, KORATH, false, false, true);

        assertThat(RelationshipResolver.resolve(view)).isEqualTo(Relationship.ALLY);
    }

    @Test
    @DisplayName("nation membership beats an alliance, since it is the closer tie")
    void nationBeatsAlly() {
        final TerritoryView view = new TerritoryView(
                true, RIFTHOLM, VALEN, ASHFORD, VALEN, false, false, true);

        assertThat(RelationshipResolver.resolve(view)).isEqualTo(Relationship.NATION);
    }

    @Test
    @DisplayName("two townless players do not become nation-mates through null matching")
    void nullAffiliationsDoNotMatch() {
        final TerritoryView view = new TerritoryView(
                true, RIFTHOLM, null, null, null, false, false, false);

        assertThat(RelationshipResolver.resolve(view))
                .as("a null nation on both sides must not read as the same nation")
                .isEqualTo(Relationship.VISITOR);
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
