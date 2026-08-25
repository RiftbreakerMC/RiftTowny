package net.riftbreaker.rifttowny.domain.civic;

import net.riftbreaker.rifttowny.domain.org.NationId;
import net.riftbreaker.rifttowny.domain.org.ResidentId;
import net.riftbreaker.rifttowny.domain.org.TownId;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Every town's civic facts, in memory, answerable without touching the database.
 *
 * <p>The companion to {@link net.riftbreaker.rifttowny.domain.territory.TerritoryIndex} and it
 * exists for the same reason. That index answers "who owns this chunk"; this answers everything that
 * follows from the owner — which nation they belong to, whether they trust this player, what their
 * roles grant. A block-break listener needs all of it before the event returns, and every one of
 * those is a database read that a server thread cannot afford and that a Folia region thread may not
 * even make.</p>
 *
 * <p><strong>Whole towns, not online players.</strong> An earlier shape cached only the residents
 * who were online. It was smaller, and wrong: the question a listener asks is about the
 * <em>owning</em> town, whose members may all be offline, and maintaining an online-only view meant
 * join and quit handling that could leave a player's membership behind. Holding every town is a few
 * hundred bytes each — trivial beside the world data the server already holds — and it makes each
 * town's facts a single value that is replaced whole.</p>
 *
 * <p>Thread-safe. Reads never lock, because they happen on every block a player touches. Writes take
 * a lock, because they happen when somebody joins a town — rare enough that the cost is invisible,
 * and coordinated enough that the resident index cannot drift from the towns it is derived from.</p>
 *
 * <p>Mutable, and a cache rather than a fact. The facts live in {@code rt_town}, {@code rt_role} and
 * their kin; this is the copy the server thread is allowed to ask.</p>
 */
public final class CivicCache {

    private final Map<TownId, TownFacts> towns = new ConcurrentHashMap<>();

    /**
     * Which town each resident belongs to.
     *
     * <p>Derived from {@link #towns}, never set independently. It exists because the alternative —
     * scanning every town's resident set to find one player — is linear in the number of towns on
     * the hottest path in the plugin.</p>
     */
    private final Map<ResidentId, TownId> membership = new ConcurrentHashMap<>();

    private final AtomicLong generation = new AtomicLong();

    public static CivicCache empty() {
        return new CivicCache();
    }

    // --- writing -------------------------------------------------------------------------------

    /**
     * Replaces the whole cache, as at startup or after a repair.
     *
     * <p>Built and swapped rather than cleared and refilled, for the same reason as the territory
     * index: a cleared cache reports every town as unknown, and a protection check during that
     * window would deny a resident building in their own town.</p>
     */
    public synchronized void replaceAll(final Collection<TownFacts> loaded) {
        Objects.requireNonNull(loaded, "loaded");
        final Map<TownId, TownFacts> replacementTowns = new HashMap<>();
        final Map<ResidentId, TownId> replacementMembers = new HashMap<>();
        for (final TownFacts facts : loaded) {
            replacementTowns.put(facts.id(), facts);
            for (final ResidentId resident : facts.residents()) {
                replacementMembers.put(resident, facts.id());
            }
        }
        towns.keySet().retainAll(replacementTowns.keySet());
        towns.putAll(replacementTowns);
        membership.keySet().retainAll(replacementMembers.keySet());
        membership.putAll(replacementMembers);
        generation.incrementAndGet();
    }

    /**
     * Records a town's current state, replacing whatever was held before.
     *
     * <p>The resident index is reconciled against the previous version rather than rebuilt: the
     * people who left this town must stop resolving to it, and the ones who joined must start. Doing
     * it from the difference keeps the work proportional to the town rather than to the server.</p>
     */
    public synchronized void remember(final TownFacts facts) {
        Objects.requireNonNull(facts, "facts");
        final TownFacts previous = towns.put(facts.id(), facts);
        if (previous != null) {
            for (final ResidentId departed : previous.residents()) {
                if (!facts.hasResident(departed)) {
                    membership.remove(departed, facts.id());
                }
            }
        }
        for (final ResidentId resident : facts.residents()) {
            membership.put(resident, facts.id());
        }
        generation.incrementAndGet();
    }

    /** Records a disbanded town. Its residents become townless rather than members of a ghost. */
    public synchronized void forget(final TownId town) {
        if (town == null) {
            return;
        }
        final TownFacts removed = towns.remove(town);
        if (removed != null) {
            for (final ResidentId resident : removed.residents()) {
                membership.remove(resident, town);
            }
        }
        // Belt and braces: a resident indexed to this town that its own facts did not list would
        // otherwise survive the disband and keep resolving to a town that no longer exists.
        membership.values().removeIf(town::equals);
        generation.incrementAndGet();
    }

    // --- reading -------------------------------------------------------------------------------

    /** Everything known about a town, or empty if this cache cannot describe it. */
    public Optional<TownFacts> town(final TownId town) {
        return town == null ? Optional.empty() : Optional.ofNullable(towns.get(town));
    }

    /**
     * Whether this cache can answer for a town at all.
     *
     * <p>A protection check against an unknown town must not fall through to "allowed" — an unknown
     * town is a fault, and the safe reading of a fault is that the land is protected. Callers test
     * this and deny rather than treating the absence as permission.</p>
     */
    public boolean knows(final TownId town) {
        return town != null && towns.containsKey(town);
    }

    public Optional<TownId> townOf(final ResidentId resident) {
        return resident == null ? Optional.empty() : Optional.ofNullable(membership.get(resident));
    }

    /** The facts of the town this resident belongs to, if any. */
    public Optional<TownFacts> townFactsOf(final ResidentId resident) {
        return townOf(resident).flatMap(this::town);
    }

    public Optional<NationId> nationOf(final TownId town) {
        return town(town).flatMap(TownFacts::nation);
    }

    /** The nation a resident belongs to through their town, if any. */
    public Optional<NationId> nationOfResident(final ResidentId resident) {
        return townFactsOf(resident).flatMap(TownFacts::nation);
    }

    public boolean isTrusted(final TownId town, final ResidentId resident) {
        return town(town).map(facts -> facts.trusts(resident)).orElse(false);
    }

    /** Every town held, for a startup summary or an administrative dump. */
    public Set<TownId> townIds() {
        return Set.copyOf(towns.keySet());
    }

    /**
     * Every resident held, with the town they belong to.
     *
     * <p>A live unmodifiable view rather than a copy, unlike {@link #townIds()}: this map has one
     * entry per resident on the server rather than one per town, and a listing that copies all of
     * them to show ten is the wrong shape. Iterating it is safe from any thread — the map underneath
     * is concurrent, so a reader sees a consistent entry for each resident it reaches, and a town
     * founded halfway through a listing may or may not appear in it. For a directory of who lives
     * where, that is the right trade; for a protection decision it would not be, which is why
     * nothing on that path reads this.</p>
     */
    public Map<ResidentId, TownId> membership() {
        return java.util.Collections.unmodifiableMap(membership);
    }

    // --- diagnostics ---------------------------------------------------------------------------

    public int cachedTowns() {
        return towns.size();
    }

    public int cachedResidents() {
        return membership.size();
    }

    /** Changes on every mutation, so a downstream view can notice without being told. */
    public long generation() {
        return generation.get();
    }

    public String describe() {
        return cachedTowns() + " town(s), " + cachedResidents() + " resident(s)";
    }
}
