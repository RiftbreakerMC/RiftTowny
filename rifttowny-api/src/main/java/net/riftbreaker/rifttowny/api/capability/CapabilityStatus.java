package net.riftbreaker.rifttowny.api.capability;

import java.util.Objects;
import java.util.Optional;

/**
 * A capability's state plus why it is in that state.
 *
 * @param capability which integration
 * @param state how far it got
 * @param detail human-readable reason; the version bound to, or the failure, or the blocker
 * @param failure the exception that caused {@link CapabilityState#FAILED}, if any
 */
public record CapabilityStatus(
        Capability capability,
        CapabilityState state,
        String detail,
        Throwable failure
) {

    public CapabilityStatus {
        Objects.requireNonNull(capability, "capability");
        Objects.requireNonNull(state, "state");
        detail = detail == null ? "" : detail;
    }

    public static CapabilityStatus absent(final Capability capability) {
        return new CapabilityStatus(capability, CapabilityState.ABSENT, "not installed", null);
    }

    public static CapabilityStatus active(final Capability capability, final String detail) {
        return new CapabilityStatus(capability, CapabilityState.ACTIVE, detail, null);
    }

    public static CapabilityStatus failed(final Capability capability, final Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        return new CapabilityStatus(capability, CapabilityState.FAILED, describe(failure), failure);
    }

    public static CapabilityStatus blocked(final Capability capability, final String blocker) {
        return new CapabilityStatus(capability, CapabilityState.BLOCKED, blocker, null);
    }

    public static CapabilityStatus disabled(final Capability capability) {
        return new CapabilityStatus(capability, CapabilityState.DISABLED, "disabled in configuration", null);
    }

    public Optional<Throwable> failureCause() {
        return Optional.ofNullable(failure);
    }

    public boolean usable() {
        return state.usable();
    }

    /**
     * The root cause's message, or its class name when it has none.
     *
     * <p>A {@code NoSuchMethodError} from a version mismatch usually carries the signature as its
     * message, which is exactly what an operator needs; an unwrapped {@code InvocationTargetException}
     * carries nothing useful, which is why this unwraps first.</p>
     */
    private static String describe(final Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        final String message = current.getMessage();
        return message == null || message.isBlank()
                ? current.getClass().getSimpleName()
                : current.getClass().getSimpleName() + ": " + message;
    }
}
