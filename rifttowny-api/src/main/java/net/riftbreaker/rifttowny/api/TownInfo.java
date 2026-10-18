package net.riftbreaker.rifttowny.api;

import java.util.Objects;
import java.util.UUID;

/** Immutable public view of a town, safe to retain outside RiftTowny's internal model. */
public record TownInfo(UUID id, String name) {
    public TownInfo {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
    }
}
