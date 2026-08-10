package net.riftbreaker.rifttowny.paper;

import net.riftbreaker.rifttowny.api.ApiVersion;
import net.riftbreaker.rifttowny.api.RiftTownyApi;
import net.riftbreaker.rifttowny.api.capability.CapabilityRegistry;
import net.riftbreaker.rifttowny.api.scheduler.RiftScheduler;

import java.util.Objects;

/**
 * The API object handed to sibling plugins.
 *
 * <p>Holds nothing mutable of its own. Everything it exposes is either immutable or a service that
 * performs its own permission checks, so a consumer cannot use the API to bypass a rule.</p>
 */
final class RiftTownyApiImpl implements RiftTownyApi {

    private final CapabilityRegistry capabilities;
    private final RiftScheduler scheduler;

    RiftTownyApiImpl(final CapabilityRegistry capabilities, final RiftScheduler scheduler) {
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    @Override
    public ApiVersion apiVersion() {
        return ApiVersion.CURRENT;
    }

    @Override
    public CapabilityRegistry capabilities() {
        return capabilities;
    }

    @Override
    public RiftScheduler scheduler() {
        return scheduler;
    }
}
