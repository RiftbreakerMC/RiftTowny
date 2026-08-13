package net.riftbreaker.rifttowny.paper.protection;

import net.riftbreaker.rifttowny.api.ChunkKey;
import net.riftbreaker.rifttowny.domain.civic.CivicCache;
import net.riftbreaker.rifttowny.domain.civic.TownFacts;
import net.riftbreaker.rifttowny.domain.org.ResidentId;
import net.riftbreaker.rifttowny.domain.territory.Claim;
import net.riftbreaker.rifttowny.domain.territory.Ruin;
import net.riftbreaker.rifttowny.domain.territory.RuinIndex;
import net.riftbreaker.rifttowny.domain.territory.TerritoryIndex;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * What to say about the ground a player has just stepped onto, and when to say anything at all.
 *
 * <p>Split from the listener so the interesting half — <em>has the territory actually changed</em> —
 * is decidable without a server. A notice on every chunk boundary would be noise: a town is many
 * chunks, and a player crossing its middle has not arrived anywhere.</p>
 *
 * <p>Every lookup is a map read against the same three caches the protection check uses, so this
 * costs the same as one block-break decision and never touches storage.</p>
 */
public final class TerritoryNotice {

    private final TerritoryIndex territory;
    private final RuinIndex ruins;
    private final CivicCache civic;

    public TerritoryNotice(
            final TerritoryIndex territory, final RuinIndex ruins, final CivicCache civic) {
        this.territory = Objects.requireNonNull(territory, "territory");
        this.ruins = Objects.requireNonNull(ruins, "ruins");
        this.civic = Objects.requireNonNull(civic, "civic");
    }

    /**
     * What the ground at this position is.
     *
     * <p>Named by an identity rather than a description, so "has this changed" is an equality check
     * on a small value rather than a comparison of two rendered strings.</p>
     */
    public Territory at(final ChunkKey chunk, final Instant now) {
        final Optional<Claim> claim = territory.at(chunk);
        if (claim.isPresent()) {
            final Optional<TownFacts> facts = civic.town(claim.get().town());
            return new Territory(
                    Kind.TOWN,
                    claim.get().town().value().toString(),
                    facts.map(TownFacts::displayName).orElse(null),
                    claim.get().owner(),
                    null);
        }
        final Optional<Ruin> ruin = ruins.at(chunk);
        if (ruin.isPresent()) {
            return new Territory(
                    Kind.RUIN,
                    ruin.get().id().toString(),
                    ruin.get().name().display(),
                    null,
                    ruin.get().remaining(now));
        }
        return Territory.WILDERNESS;
    }

    /**
     * Whether crossing from one to the other is worth announcing.
     *
     * <p>True on a change of ground, and — inside a town — on a change of who holds the plot. The
     * second is not decoration: walking from your own square onto a neighbour's changes what you may
     * do there, and finding that out by being refused is the experience this exists to avoid.</p>
     */
    public static boolean worthAnnouncing(final Territory from, final Territory to) {
        if (from == null) {
            // First position after joining. Wilderness is the default state of the world and
            // announcing it to everybody who logs in outside a town is noise.
            return to.kind() != Kind.WILDERNESS;
        }
        if (from.kind() != to.kind() || !Objects.equals(from.id(), to.id())) {
            return true;
        }
        return to.kind() == Kind.TOWN && !Objects.equals(from.holder(), to.holder());
    }

    /** What kind of ground. */
    public enum Kind {
        WILDERNESS,
        TOWN,
        RUIN
    }

    /**
     * One piece of ground, as a player would be told about it.
     *
     * @param id the town or ruin this is, so two towns with the same display name are still two
     *        places. Empty for wilderness, which is everywhere and nowhere
     * @param name what to call it, or null when the civic cache cannot describe the town — which is
     *        a fault the protection check already refuses on, and not worth a second message
     * @param holder the resident holding this plot, or null
     * @param remaining how long a ruin has left, or null
     */
    public record Territory(
            Kind kind, String id, String name, ResidentId holder, java.time.Duration remaining) {

        static final Territory WILDERNESS = new Territory(Kind.WILDERNESS, "", null, null, null);

        public Territory {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(id, "id");
        }

        public Optional<String> displayName() {
            return Optional.ofNullable(name);
        }
    }
}
