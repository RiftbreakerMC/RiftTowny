package net.riftbreaker.rifttowny.domain.flag;

import net.riftbreaker.rifttowny.api.ChunkKey;
import net.riftbreaker.rifttowny.domain.org.TownId;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Every configured flag override, in memory, assembled into the layers a resolution needs.
 *
 * <p>The third of the caches a protection check reads, beside the territory index and the civic
 * cache, and for the same reason: the check runs inside a block-break listener and cannot wait for a
 * query.</p>
 *
 * <p><strong>Stored as built {@link FlagSettings} rather than as rows.</strong> A resolution asks
 * one question of each layer, and rebuilding a settings object per question would allocate on the
 * hottest path in the plugin. Overrides change when somebody runs a command; resolutions happen on
 * every block a player touches, so the cost belongs on the writing side.</p>
 *
 * <p>Thread-safe. Writes replace one target's settings whole, so a reader either sees the old
 * opinion or the new one and never a half-applied set.</p>
 */
public final class FlagOverrides implements FlagSettingsSource {

    private final Map<String, FlagSettings> byTarget = new ConcurrentHashMap<>();
    private final Map<String, List<FlagOverride>> rowsByTarget = new ConcurrentHashMap<>();
    private final AtomicLong generation = new AtomicLong();

    public static FlagOverrides empty() {
        return new FlagOverrides();
    }

    // --- resolving -----------------------------------------------------------------------------

    /**
     * The layers that apply at a position, in resolution order.
     *
     * <p>Area and war are absent: neither is stored here. An area's overrides arrive with
     * {@code RT-CORE-AREA} and a war supplies its own layer for as long as it lasts.</p>
     */
    @Override
    public List<FlagResolver.FlagLayer> layersFor(final ChunkKey chunk, final TownId owner) {
        return FlagResolver.layers(
                settingsFor(FlagTarget.admin()),
                null,
                null,
                chunk == null ? null : settingsFor(FlagTarget.claim(chunk)),
                owner == null ? null : settingsFor(FlagTarget.organisation(owner)),
                chunk == null ? null : settingsFor(FlagTarget.world(chunk.worldId())));
    }

    /** One target's opinions, or null when it has none. */
    public FlagSettings settingsFor(final FlagTarget target) {
        return target == null ? null : byTarget.get(key(target));
    }

    // --- writing -------------------------------------------------------------------------------

    /** Replaces everything, as at startup or after a reload. */
    public synchronized void replaceAll(final Collection<FlagOverride> loaded) {
        Objects.requireNonNull(loaded, "loaded");
        final Map<String, List<FlagOverride>> grouped = new HashMap<>();
        for (final FlagOverride override : loaded) {
            grouped.computeIfAbsent(key(override.target()), ignored -> new ArrayList<>())
                    .add(override);
        }
        final Map<String, FlagSettings> rebuilt = new HashMap<>();
        grouped.forEach((target, rows) -> rebuilt.put(target, build(rows)));

        byTarget.keySet().retainAll(rebuilt.keySet());
        byTarget.putAll(rebuilt);
        rowsByTarget.keySet().retainAll(grouped.keySet());
        grouped.forEach((target, rows) -> rowsByTarget.put(target, List.copyOf(rows)));
        generation.incrementAndGet();
    }

    /** Records one override, replacing any existing opinion on the same flag and relationship. */
    public synchronized void apply(final FlagOverride override) {
        Objects.requireNonNull(override, "override");
        final String key = key(override.target());
        final List<FlagOverride> rows = new ArrayList<>(rowsByTarget.getOrDefault(key, List.of()));
        rows.removeIf(existing -> existing.flag() == override.flag()
                && existing.relationship() == override.relationship());
        rows.add(override);
        store(key, rows);
    }

    /** Removes one opinion. Returns whether there was one to remove. */
    public synchronized boolean clear(
            final FlagTarget target, final ProtectionFlag flag, final Relationship relationship) {
        if (target == null || flag == null || relationship == null) {
            return false;
        }
        final String key = key(target);
        final List<FlagOverride> rows = new ArrayList<>(rowsByTarget.getOrDefault(key, List.of()));
        if (!rows.removeIf(existing -> existing.flag() == flag
                && existing.relationship() == relationship)) {
            return false;
        }
        store(key, rows);
        return true;
    }

    /** Removes every opinion a target holds, as when its town is disbanded. */
    public synchronized int clearAll(final FlagTarget target) {
        if (target == null) {
            return 0;
        }
        final String key = key(target);
        final int removed = rowsByTarget.getOrDefault(key, List.of()).size();
        rowsByTarget.remove(key);
        byTarget.remove(key);
        generation.incrementAndGet();
        return removed;
    }

    // --- reading -------------------------------------------------------------------------------

    /** Everything one target holds, for a listing. */
    public List<FlagOverride> of(final FlagTarget target) {
        return target == null ? List.of() : rowsByTarget.getOrDefault(key(target), List.of());
    }

    /** What one layer thinks about one question, for an explanation. */
    public Optional<Boolean> opinionOf(
            final FlagTarget target, final ProtectionFlag flag, final Relationship relationship) {
        final FlagSettings settings = settingsFor(target);
        return settings == null ? Optional.empty() : settings.opinionOn(flag, relationship);
    }

    public int size() {
        return rowsByTarget.values().stream().mapToInt(List::size).sum();
    }

    public int targets() {
        return rowsByTarget.size();
    }

    /** Changes on every mutation, so a downstream view can notice without being told. */
    public long generation() {
        return generation.get();
    }

    public String describe() {
        return size() + " override(s) across " + targets() + " target(s)";
    }

    // --- internals -----------------------------------------------------------------------------

    private void store(final String key, final List<FlagOverride> rows) {
        if (rows.isEmpty()) {
            rowsByTarget.remove(key);
            byTarget.remove(key);
        } else {
            rowsByTarget.put(key, List.copyOf(rows));
            byTarget.put(key, build(rows));
        }
        generation.incrementAndGet();
    }

    private static FlagSettings build(final List<FlagOverride> rows) {
        final FlagSettings.Builder builder = FlagSettings.builder();
        for (final FlagOverride override : rows) {
            builder.set(override.flag(), override.relationship(), override.allowed());
        }
        return builder.build();
    }

    /**
     * The map key.
     *
     * <p>The source is included as well as the target's own key so two scopes cannot collide — a
     * world and an organisation are both a bare UUID, and without this a world override would answer
     * for a town with the same identifier. That cannot happen today, and relying on it not happening
     * is not a reason to make it possible.</p>
     */
    private static String key(final FlagTarget target) {
        return target.source() + "|" + target.key();
    }
}
