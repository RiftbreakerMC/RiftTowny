package net.riftbreaker.rifttowny.domain.org;

/**
 * Which kind of organisation an ID, role or setting belongs to.
 *
 * <p>Persisted as the {@code scope} column on {@code rt_role} and
 * {@code rt_organisation_currency}, so the names here are part of the schema. Renaming a constant
 * is a migration.</p>
 */
public enum OrganisationScope {

    TOWN,
    NATION;

    /** The stored form. Explicit rather than {@code name()} so a rename cannot silently break rows. */
    public String storageValue() {
        return switch (this) {
            case TOWN -> "TOWN";
            case NATION -> "NATION";
        };
    }

    public static OrganisationScope fromStorage(final String value) {
        return switch (value == null ? "" : value) {
            case "TOWN" -> TOWN;
            case "NATION" -> NATION;
            default -> throw new IllegalArgumentException("Unknown organisation scope: " + value);
        };
    }
}
