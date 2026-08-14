package net.riftbreaker.rifttowny.domain.directory;

import java.util.Comparator;
import java.util.Locale;
import java.util.Optional;

/**
 * How a listing is ordered.
 *
 * <p>Every order is total. An order that leaves ties unresolved would let two towns swap places
 * between one page and the next, which shows a player the same town twice and hides another
 * entirely — the classic unstable-paging bug, and one that only appears on a server large enough to
 * need pages.</p>
 */
public enum CivicSort {

    /** Alphabetical, case-insensitively, which is the only order a player can predict. */
    NAME(Comparator.comparing(summary -> summary.name().toLowerCase(Locale.ROOT))),

    /** Most people first. */
    RESIDENTS(Comparator.comparingInt(CivicSummary::residents).reversed()),

    /** Most land first. */
    LAND(Comparator.comparingInt(CivicSummary::chunks).reversed()),

    /** Oldest first, so the founding towns of a server head the list. */
    AGE(Comparator.comparing(CivicSummary::foundedAt));

    private final Comparator<CivicSummary> order;

    CivicSort(final Comparator<CivicSummary> order) {
        // Name breaks every tie, and name is unique, so the whole order is total.
        this.order = order.thenComparing(summary -> summary.name().toLowerCase(Locale.ROOT));
    }

    public Comparator<CivicSummary> order() {
        return order;
    }

    /** Reads what the player typed, or empty. Case and the plural do not matter. */
    public static Optional<CivicSort> parse(final String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "name", "names", "alphabetical", "a-z" -> Optional.of(NAME);
            case "residents", "resident", "people", "population", "size" -> Optional.of(RESIDENTS);
            case "land", "chunks", "claims", "territory", "area" -> Optional.of(LAND);
            case "age", "oldest", "founded", "created" -> Optional.of(AGE);
            default -> Optional.empty();
        };
    }

    /** What to offer in tab completion, and what to name in a usage message. */
    public static String options() {
        return "name, residents, land, age";
    }
}
