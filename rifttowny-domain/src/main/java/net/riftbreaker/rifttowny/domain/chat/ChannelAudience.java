package net.riftbreaker.rifttowny.domain.chat;

import net.riftbreaker.rifttowny.domain.civic.CivicCache;
import net.riftbreaker.rifttowny.domain.civic.NationCache;
import net.riftbreaker.rifttowny.domain.civic.TownFacts;
import net.riftbreaker.rifttowny.domain.org.Nation;
import net.riftbreaker.rifttowny.domain.org.NationId;
import net.riftbreaker.rifttowny.domain.org.ResidentId;
import net.riftbreaker.rifttowny.domain.org.TownId;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Who hears a channel.
 *
 * <p>This is the half of chat RiftTowny owns. RiftChat's own javadoc draws the line — a
 * presentation request is "supplied […] after it has selected the channel and recipients", and
 * "callers must never rely on RiftChat to validate routing or privacy". So membership is decided
 * here, from the same cache protection reads, and RiftChat is told who to draw it for.</p>
 *
 * <p>Answered from memory because chat is not a place that can wait. A message is sent on the
 * network thread and the audience has to be known before it can be delivered; a query here would be
 * a query per message per player.</p>
 */
public final class ChannelAudience {

    private final CivicCache towns;
    private final NationCache nations;
    private final net.riftbreaker.rifttowny.domain.diplomacy.DiplomacyBook diplomacy;

    public ChannelAudience(
            final CivicCache towns,
            final NationCache nations,
            final net.riftbreaker.rifttowny.domain.diplomacy.DiplomacyBook diplomacy) {
        this.towns = Objects.requireNonNull(towns, "towns");
        this.nations = Objects.requireNonNull(nations, "nations");
        this.diplomacy = Objects.requireNonNull(diplomacy, "diplomacy");
    }

    /**
     * Everybody who should hear this speaker on this channel.
     *
     * <p>Empty when the speaker has nowhere to say it — no town, or no nation — which the caller
     * turns into a refusal. A channel that accepted the message and delivered it to nobody would be
     * worse than one that refused: the player would believe they had been heard.</p>
     */
    public Optional<Audience> forSpeaker(final ResidentId speaker, final ChatChannel channel) {
        if (speaker == null || channel == null) {
            return Optional.empty();
        }
        return switch (channel) {
            case TOWN -> towns.townFactsOf(speaker).map(ChannelAudience::townAudience);
            case NATION -> towns.townFactsOf(speaker)
                    .flatMap(TownFacts::nation)
                    .flatMap(nations::nation)
                    .map(this::nationAudience);
            case ALLY -> towns.townFactsOf(speaker)
                    .flatMap(TownFacts::nation)
                    .flatMap(nations::nation)
                    .flatMap(this::allyAudience);
        };
    }

    /**
     * Everybody in this nation and in every nation allied to it.
     *
     * <p>The speaker's own nation is included, not only its allies. An ally channel where you
     * cannot see your own people talking is one where half a conversation is invisible, and
     * somebody would answer a question nobody in the room had heard asked.</p>
     *
     * <p>Empty when the nation has no allies, which the caller turns into a refusal. That is the
     * promise {@link ChatChannel}'s javadoc used to keep by not having the channel at all: a
     * message delivered only back to the people who could already hear you on {@code /nc} is one
     * the speaker would read as having reached their allies.</p>
     *
     * <p>Alliances are mutual — {@code DiplomacyBook.allies} returns only pairs where both have
     * declared — so this cannot reach a nation that has not agreed to hear it.</p>
     */
    private Optional<Audience> allyAudience(final Nation nation) {
        final Set<NationId> allies = diplomacy.allies(nation.id());
        if (allies.isEmpty()) {
            return Optional.empty();
        }
        final Set<ResidentId> members = new LinkedHashSet<>(nationAudience(nation).members());
        for (final NationId ally : allies) {
            nations.nation(ally).ifPresent(found -> members.addAll(nationAudience(found).members()));
        }
        return Optional.of(new Audience(
                ChatChannel.ALLY, nation.name().display(), Set.copyOf(members)));
    }

    private static Audience townAudience(final TownFacts facts) {
        return new Audience(ChatChannel.TOWN, facts.displayName(), facts.residents());
    }

    /**
     * Everybody in every member town.
     *
     * <p>A town this cache cannot describe contributes nobody rather than throwing. A missing town
     * should cost its residents the message, not cost everybody else theirs.</p>
     */
    private Audience nationAudience(final Nation nation) {
        final Set<ResidentId> members = new LinkedHashSet<>();
        for (final TownId town : nation.towns()) {
            towns.town(town).ifPresent(facts -> members.addAll(facts.residents()));
        }
        return new Audience(ChatChannel.NATION, nation.name().display(), Set.copyOf(members));
    }

    /**
     * A channel's recipients, and what to call it.
     *
     * @param label the town's or nation's name, for the message that says where this went
     * @param members everybody entitled to hear it, online or not. Filtering to who is actually
     *        here belongs to the caller, which is the only thing that knows
     */
    public record Audience(ChatChannel channel, String label, Set<ResidentId> members) {

        public Audience {
            Objects.requireNonNull(channel, "channel");
            Objects.requireNonNull(label, "label");
            members = Set.copyOf(Objects.requireNonNull(members, "members"));
        }

        public boolean includes(final ResidentId who) {
            return who != null && members.contains(who);
        }

        public int size() {
            return members.size();
        }
    }
}
