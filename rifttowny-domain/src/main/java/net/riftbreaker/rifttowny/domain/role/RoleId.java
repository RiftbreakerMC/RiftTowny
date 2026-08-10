package net.riftbreaker.rifttowny.domain.role;

import java.util.Objects;
import java.util.UUID;

/**
 * A role's stable identity.
 *
 * <p>Typed for the same reason organisation ids are: a role id and a resident id are both 36
 * characters of hex, and swapping them would attach a permission set to a player instead of a
 * role.</p>
 */
public record RoleId(UUID value) {

    public RoleId {
        Objects.requireNonNull(value, "value");
    }

    public static RoleId random() {
        return new RoleId(UUID.randomUUID());
    }

    public static RoleId parse(final String raw) {
        return new RoleId(UUID.fromString(raw));
    }

    @Override
    public String toString() {
        return "role:" + value;
    }
}
