package net.riftbreaker.rifttowny.domain.chat;

import net.riftbreaker.rifttowny.domain.civic.CivicCache;
import net.riftbreaker.rifttowny.domain.civic.CivicFixture;
import net.riftbreaker.rifttowny.domain.civic.NationCache;
import net.riftbreaker.rifttowny.domain.org.Nation;
import net.riftbreaker.rifttowny.domain.org.NationId;
import net.riftbreaker.rifttowny.domain.org.ResidentId;
import net.riftbreaker.rifttowny.domain.org.Town;
import net.riftbreaker.rifttowny.domain.org.TownId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who hears a channel.
 *
 * <p>The half of chat RiftTowny owns, and the half with a privacy consequence: everything here
 * decides which players receive somebody's words. The failure that matters is not a missing
 * message — it is a message reaching one person more than it should.</p>
 */
class ChannelAudienceTest {

    private final CivicCache towns = CivicCache.empty();
    private final NationCache nations = NationCache.empty();
    private ChannelAudience audiences;

    private ResidentId mayor;
    private ResidentId citizen;
    private ResidentId stranger;
    private Town ashford;

    @BeforeEach
    void setUp() {
        audiences = new ChannelAudience(towns, nations);
        mayor = CivicFixture.resident();
        citizen = CivicFixture.resident();
        stranger = CivicFixture.resident();

        ashford = CivicFixture.town("Ashford", mayor, citizen);
        towns.remember(CivicFixture.facts(ashford));
    }

    private Nation valenWith(final Town... members) {
        final NationId id = NationId.random();
        final Set<TownId> ids = new java.util.LinkedHashSet<>();
        for (final Town member : members) {
            ids.add(member.id());
            towns.remember(CivicFixture.facts(member.joinNation(id).orElseThrow()));
        }
        final Nation valen = Nation.restore(id, CivicFixture.name("Valen"), mayor,
                members[0].id(), UUID.randomUUID(), ids, CivicFixture.NOW);
        nations.remember(valen);
        return valen;
    }

    @Nested
    @DisplayName("town chat")
    class TownChat {

        @Test
        @DisplayName("reaches every resident and nobody else")
        void reachesTheTownOnly() {
            final ChannelAudience.Audience audience =
                    audiences.forSpeaker(mayor, ChatChannel.TOWN).orElseThrow();

            assertThat(audience.members()).containsExactlyInAnyOrder(mayor, citizen);
            assertThat(audience.includes(stranger))
                    .as("a message reaching one person more than it should is the failure here")
                    .isFalse();
            assertThat(audience.label()).isEqualTo("Ashford");
            assertThat(audience.channel()).isEqualTo(ChatChannel.TOWN);
        }

        @Test
        @DisplayName("somebody in no town has no town channel")
        void townlessPlayersHaveNoChannel() {
            // Empty rather than an audience of one: a channel that accepted the message and
            // delivered it to nobody would let a player believe they had been heard.
            assertThat(audiences.forSpeaker(stranger, ChatChannel.TOWN)).isEmpty();
        }

        @Test
        @DisplayName("a resident who leaves stops hearing it")
        void leavingRemovesThem() {
            towns.remember(CivicFixture.facts(ashford.release(citizen, true).orElseThrow()));

            assertThat(audiences.forSpeaker(mayor, ChatChannel.TOWN).orElseThrow().members())
                    .containsExactly(mayor);
        }

        @Test
        @DisplayName("a trusted outsider is not a resident and does not hear it")
        void trustIsNotMembership() {
            // Trust grants narrow permissions on land. It is not membership, and reading it as
            // membership here would put an outsider inside the town's private conversation.
            towns.remember(CivicFixture.facts(ashford.trust(stranger).orElseThrow()));

            assertThat(audiences.forSpeaker(mayor, ChatChannel.TOWN).orElseThrow().includes(stranger))
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("nation chat")
    class NationChat {

        @Test
        @DisplayName("reaches every resident of every member town")
        void reachesTheWholeNation() {
            final ResidentId other = CivicFixture.resident();
            final Town highholm = CivicFixture.town("Highholm", other);
            valenWith(ashford, highholm);

            final ChannelAudience.Audience audience =
                    audiences.forSpeaker(mayor, ChatChannel.NATION).orElseThrow();

            assertThat(audience.members()).containsExactlyInAnyOrder(mayor, citizen, other);
            assertThat(audience.label()).isEqualTo("Valen");
        }

        @Test
        @DisplayName("a town in no nation has no nation channel")
        void nationlessTownsHaveNoChannel() {
            assertThat(audiences.forSpeaker(mayor, ChatChannel.NATION)).isEmpty();
        }

        @Test
        @DisplayName("a member town the cache cannot describe costs only its own residents")
        void unknownMemberTownsAreSkipped() {
            // A missing town should cost its residents the message, not cost everybody else theirs.
            final NationId id = NationId.random();
            towns.remember(CivicFixture.facts(ashford.joinNation(id).orElseThrow()));
            nations.remember(Nation.restore(id, CivicFixture.name("Valen"), mayor, ashford.id(),
                    UUID.randomUUID(), Set.of(ashford.id(), TownId.random()), CivicFixture.NOW));

            assertThat(audiences.forSpeaker(mayor, ChatChannel.NATION).orElseThrow().members())
                    .containsExactlyInAnyOrder(mayor, citizen);
        }
    }

    @Nested
    @DisplayName("the active channel")
    class Toggling {

        @Test
        @DisplayName("the same command twice turns it on and off again")
        void toggling() {
            final ActiveChannels active = ActiveChannels.empty();
            final UUID player = UUID.randomUUID();

            assertThat(active.toggle(player, ChatChannel.TOWN)).contains(ChatChannel.TOWN);
            assertThat(active.of(player)).contains(ChatChannel.TOWN);
            assertThat(active.toggle(player, ChatChannel.TOWN)).isEmpty();
            assertThat(active.of(player)).isEmpty();
        }

        @Test
        @DisplayName("switching channels replaces rather than stacking")
        void switchingReplaces() {
            final ActiveChannels active = ActiveChannels.empty();
            final UUID player = UUID.randomUUID();

            active.toggle(player, ChatChannel.TOWN);
            assertThat(active.toggle(player, ChatChannel.NATION)).contains(ChatChannel.NATION);
            assertThat(active.of(player)).contains(ChatChannel.NATION);
        }

        @Test
        @DisplayName("a player with no channel is in ordinary chat")
        void defaultIsOrdinaryChat() {
            assertThat(ActiveChannels.empty().of(UUID.randomUUID())).isEmpty();
        }

        @Test
        @DisplayName("logging out clears it")
        void quittingClears() {
            // A mode that survived a logout is a player logging in tomorrow and saying something to
            // their town that they meant for the server.
            final ActiveChannels active = ActiveChannels.empty();
            final UUID player = UUID.randomUUID();
            active.toggle(player, ChatChannel.TOWN);

            active.clear(player);

            assertThat(active.of(player)).isEmpty();
            assertThat(active.tracked()).isZero();
        }
    }

    @Nested
    @DisplayName("naming a channel")
    class Parsing {

        @Test
        @DisplayName("a channel is named however a player types it")
        void parsing() {
            assertThat(ChatChannel.parse("town")).contains(ChatChannel.TOWN);
            assertThat(ChatChannel.parse("TC")).contains(ChatChannel.TOWN);
            assertThat(ChatChannel.parse(" nation ")).contains(ChatChannel.NATION);
            assertThat(ChatChannel.parse("ally")).isEmpty();
            assertThat(ChatChannel.parse(null)).isEmpty();
        }
    }
}
