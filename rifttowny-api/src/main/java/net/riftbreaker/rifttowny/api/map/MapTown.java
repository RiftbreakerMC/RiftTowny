package net.riftbreaker.rifttowny.api.map;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Public town identity and its nation affiliation at snapshot time.
 *
 * <p>Names are display labels, never keys. RiftMap must use stable UUIDs when
 * coloring territory or joining town ownership with nations.</p>
 */
public record MapTown(UUID id, String name, Optional<UUID> nationId) {
    public MapTown {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(nationId, "nationId");
    }
}
