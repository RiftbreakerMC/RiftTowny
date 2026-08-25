package net.riftbreaker.rifttowny.domain.justice;

import net.riftbreaker.rifttowny.domain.org.ResidentId;
import net.riftbreaker.rifttowny.domain.org.TownId;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Who each town has declared unwelcome, in memory.
 *
 * <p>Protection asks "is this player outlawed here" on blocks a player touches, so the answer comes
 * from memory for the same reason the claim index and the diplomacy book do. It is small: two ids
 * per row, and a town with a hundred grudges is a remarkable town.</p>
 *
 * <p><strong>Keyed by town, not by player.</strong> The hot question is always "is this one player
 * unwelcome in this one town" — asked with both ids in hand — and the town's own list is also what
 * its screen prints. The reverse question, "where am I unwelcome", is asked once on a command and is
 * answered by walking the towns rather than by keeping a second index that could disagree with the
 * first.</p>
 *
 * <p>Thread-safe and mutable, like its companions. The facts live in {@code rt_town_outlaw}, and
 * this is updated after a transaction commits, never inside one — a rolled-back declaration that had
 * already reached the cache would leave a player barred by a rule the database never took.</p>
 */
public final class Outlaws {

    private final Map<TownId, Set<ResidentId>> byTown = new ConcurrentHashMap<>();

    private final AtomicLong generation = new AtomicLong();

    public static Outlaws empty() {
        return new Outlaws();
    }

    // --- writing -------------------------------------------------------------------------------

    /** Replaces everything, as at startup. */
    public synchronized void replaceAll(final Collection<Declaration> loaded) {
        Objects.requireNonNull(loaded, "loaded");
        byTown.clear();
        loaded.forEach(this::put);
        generation.incrementAndGet();
    }

    /** Records one. Idempotent: declaring twice is the same as declaring once. */
    public synchronized void declare(final TownId town, final ResidentId who) {
        put(new Declaration(town, who));
        generation.incrementAndGet();
    }

    /** Lifts one. Silent when it was never there. */
    public synchronized void pardon(final TownId town, final ResidentId who) {
        if (town == null || who == null) {
            return;
        }
        // Dropped entirely once empty, rather than left as an empty set: townsOutlawing
        // walks these, and a town that has pardoned everybody should stop being walked.
        byTown.computeIfPresent(town, (ignored, held) -> {
            held.remove(who);
            return held.isEmpty() ? null : held;
        });
        generation.incrementAndGet();
    }

    /** Forgets a disbanded town's list. The rows cascade; this is the cache being told. */
    public synchronized void forget(final TownId town) {
        if (town != null && byTown.remove(town) != null) {
            generation.incrementAndGet();
        }
    }

    private void put(final Declaration declaration) {
        Objects.requireNonNull(declaration, "declaration");
        byTown.computeIfAbsent(declaration.town(), ignored -> ConcurrentHashMap.newKeySet())
                .add(declaration.who());
    }

    // --- reading -------------------------------------------------------------------------------

    /** Whether this town has declared this player unwelcome. The hot path's only question. */
    public boolean isOutlawed(final TownId town, final ResidentId who) {
        if (town == null || who == null) {
            return false;
        }
        final Set<ResidentId> held = byTown.get(town);
        return held != null && held.contains(who);
    }

    /** One town's list, for its own screen. */
    public Set<ResidentId> of(final TownId town) {
        if (town == null) {
            return Set.of();
        }
        return Set.copyOf(byTown.getOrDefault(town, Set.of()));
    }

    /**
     * Every town that has declared this player unwelcome.
     *
     * <p>A walk rather than a second index, and the trade is deliberate: this is asked once when a
     * player opens their own record, while the index that would make it fast would have to be kept
     * in step with the first on every change — and two indexes that can disagree about who is barred
     * from where is a worse failure than a loop over a few hundred towns.</p>
     */
    public Set<TownId> townsOutlawing(final ResidentId who) {
        if (who == null) {
            return Set.of();
        }
        final Set<TownId> found = new java.util.LinkedHashSet<>();
        byTown.forEach((town, held) -> {
            if (held.contains(who)) {
                found.add(town);
            }
        });
        return Set.copyOf(found);
    }

    public int size() {
        int total = 0;
        for (final Set<ResidentId> held : byTown.values()) {
            total += held.size();
        }
        return total;
    }

    /** Changes on every mutation, so a downstream view can notice without being told. */
    public long generation() {
        return generation.get();
    }

    public String describe() {
        return "Outlaws[towns=" + byTown.size() + ", declarations=" + size() + ']';
    }

    /** One town's declaration about one player. */
    public record Declaration(TownId town, ResidentId who) {

        public Declaration {
            Objects.requireNonNull(town, "town");
            Objects.requireNonNull(who, "who");
        }
    }
}
