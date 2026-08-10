package net.riftbreaker.rifttowny.domain.org;

import java.util.Objects;
import java.util.UUID;

/**
 * A player's identity, which is their Mojang UUID.
 *
 * <p>Deliberately not an {@link OrganisationId}: a resident is a member of organisations, never one
 * itself. Keeping them separate types is what stops a resident id reaching a role or bank method
 * that expects an organisation.</p>
 */
public record ResidentId(UUID value) {

    public ResidentId {
        Objects.requireNonNull(value, "value");
    }

    public static ResidentId of(final UUID playerId) {
        return new ResidentId(playerId);
    }

    public static ResidentId parse(final String raw) {
        return new ResidentId(UUID.fromString(raw));
    }

    @Override
    public String toString() {
        return "resident:" + value;
    }
}
