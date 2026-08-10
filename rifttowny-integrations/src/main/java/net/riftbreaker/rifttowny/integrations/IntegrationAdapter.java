package net.riftbreaker.rifttowny.integrations;

import net.riftbreaker.rifttowny.api.capability.Capability;

/**
 * One optional dependency, bound to its real API.
 *
 * <p>An adapter is written only after the target plugin's API has actually been read. Where the API
 * does not exist, the capability is registered
 * {@link net.riftbreaker.rifttowny.api.capability.CapabilityState#BLOCKED} instead of an adapter
 * being written against invented method names.</p>
 *
 * <p>{@link #bind()} takes no arguments so an adapter captures whatever it needs — the plugin
 * instance, the server, a configuration section — at construction. That keeps the registry itself
 * free of Bukkit and therefore testable.</p>
 */
public interface IntegrationAdapter {

    /** Which capability this adapter provides. */
    Capability capability();

    /**
     * Binds against the target plugin's real API and returns the service object.
     *
     * <p>Called only after the plugin has been confirmed present and enabled. Implementations
     * should touch the API enough to prove the binding works — resolve a service, read a version —
     * because a constructor that merely stores a reference proves nothing, and would let the
     * registry report {@code ACTIVE} for an integration that fails on first use.</p>
     *
     * @return the bound service, or null if the plugin is present but its service was unavailable
     * @throws Exception on any binding failure; the registry records it and marks this capability
     *         {@code FAILED} without affecting any other adapter
     */
    Object bind() throws Exception;

    /**
     * A short description for {@code /rifttowny status}, ideally naming the bound version.
     *
     * @param bound whatever {@link #bind()} returned
     */
    default String describe(final Object bound) {
        return "bound";
    }
}
