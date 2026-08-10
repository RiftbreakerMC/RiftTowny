package net.riftbreaker.rifttowny.domain.org;

import java.util.Objects;
import java.util.UUID;

/** A nation's stable identity. */
public record NationId(UUID value) implements OrganisationId {

    public NationId {
        Objects.requireNonNull(value, "value");
    }

    public static NationId random() {
        return new NationId(UUID.randomUUID());
    }

    public static NationId parse(final String raw) {
        return new NationId(UUID.fromString(raw));
    }

    @Override
    public OrganisationScope scope() {
        return OrganisationScope.NATION;
    }

    @Override
    public String toString() {
        return "nation:" + value;
    }
}
