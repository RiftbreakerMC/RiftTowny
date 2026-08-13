package net.riftbreaker.rifttowny.domain.territory;

import java.util.Locale;
import java.util.Optional;

/**
 * What a chunk inside a town is <em>for</em>.
 *
 * <p>Deliberately separate from {@link ClaimKind}, which is about the town's shape — where the
 * homeblock is, which claims anchor connectivity. A plot type is about use, and changing one must
 * never change whether the town is still contiguous. Mixing them would mean marking a chunk as a
 * market could accidentally sever the town.</p>
 *
 * <p>The constant name is the stored value, so renaming one is a migration. New types may be added
 * freely: a plot whose type this version does not know reads as {@link #DEFAULT}, which is the only
 * safe way to be wrong about what a chunk is for.</p>
 *
 * <p>Most types carry no behaviour yet. They are recorded, shown and configurable, and the modules
 * that give them meaning — {@code RT-MOD-SHOP}, {@code RT-MOD-SPAWNER}, {@code RT-MOD-JUSTICE} —
 * read them when they land. That is stated plainly rather than implied, because a server that marks
 * a plot as a jail today should not expect anybody to be held in it.</p>
 */
public enum PlotType {

    /** An ordinary plot. Whatever the town's own rules say. */
    DEFAULT,

    /** Housing, sold or let to a resident. */
    RESIDENTIAL,

    /** Trade. {@code RT-MOD-SHOP} reads this. */
    SHOP,

    /** Crops and animals. */
    FARM,

    /** Fighting is expected here, and PvP is normally opened on it. */
    ARENA,

    /** Held by another town inside this one's territory. */
    EMBASSY,

    /** Left as it is, outside the town's usual protection. */
    WILDS,

    /** Where visitors sleep. */
    INN,

    /** The town's treasury. {@code RT-MOD-BANK} reads this. */
    BANK,

    /** Detention. {@code RT-MOD-JUSTICE} reads this. */
    JAIL;

    /** The stored form. Explicit so a rename is a visible migration rather than silent corruption. */
    public String storageValue() {
        return name();
    }

    /**
     * Parses a stored or player-supplied value.
     *
     * <p>{@link #DEFAULT} rather than empty for an unknown name: a type added in a later version
     * must not stop a plot loading, and "an ordinary plot" is the reading that grants least.</p>
     */
    public static PlotType fromStorage(final String raw) {
        return parse(raw).orElse(DEFAULT);
    }

    /** The same, but empty for an unknown name — for a command, where a typo is a message. */
    public static Optional<PlotType> parse(final String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        final String normalised = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        for (final PlotType type : values()) {
            if (type.name().equals(normalised)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}
