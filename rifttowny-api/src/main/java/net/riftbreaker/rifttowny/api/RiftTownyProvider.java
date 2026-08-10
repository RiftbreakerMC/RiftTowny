package net.riftbreaker.rifttowny.api;

import java.util.Optional;

/**
 * How a sibling plugin gets hold of {@link RiftTownyApi}.
 *
 * <p>Registration is done by RiftTowny itself during enable and cleared on disable, so a consumer
 * that caches the instance across a plugin reload gets a stale object. Prefer calling
 * {@link #get()} at the point of use over holding a field.</p>
 */
public final class RiftTownyProvider {

    private static volatile RiftTownyApi instance;

    private RiftTownyProvider() {
    }

    /** The running API, or empty when RiftTowny is absent or not yet enabled. */
    public static Optional<RiftTownyApi> get() {
        return Optional.ofNullable(instance);
    }

    /**
     * The running API, or a failure naming the likely cause.
     *
     * <p>Use this from code that has already established RiftTowny is present — for example inside
     * an adapter guarded by a {@code softdepend} check — so a null never travels further.</p>
     */
    public static RiftTownyApi require() {
        final RiftTownyApi current = instance;
        if (current == null) {
            throw new IllegalStateException(
                    "RiftTowny is not enabled. Declare it as a depend or softdepend in plugin.yml, "
                            + "and obtain the API no earlier than your own onEnable.");
        }
        return current;
    }

    /** Called by RiftTowny only. Passing null clears the registration on disable. */
    public static void register(final RiftTownyApi api) {
        instance = api;
    }
}
