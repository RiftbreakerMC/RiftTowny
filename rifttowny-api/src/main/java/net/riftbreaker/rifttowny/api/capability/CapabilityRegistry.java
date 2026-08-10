package net.riftbreaker.rifttowny.api.capability;

import java.util.List;
import java.util.Optional;

/** Read-only view of what RiftTowny actually managed to integrate with. */
public interface CapabilityRegistry {

    /** The status of one capability. Never null: an unregistered capability reports {@code ABSENT}. */
    CapabilityStatus status(Capability capability);

    /** Every capability's status, in declaration order, for {@code /rifttowny status}. */
    List<CapabilityStatus> statuses();

    /** Whether this capability is {@link CapabilityState#ACTIVE} and may be called. */
    default boolean isActive(final Capability capability) {
        return status(capability).usable();
    }

    /**
     * The bound service object for an active capability.
     *
     * <p>Empty unless the capability is {@code ACTIVE}, so a caller cannot accidentally use a
     * half-bound integration.</p>
     */
    <T> Optional<T> service(Capability capability, Class<T> type);
}
