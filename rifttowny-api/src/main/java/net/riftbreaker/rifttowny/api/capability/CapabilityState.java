package net.riftbreaker.rifttowny.api.capability;

/**
 * How far a capability actually got.
 *
 * <p>{@link #PRESENT_UNVERIFIED} exists because "the plugin is installed" and "we successfully
 * bound to its API" are different facts, and conflating them is how a startup log ends up claiming
 * an integration works when a version mismatch has quietly broken it.</p>
 */
public enum CapabilityState {

    /** The plugin is not installed. Normal, not an error. */
    ABSENT(false),

    /** The plugin is installed but its API has not been exercised yet. Not usable. */
    PRESENT_UNVERIFIED(false),

    /** The adapter resolved against the real API and is usable. */
    ACTIVE(true),

    /** The plugin is installed but binding failed — usually a version mismatch. Cause is recorded. */
    FAILED(false),

    /**
     * The upstream API RiftTowny needs does not exist yet.
     *
     * <p>Distinct from {@link #FAILED}: nothing is broken, the feature simply cannot be built until
     * the other plugin gains the contract. The blocker is named in the status detail.</p>
     */
    BLOCKED(false),

    /** Turned off in configuration despite being available. */
    DISABLED(false);

    private final boolean usable;

    CapabilityState(final boolean usable) {
        this.usable = usable;
    }

    /** Whether code may actually call this integration. Only {@link #ACTIVE} is usable. */
    public boolean usable() {
        return usable;
    }
}
