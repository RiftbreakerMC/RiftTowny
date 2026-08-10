package net.riftbreaker.rifttowny.domain.org;

import java.util.Objects;
import java.util.UUID;

/** A town's stable identity. */
public record TownId(UUID value) implements OrganisationId {

    public TownId {
        Objects.requireNonNull(value, "value");
    }

    public static TownId random() {
        return new TownId(UUID.randomUUID());
    }

    public static TownId parse(final String raw) {
        return new TownId(UUID.fromString(raw));
    }

    @Override
    public OrganisationScope scope() {
        return OrganisationScope.TOWN;
    }

    @Override
    public String toString() {
        return "town:" + value;
    }
}
