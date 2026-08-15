package net.riftbreaker.rifttowny.domain.diplomacy;

import net.riftbreaker.rifttowny.domain.org.NationId;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Who every nation has declared what about, in memory.
 *
 * <p>Protection asks "are these two allied" on blocks a player touches, so the answer has to come
 * from memory for the same reason the claim index does. It is small: a declaration is two ids and a
 * kind, and even a server with fifty nations has a few hundred of them.</p>
 *
 * <p><strong>Declarations are stored one-way, exactly as they are made.</strong> An alliance is
 * then two of them, and {@link #areAllied} is what makes that rule real rather than a convention —
 * holding a single "these two are allied" row instead would have made the mutual requirement
 * something every caller had to remember.</p>
 *
 * <p>Thread-safe and mutable, like its companions. The facts live in {@code rt_nation_relation}.</p>
 */
public final class DiplomacyBook {

    /** Declarer to the nations it has declared that relation about. */
    private final Map<Relation, Map<NationId, Set<NationId>>> declarations = new ConcurrentHashMap<>();

    private final AtomicLong generation = new AtomicLong();

    public static DiplomacyBook empty() {
        return new DiplomacyBook();
    }

    // --- writing -------------------------------------------------------------------------------

    /** Replaces everything, as at startup. */
    public synchronized void replaceAll(final Collection<Declaration> loaded) {
        Objects.requireNonNull(loaded, "loaded");
        declarations.clear();
        loaded.forEach(this::put);
        generation.incrementAndGet();
    }

    /** Records one declaration. Idempotent: declaring twice is the same as declaring once. */
    public synchronized void declare(final Declaration declaration) {
        put(Objects.requireNonNull(declaration, "declaration"));
        generation.incrementAndGet();
    }

    /** Withdraws one. Silent when it was never there. */
    public synchronized void withdraw(final Declaration declaration) {
        if (declaration == null) {
            return;
        }
        final Map<NationId, Set<NationId>> byDeclarer = declarations.get(declaration.relation());
        if (byDeclarer != null) {
            final Set<NationId> targets = byDeclarer.get(declaration.declarer());
            if (targets != null) {
                targets.remove(declaration.target());
            }
        }
        generation.incrementAndGet();
    }

    /**
     * Forgets a nation entirely, in both directions.
     *
     * <p>Called when a nation dissolves. Leaving its declarations behind would make a dead nation
     * somebody's ally, and — worse — leave the nations that had declared <em>it</em> an enemy
     * carrying a grudge against nothing.</p>
     */
    public synchronized void forget(final NationId nation) {
        if (nation == null) {
            return;
        }
        declarations.values().forEach(byDeclarer -> {
            byDeclarer.remove(nation);
            byDeclarer.values().forEach(targets -> targets.remove(nation));
        });
        generation.incrementAndGet();
    }

    private void put(final Declaration declaration) {
        declarations
                .computeIfAbsent(declaration.relation(), ignored -> new ConcurrentHashMap<>())
                .computeIfAbsent(declaration.declarer(), ignored -> ConcurrentHashMap.newKeySet())
                .add(declaration.target());
    }

    // --- reading -------------------------------------------------------------------------------

    /**
     * Whether these two nations are allied.
     *
     * <p>Both must have said so. This is the whole reason declarations are stored one-way.</p>
     */
    public boolean areAllied(final NationId one, final NationId other) {
        if (one == null || other == null || one.equals(other)) {
            return false;
        }
        return hasDeclared(one, Relation.ALLY, other) && hasDeclared(other, Relation.ALLY, one);
    }

    /**
     * Whether the first nation treats the second as an enemy.
     *
     * <p>One-way on purpose, and the argument order matters: {@code isEnemy(a, b)} asks what
     * <em>a</em> has declared, not whether the two are at odds.</p>
     */
    public boolean isEnemy(final NationId declarer, final NationId target) {
        return hasDeclared(declarer, Relation.ENEMY, target);
    }

    /** Whether either has declared the other an enemy — which is what a war looks like from outside. */
    public boolean hostile(final NationId one, final NationId other) {
        return isEnemy(one, other) || isEnemy(other, one);
    }

    /** Whether this nation has made that declaration, agreed or not. */
    public boolean hasDeclared(
            final NationId declarer, final Relation relation, final NationId target) {
        if (declarer == null || relation == null || target == null) {
            return false;
        }
        return declarations
                .getOrDefault(relation, Map.of())
                .getOrDefault(declarer, Set.of())
                .contains(target);
    }

    /** Everything this nation has declared of one kind, agreed or not. */
    public Set<NationId> declared(final NationId declarer, final Relation relation) {
        if (declarer == null || relation == null) {
            return Set.of();
        }
        return Set.copyOf(declarations
                .getOrDefault(relation, Map.of())
                .getOrDefault(declarer, Set.of()));
    }

    /** Only the alliances that are actually mutual. */
    public Set<NationId> allies(final NationId nation) {
        final Set<NationId> mutual = new LinkedHashSet<>();
        for (final NationId candidate : declared(nation, Relation.ALLY)) {
            if (hasDeclared(candidate, Relation.ALLY, nation)) {
                mutual.add(candidate);
            }
        }
        return Set.copyOf(mutual);
    }

    /** Alliances this nation has offered that have not been returned. */
    public Set<NationId> offeredAlliances(final NationId nation) {
        final Set<NationId> pending = new LinkedHashSet<>();
        for (final NationId candidate : declared(nation, Relation.ALLY)) {
            if (!hasDeclared(candidate, Relation.ALLY, nation)) {
                pending.add(candidate);
            }
        }
        return Set.copyOf(pending);
    }

    // --- diagnostics ---------------------------------------------------------------------------

    public int size() {
        int total = 0;
        for (final Map<NationId, Set<NationId>> byDeclarer : declarations.values()) {
            for (final Set<NationId> targets : byDeclarer.values()) {
                total += targets.size();
            }
        }
        return total;
    }

    public long generation() {
        return generation.get();
    }

    public String describe() {
        return size() + " declaration(s)";
    }

    /**
     * One nation's statement about another.
     *
     * @param declarer who said it. Never interchangeable with the target: an enmity binds only the
     *        nation that declared it
     */
    public record Declaration(NationId declarer, Relation relation, NationId target) {

        public Declaration {
            Objects.requireNonNull(declarer, "declarer");
            Objects.requireNonNull(relation, "relation");
            Objects.requireNonNull(target, "target");
            if (declarer.equals(target)) {
                throw new IllegalArgumentException("A nation cannot declare a relation with itself");
            }
        }
    }
}
