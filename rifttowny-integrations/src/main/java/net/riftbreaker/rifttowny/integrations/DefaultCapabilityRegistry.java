package net.riftbreaker.rifttowny.integrations;

import net.riftbreaker.rifttowny.api.capability.Capability;
import net.riftbreaker.rifttowny.api.capability.CapabilityRegistry;
import net.riftbreaker.rifttowny.api.capability.CapabilityState;
import net.riftbreaker.rifttowny.api.capability.CapabilityStatus;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Tracks what RiftTowny actually integrated with.
 *
 * <p>The point of this class is that one broken integration cannot take anything else down. Every
 * adapter is bound inside its own guard, and a failure is recorded against that capability alone —
 * a version mismatch in RiftShop must not stop towns being claimable.</p>
 *
 * <p>It matters just as much that the registry never over-reports. A startup line saying
 * "RiftEco: ACTIVE" has to mean the adapter resolved against the real API, not merely that the
 * plugin was on the server; otherwise an operator debugging a missing feature is being lied to by
 * their own logs.</p>
 *
 * <p>Contains no Bukkit types: plugin presence arrives as a predicate. That is what makes the
 * degradation behaviour testable rather than something we hope works.</p>
 */
public final class DefaultCapabilityRegistry implements CapabilityRegistry {

    private final Map<Capability, CapabilityStatus> statuses = new EnumMap<>(Capability.class);
    private final Map<Capability, Object> services = new EnumMap<>(Capability.class);
    private final Consumer<String> logger;

    /**
     * @param logger where startup lines go; normally the plugin logger's info method
     */
    public DefaultCapabilityRegistry(final Consumer<String> logger) {
        this.logger = Objects.requireNonNull(logger, "logger");
        for (final Capability capability : Capability.values()) {
            // Unimplemented, not absent. Absent is a claim that the plugin is not installed, and
            // seeding it here made that claim about every capability before anything had looked.
            // register() records absent properly, once it has actually asked.
            statuses.put(capability, CapabilityStatus.unimplemented(capability));
        }
    }

    /**
     * Attempts to bind one adapter.
     *
     * <p>Never throws. Every outcome — plugin absent, service unavailable, binding failed, bound —
     * ends as a recorded status.</p>
     *
     * @param pluginEnabled answers whether a named plugin is installed and enabled
     * @return true if the capability is now usable
     */
    public boolean register(final IntegrationAdapter adapter, final Predicate<String> pluginEnabled) {
        Objects.requireNonNull(adapter, "adapter");
        Objects.requireNonNull(pluginEnabled, "pluginEnabled");

        final Capability capability = adapter.capability();
        if (!pluginEnabled.test(capability.pluginName())) {
            record(CapabilityStatus.absent(capability));
            return false;
        }

        try {
            final Object bound = adapter.bind();
            if (bound == null) {
                services.remove(capability);
                record(new CapabilityStatus(capability, CapabilityState.PRESENT_UNVERIFIED,
                        "installed, but its service was not available", null));
                return false;
            }
            services.put(capability, bound);
            record(CapabilityStatus.active(capability, adapter.describe(bound)));
            return true;
        } catch (final LinkageError | Exception failure) {
            // LinkageError is caught deliberately, not defensively: NoClassDefFoundError and
            // NoSuchMethodError are exactly what a version mismatch throws, and they are Errors
            // rather than Exceptions. Letting one escape would abort the entire plugin enable
            // because a single optional dependency was the wrong version.
            services.remove(capability);
            record(CapabilityStatus.failed(capability, failure));
            return false;
        }
    }

    /**
     * Records a capability as blocked upstream.
     *
     * <p>Distinct from a failure: nothing is broken, the API RiftTowny needs simply does not exist
     * yet. {@link Capability#DISCORD_CHANNEL_PROVISIONING} is the current example.</p>
     */
    public void markBlocked(final Capability capability, final String blocker) {
        record(CapabilityStatus.blocked(capability, blocker));
    }

    /** Records a capability as switched off in configuration despite being available. */
    public void markDisabled(final Capability capability) {
        record(CapabilityStatus.disabled(capability));
    }

    @Override
    public CapabilityStatus status(final Capability capability) {
        Objects.requireNonNull(capability, "capability");
        return statuses.getOrDefault(capability, CapabilityStatus.unimplemented(capability));
    }

    @Override
    public List<CapabilityStatus> statuses() {
        return List.copyOf(statuses.values());
    }

    @Override
    public <T> Optional<T> service(final Capability capability, final Class<T> type) {
        Objects.requireNonNull(capability, "capability");
        Objects.requireNonNull(type, "type");
        if (!status(capability).usable()) {
            return Optional.empty();
        }
        final Object bound = services.get(capability);
        return type.isInstance(bound) ? Optional.of(type.cast(bound)) : Optional.empty();
    }

    /** One line per capability, worst news first, for the startup log and {@code /rifttowny status}. */
    public List<String> summary() {
        return statuses.values().stream()
                .sorted((left, right) -> {
                    final int bySeverity = Integer.compare(severity(right.state()), severity(left.state()));
                    return bySeverity != 0
                            ? bySeverity
                            : left.capability().name().compareTo(right.capability().name());
                })
                .map(status -> String.format("%-34s %-18s %s",
                        status.capability().name(), status.state(), status.detail()))
                .toList();
    }

    private static int severity(final CapabilityState state) {
        return switch (state) {
            case FAILED -> 4;
            case BLOCKED -> 3;
            case PRESENT_UNVERIFIED -> 2;
            case ACTIVE -> 1;
            // Unimplemented sits with the non-problems: nothing is wrong, the adapter is simply
            // not written. Ranking it as a fault would fill the report with alarm about work
            // that has not started.
            case DISABLED, ABSENT, UNIMPLEMENTED -> 0;
        };
    }

    private void record(final CapabilityStatus status) {
        statuses.put(status.capability(), status);
        if (status.state() == CapabilityState.FAILED) {
            logger.accept("Integration " + status.capability() + " failed to bind: " + status.detail()
                    + ". Only features that need it are affected.");
        } else if (status.state() == CapabilityState.ACTIVE) {
            logger.accept("Integration " + status.capability() + " active (" + status.detail() + ").");
        }
    }
}
