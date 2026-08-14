package net.riftbreaker.rifttowny.domain.civic;

import net.riftbreaker.rifttowny.domain.org.Nation;
import net.riftbreaker.rifttowny.domain.org.NationId;
import net.riftbreaker.rifttowny.domain.org.TownId;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Every nation, in memory.
 *
 * <p>The last of the read models, and the one that was deliberately left out until something needed
 * it. Nothing on a movement path asks about a nation — protection resolves the
 * {@link net.riftbreaker.rifttowny.domain.flag.Relationship#NATION} rung from the owning town's
 * {@code nation_id}, which is already cached, and never needs the nation's <em>name</em>.</p>
 *
 * <p>Two things changed that. A placeholder must answer without touching storage, and roughly twenty
 * of Towny's placeholders are a nation's name, tag, board, colour, king or capital. And
 * {@code /town list} had to read every nation once per invocation to turn twenty ids into twenty
 * names. Both are the same missing cache.</p>
 *
 * <p>Far smaller than {@link CivicCache}: a server with three hundred towns has perhaps twenty
 * nations, and a nation is a name, a leader, a capital and a set of town ids.</p>
 *
 * <p>Thread-safe and mutable, like its companions. The facts live in {@code rt_nation}; this is the
 * copy the server thread is allowed to ask.</p>
 */
public final class NationCache {

    private final Map<NationId, Nation> nations = new ConcurrentHashMap<>();

    /**
     * Which nation each town belongs to.
     *
     * <p>Derived from {@link #nations}, never set independently. {@link CivicCache} already answers
     * this from the town's own row, and the two must agree; this one exists so a nation's member
     * list can be walked without scanning every town on the server.</p>
     */
    private final Map<TownId, NationId> membership = new ConcurrentHashMap<>();

    private final AtomicLong generation = new AtomicLong();

    public static NationCache empty() {
        return new NationCache();
    }

    // --- writing -------------------------------------------------------------------------------

    /**
     * Replaces the whole cache, as at startup.
     *
     * <p>Built and swapped rather than cleared and refilled, for the same reason as its companions:
     * a cleared cache reports every nation as unknown, and a placeholder resolving during that
     * window would render a player's nation as blank.</p>
     */
    public synchronized void replaceAll(final Collection<Nation> loaded) {
        Objects.requireNonNull(loaded, "loaded");
        final Map<NationId, Nation> replacement = new HashMap<>();
        final Map<TownId, NationId> replacementMembers = new HashMap<>();
        for (final Nation nation : loaded) {
            replacement.put(nation.id(), nation);
            for (final TownId town : nation.towns()) {
                replacementMembers.put(town, nation.id());
            }
        }
        nations.keySet().retainAll(replacement.keySet());
        nations.putAll(replacement);
        membership.keySet().retainAll(replacementMembers.keySet());
        membership.putAll(replacementMembers);
        generation.incrementAndGet();
    }

    /** Records a nation's current state, reconciling its member towns against the previous version. */
    public synchronized void remember(final Nation nation) {
        Objects.requireNonNull(nation, "nation");
        final Nation previous = nations.put(nation.id(), nation);
        if (previous != null) {
            for (final TownId departed : previous.towns()) {
                if (!nation.hasTown(departed)) {
                    membership.remove(departed, nation.id());
                }
            }
        }
        for (final TownId town : nation.towns()) {
            membership.put(town, nation.id());
        }
        generation.incrementAndGet();
    }

    /** Records a dissolved nation. Its towns become independent rather than members of a ghost. */
    public synchronized void forget(final NationId nation) {
        if (nation == null) {
            return;
        }
        nations.remove(nation);
        // Swept rather than driven from the removed nation's own town set: a town indexed here that
        // the nation did not list would otherwise survive the dissolution and keep resolving.
        membership.values().removeIf(nation::equals);
        generation.incrementAndGet();
    }

    // --- reading -------------------------------------------------------------------------------

    public Optional<Nation> nation(final NationId id) {
        return id == null ? Optional.empty() : Optional.ofNullable(nations.get(id));
    }

    /** The nation a town belongs to, if any. */
    public Optional<Nation> of(final TownId town) {
        return town == null ? Optional.empty() : nation(membership.get(town));
    }

    /** What to call a nation, or empty if this cache cannot describe it. */
    public Optional<String> nameOf(final NationId id) {
        return nation(id).map(found -> found.name().display());
    }

    public boolean knows(final NationId id) {
        return id != null && nations.containsKey(id);
    }

    /** Every nation held, for a listing. A copy, so a caller may sort it. */
    public List<Nation> all() {
        return List.copyOf(nations.values());
    }

    // --- diagnostics ---------------------------------------------------------------------------

    public int size() {
        return nations.size();
    }

    /** Changes on every mutation, so a downstream view can notice without being told. */
    public long generation() {
        return generation.get();
    }

    public String describe() {
        return size() + " nation(s), " + membership.size() + " member town(s)";
    }
}
