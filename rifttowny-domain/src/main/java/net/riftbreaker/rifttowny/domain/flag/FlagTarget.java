package net.riftbreaker.rifttowny.domain.flag;

import net.riftbreaker.rifttowny.api.ChunkKey;
import net.riftbreaker.rifttowny.domain.org.OrganisationId;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * What an override applies to.
 *
 * <p>A {@link FlagSource} says which layer an opinion belongs to; this says which <em>one</em> thing
 * in that layer holds it — this chunk, this town, this world, or the whole server. The two together
 * are the identity of a stored override.</p>
 *
 * <p>The storage key is derived here rather than assembled at the call site. Four scopes with four
 * different kinds of identifier, written by a service and read back by a loader, is exactly the
 * shape that ends with a town's overrides stored under a key nothing ever looks up.</p>
 */
public final class FlagTarget {

    /** The key every administrative override shares. */
    private static final String GLOBAL = "*";

    private final FlagSource source;
    private final String key;

    private FlagTarget(final FlagSource source, final String key) {
        this.source = source;
        this.key = key;
    }

    /** Every position on the server. Only an operator sets these. */
    public static FlagTarget admin() {
        return new FlagTarget(FlagSource.ADMIN, GLOBAL);
    }

    /** One world's defaults. */
    public static FlagTarget world(final UUID worldId) {
        Objects.requireNonNull(worldId, "worldId");
        return new FlagTarget(FlagSource.WORLD, worldId.toString());
    }

    /** One claimed chunk. */
    public static FlagTarget claim(final ChunkKey chunk) {
        Objects.requireNonNull(chunk, "chunk");
        return new FlagTarget(
                FlagSource.CLAIM,
                chunk.worldId() + ":" + chunk.chunkX() + ':' + chunk.chunkZ());
    }

    /** One town or nation, applying across all of its territory. */
    public static FlagTarget organisation(final OrganisationId organisation) {
        Objects.requireNonNull(organisation, "organisation");
        return new FlagTarget(FlagSource.ORGANISATION, organisation.value().toString());
    }

    /**
     * Rebuilds a target from storage.
     *
     * <p>Empty rather than throwing for an unusable row: a scope removed in a later version, or a
     * key left behind by a hand-edited database, must not stop the whole override set loading. A
     * refused row simply has no opinion, which is the state the resolver already handles.</p>
     */
    public static Optional<FlagTarget> restore(final String scope, final String key) {
        if (scope == null || key == null || key.isBlank()) {
            return Optional.empty();
        }
        return FlagSource.parse(scope).map(source -> new FlagTarget(source, key));
    }

    public FlagSource source() {
        return source;
    }

    /** The stored key. Stable: changing how one is built is a migration. */
    public String key() {
        return key;
    }

    /** Whether this target is the whole server rather than one thing in it. */
    public boolean isGlobal() {
        return GLOBAL.equals(key);
    }

    @Override
    public boolean equals(final Object other) {
        return other instanceof FlagTarget target
                && source == target.source
                && key.equals(target.key);
    }

    @Override
    public int hashCode() {
        return 31 * source.hashCode() + key.hashCode();
    }

    @Override
    public String toString() {
        return source + "[" + key + ']';
    }
}
