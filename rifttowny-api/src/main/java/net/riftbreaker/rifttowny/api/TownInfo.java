package net.riftbreaker.rifttowny.api;

import java.util.Objects;
import java.util.UUID;

/** Immutable public view of a town, safe to retain outside RiftTowny's internal model. */
public record TownInfo(UUID id, String name) {
    public TownInfo {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
    }

    /** A rename changes presentation, not the town's identity. */
    @Override
    public boolean equals(final Object other) {
        return other instanceof TownInfo town && id.equals(town.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
