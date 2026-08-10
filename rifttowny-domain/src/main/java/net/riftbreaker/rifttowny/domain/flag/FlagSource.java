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
    BUILT_IN
}
