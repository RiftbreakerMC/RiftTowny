package net.riftbreaker.rifttowny.api;

import net.riftbreaker.rifttowny.api.capability.CapabilityRegistry;
import net.riftbreaker.rifttowny.api.scheduler.RiftScheduler;

/**
 * The entry point sibling plugins compile against.
 *
 * <p>Obtain it through {@link RiftTownyProvider}. Everything reachable from here is either a
 * read-only view or a service method that performs its own permission and validation checks —
 * mutable internal entities are never exposed, so holding an API object can never be a way around
 * a permission check.</p>
 */
public interface RiftTownyApi {

    /**
     * The API version this build implements.
     *
     * <p>Consumers should call {@link #requireApiVersion(ApiVersion)} at enable time rather than
     * comparing this themselves.</p>
     */
    ApiVersion apiVersion();

    /**
     * Asserts that a consumer built against {@code required} can run against this build.
     *
     * @throws IllegalStateException with both versions named, so the log says what to do about it
     */
    default void requireApiVersion(final ApiVersion required) {
        if (required == null || !required.isSupportedBy(apiVersion())) {
            throw new IllegalStateException(
                    "This plugin needs RiftTowny API " + required + " but RiftTowny provides "
                            + apiVersion() + ". Update the plugin, or RiftTowny.");
        }
    }

    /** What RiftTowny actually integrated with. Ask before assuming an integration is present. */
    CapabilityRegistry capabilities();

    /**
     * The scheduler abstraction.
     *
     * <p>Exposed so an adapter written in a sibling plugin can honour Folia's rules without
     * duplicating the detection logic.</p>
     */
    RiftScheduler scheduler();
}
