package net.riftbreaker.rifttowny.domain.flag;

/**
 * Which layer decided a flag.
 *
 * <p>Carried on every {@link FlagDecision} because "why was I denied" is a support question that
 * arrives daily, and answering it with "protection" satisfies nobody. Declared in resolution order:
 * the first layer with an opinion wins.</p>
 */
public enum FlagSource {

    /**
     * A server administrator's restriction. Always wins.
     *
     * <p>An organisation can never grant itself something an administrator has forbidden, which is
     * the whole reason this layer is first.</p>
     */
    ADMIN,

    /** A war or event override, granted by the subsystem that owns it and time-limited. */
    WAR_OR_EVENT,

    /** A 3D area inside a claim. */
    AREA,

    /** The individual claim. */
    CLAIM,

    /** The owning town or nation's own setting. */
    ORGANISATION,

    /** The world's default. */
    WORLD,

    /** The shipped default, when nothing above had an opinion. */
    BUILT_IN;

    /**
     * Whether an override at this layer can be stored.
     *
     * <p>{@link #WAR_OR_EVENT} and {@link #BUILT_IN} cannot: a war supplies its own layer for as
     * long as it lasts, and the built-in default is code. Storing either would give two places to
     * change one answer.</p>
     */
    public boolean isConfigurable() {
        return this != WAR_OR_EVENT && this != BUILT_IN;
    }

    /**
     * Parses a stored value.
     *
     * <p>Empty rather than throwing for an unknown name: a layer removed in a later version must not
     * stop the override set loading.</p>
     */
    public static java.util.Optional<FlagSource> parse(final String raw) {
        if (raw == null || raw.isBlank()) {
            return java.util.Optional.empty();
        }
        final String normalised = raw.trim().toUpperCase(java.util.Locale.ROOT).replace('-', '_');
        for (final FlagSource source : values()) {
            if (source.name().equals(normalised)) {
                return java.util.Optional.of(source);
            }
        }
        return java.util.Optional.empty();
    }
}
