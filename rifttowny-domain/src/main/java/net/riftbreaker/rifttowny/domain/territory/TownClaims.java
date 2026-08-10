package net.riftbreaker.rifttowny.domain.territory;

import net.riftbreaker.rifttowny.api.ChunkKey;
import net.riftbreaker.rifttowny.domain.event.DomainEvent;
import net.riftbreaker.rifttowny.domain.org.ChangeDenial;
import net.riftbreaker.rifttowny.domain.org.Outcome;
import net.riftbreaker.rifttowny.domain.org.TownId;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * One town's territory, and the shape rules that govern it.
 *
 * <p>The rules only make sense across the whole set — whether a chunk touches the town, whether
 * removing one would strand others — so they live here rather than on {@link Claim}, which can only
 * see itself.</p>
 *
 * <p>This aggregate knows nothing about <em>other</em> towns. Whether a chunk is already owned by
 * somebody else is answered by the unique constraint on {@code (world_id, chunk_x, chunk_z)} and
 * reported by the service; duplicating that check here would be a second source of truth that could
 * disagree under a race.</p>
 *
 * <p>Immutable, like the other aggregates.</p>
 */
public final class TownClaims {

    private final TownId town;
    private final Map<ChunkKey, Claim> claims;

    private TownClaims(final TownId town, final Map<ChunkKey, Claim> claims) {
        this.town = town;
        this.claims = Collections.unmodifiableMap(new LinkedHashMap<>(claims));
    }

    /** A town that has claimed nothing yet. */
    public static TownClaims empty(final TownId town) {
        return new TownClaims(Objects.requireNonNull(town, "town"), Map.of());
    }

    /** Rebuilds from storage. */
    public static TownClaims restore(final TownId town, final Iterable<Claim> claims) {
        Objects.requireNonNull(town, "town");
        Objects.requireNonNull(claims, "claims");

        final Map<ChunkKey, Claim> byChunk = new LinkedHashMap<>();
        Claim homeblock = null;
        for (final Claim claim : claims) {
            if (!claim.town().equals(town)) {
                throw new IllegalArgumentException(
                        "Claim " + claim.chunk() + " belongs to " + claim.town() + ", not " + town);
            }
            if (claim.kind() == ClaimKind.HOMEBLOCK) {
                if (homeblock != null) {
                    // Two homeblocks make "the last chunk that may be unclaimed" ambiguous, and the
                    // ambiguity would surface as a town that can be dissolved by unclaiming.
                    throw new IllegalArgumentException(
                            "Town " + town + " was restored with two homeblocks");
                }
                homeblock = claim;
            }
            byChunk.put(claim.chunk(), claim);
        }
        if (homeblock == null && !byChunk.isEmpty()) {
            throw new IllegalArgumentException(
                    "Town " + town + " was restored with claims but no homeblock");
        }
        return new TownClaims(town, byChunk);
    }

    /**
     * Claims a chunk.
     *
     * <p>The first chunk a town takes becomes its homeblock whatever kind is asked for, because a
     * town with an outpost and no homeblock has no anchor and no spawn. Asking for something else
     * first is refused rather than silently corrected — quietly changing what an operator asked for
     * is how a paid outpost turns into a free homeblock.</p>
     */
    public Outcome<TownClaims> claim(
            final ChunkKey chunk, final ClaimKind kind, final Instant now) {
        Objects.requireNonNull(chunk, "chunk");
        Objects.requireNonNull(kind, "kind");

        if (claims.containsKey(chunk)) {
            return Outcome.denied(ChangeDenial.CHUNK_ALREADY_CLAIMED);
        }
        if (claims.isEmpty()) {
            if (kind != ClaimKind.HOMEBLOCK) {
                return Outcome.denied(ChangeDenial.FIRST_CLAIM_MUST_BE_THE_HOMEBLOCK);
            }
        } else {
            if (kind == ClaimKind.HOMEBLOCK) {
                return Outcome.denied(ChangeDenial.ALREADY_THE_HOMEBLOCK);
            }
            final boolean touches = touchesTown(chunk);
            if (kind == ClaimKind.ORDINARY && !touches) {
                return Outcome.denied(ChangeDenial.CLAIM_MUST_TOUCH_TOWN);
            }
            if (kind == ClaimKind.OUTPOST && touches) {
                return Outcome.denied(ChangeDenial.OUTPOST_MUST_NOT_TOUCH_TOWN);
            }
        }

        final Claim claim = Claim.of(chunk, town, kind, now);
        final Map<ChunkKey, Claim> updated = new LinkedHashMap<>(claims);
        updated.put(chunk, claim);
        return Outcome.applied(
                new TownClaims(town, updated),
                new DomainEvent.ChunkClaimed(town, chunk.toString(), kind.name()));
    }

    /**
     * Releases a chunk.
     *
     * <p>Refuses when it would strand other claims, rather than converting the stranded part into
     * outposts. Which half of a severed town is the real one is a decision only its mayor can
     * make, and guessing produces a town whose spawn is suddenly in someone else's half.</p>
     */
    public Outcome<TownClaims> unclaim(final ChunkKey chunk) {
        Objects.requireNonNull(chunk, "chunk");

        final Claim claim = claims.get(chunk);
        if (claim == null) {
            return Outcome.denied(ChangeDenial.CHUNK_NOT_CLAIMED);
        }
        if (claim.kind() == ClaimKind.HOMEBLOCK && claims.size() > 1) {
            return Outcome.denied(ChangeDenial.HOMEBLOCK_MUST_BE_UNCLAIMED_LAST);
        }

        final Map<ChunkKey, Claim> updated = new LinkedHashMap<>(claims);
        updated.remove(chunk);
        if (!allReachableFromAnchors(updated)) {
            return Outcome.denied(ChangeDenial.UNCLAIM_WOULD_DISCONNECT);
        }

        return Outcome.applied(
                new TownClaims(town, updated),
                new DomainEvent.ChunkUnclaimed(town, chunk.toString()));
    }

    /**
     * Moves the homeblock to another chunk the town already owns.
     *
     * <p>The old homeblock becomes ordinary. Both changes happen together: a moment with two
     * homeblocks, or none, would make {@link #unclaim} refuse or permit the wrong thing.</p>
     */
    public Outcome<TownClaims> moveHomeblock(final ChunkKey chunk) {
        Objects.requireNonNull(chunk, "chunk");

        final Claim target = claims.get(chunk);
        if (target == null) {
            return Outcome.denied(ChangeDenial.CHUNK_NOT_CLAIMED);
        }
        if (target.kind() == ClaimKind.HOMEBLOCK) {
            return Outcome.denied(ChangeDenial.ALREADY_THE_HOMEBLOCK);
        }

        final Map<ChunkKey, Claim> updated = new LinkedHashMap<>(claims);
        homeblock().ifPresent(previous ->
                updated.put(previous.chunk(), previous.as(ClaimKind.ORDINARY)));
        updated.put(chunk, target.as(ClaimKind.HOMEBLOCK));

        // The old homeblock stops being an anchor, so a town whose outposts hung off it could be
        // severed by the move. Checked here rather than discovered on the next unclaim.
        if (!allReachableFromAnchors(updated)) {
            return Outcome.denied(ChangeDenial.UNCLAIM_WOULD_DISCONNECT);
        }

        return Outcome.applied(
                new TownClaims(town, updated),
                new DomainEvent.HomeblockMoved(town, chunk.toString()));
    }

    /**
     * What claiming a chunk would do, without doing it.
     *
     * <p>Exists so a player sees the answer before paying for it. The command layer runs this,
     * shows it, and only then calls {@link #claim}.</p>
     */
    public ClaimPreview previewClaim(final ChunkKey chunk, final ClaimKind kind) {
        final Outcome<TownClaims> attempt = claim(chunk, kind, Instant.EPOCH);
        return new ClaimPreview(
                chunk, kind, attempt.wasApplied(), attempt.denial().orElse(null),
                claims.size(), attempt.wasApplied() ? claims.size() + 1 : claims.size(),
                touchesTown(chunk));
    }

    /** What unclaiming a chunk would do, without doing it. */
    public ClaimPreview previewUnclaim(final ChunkKey chunk) {
        final Outcome<TownClaims> attempt = unclaim(chunk);
        final ClaimKind kind = Optional.ofNullable(claims.get(chunk))
                .map(Claim::kind).orElse(ClaimKind.ORDINARY);
        return new ClaimPreview(
                chunk, kind, attempt.wasApplied(), attempt.denial().orElse(null),
                claims.size(), attempt.wasApplied() ? claims.size() - 1 : claims.size(),
                touchesTown(chunk));
    }

    // --- reading -----------------------------------------------------------------------------

    public TownId town() {
        return town;
    }

    public Optional<Claim> at(final ChunkKey chunk) {
        return chunk == null ? Optional.empty() : Optional.ofNullable(claims.get(chunk));
    }

    public boolean owns(final ChunkKey chunk) {
        return chunk != null && claims.containsKey(chunk);
    }

    public Optional<Claim> homeblock() {
        return claims.values().stream()
                .filter(claim -> claim.kind() == ClaimKind.HOMEBLOCK)
                .findFirst();
    }

    public List<Claim> all() {
        return List.copyOf(claims.values());
    }

    public List<Claim> outposts() {
        return claims.values().stream().filter(claim -> claim.kind() == ClaimKind.OUTPOST).toList();
    }

    public int size() {
        return claims.size();
    }

    public boolean isEmpty() {
        return claims.isEmpty();
    }

    /** Whether the chunk shares an edge with any claim this town already holds. */
    public boolean touchesTown(final ChunkKey chunk) {
        if (chunk == null) {
            return false;
        }
        for (final ChunkKey owned : claims.keySet()) {
            if (owned.isAdjacentTo(chunk)) {
                return true;
            }
        }
        return false;
    }

    // --- connectivity ------------------------------------------------------------------------

    /**
     * Whether every claim can be walked to from an anchor through the town's own land.
     *
     * <p>A breadth-first sweep from every homeblock and outpost at once. Linear in the number of
     * claims, which is the right cost for an operation a player performs occasionally — the
     * per-block protection lookup is a different structure entirely and never runs this.</p>
     *
     * <p>Embassies are excluded from the requirement: they sit inside somebody else's territory by
     * definition and could never touch their owner's land.</p>
     */
    private static boolean allReachableFromAnchors(final Map<ChunkKey, Claim> claims) {
        if (claims.isEmpty()) {
            return true;
        }

        final Set<ChunkKey> reached = new LinkedHashSet<>();
        final Deque<ChunkKey> queue = new ArrayDeque<>();
        for (final Claim claim : claims.values()) {
            if (claim.anchorsConnectivity()) {
                reached.add(claim.chunk());
                queue.add(claim.chunk());
            }
        }

        while (!queue.isEmpty()) {
            final ChunkKey current = queue.removeFirst();
            for (final ChunkKey neighbour : orthogonalNeighbours(current)) {
                if (claims.containsKey(neighbour) && reached.add(neighbour)) {
                    queue.addLast(neighbour);
                }
            }
        }

        for (final Claim claim : claims.values()) {
            if (claim.kind() != ClaimKind.EMBASSY && !reached.contains(claim.chunk())) {
                return false;
            }
        }
        return true;
    }

    private static List<ChunkKey> orthogonalNeighbours(final ChunkKey chunk) {
        // Orthogonal only. Diagonally touching claims look connected on a map but share no edge,
        // and a town joined at a corner cannot be walked across without leaving its own land.
        final List<ChunkKey> neighbours = new ArrayList<>(4);
        neighbours.add(chunk.offset(1, 0));
        neighbours.add(chunk.offset(-1, 0));
        neighbours.add(chunk.offset(0, 1));
        neighbours.add(chunk.offset(0, -1));
        return neighbours;
    }

    @Override
    public String toString() {
        return "TownClaims[" + town + ", " + claims.size() + " chunk(s)]";
    }
}
