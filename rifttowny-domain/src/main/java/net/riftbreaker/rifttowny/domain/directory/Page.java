package net.riftbreaker.rifttowny.domain.directory;

import java.util.List;
import java.util.Objects;

/**
 * One page of a listing.
 *
 * <p>Paging lives here rather than in the command layer because it is the part of a listing that is
 * worth getting wrong only once. A server with four hundred towns cannot send them all to a chat
 * window — the client drops the overflow, so the player is shown a truncated list with no way to
 * know it was truncated, which is worse than showing them nothing.</p>
 *
 * <p><strong>Pages are numbered from one</strong>, because that is what a player types. Converting
 * at the boundary means the off-by-one lives in one method rather than at every call site.</p>
 *
 * @param items the slice, which is empty for a page past the end rather than an error
 * @param number the 1-based page number actually served, clamped into range
 * @param size how many items a full page holds
 * @param total how many items exist in the whole listing
 */
public record Page<T>(List<T> items, int number, int size, int total) {

    public Page {
        Objects.requireNonNull(items, "items");
        items = List.copyOf(items);
        if (size < 1) {
            throw new IllegalArgumentException("a page holds at least one item, not " + size);
        }
    }

    /**
     * Cuts a page out of a listing.
     *
     * <p>A page number past the end is clamped to the last page rather than refused. A player who
     * types {@code 9} on a three-page list has made a typo, and showing them the last page answers
     * what they meant; an error message would make them count.</p>
     */
    public static <T> Page<T> of(final List<T> all, final int requested, final int size) {
        Objects.requireNonNull(all, "all");
        if (size < 1) {
            throw new IllegalArgumentException("a page holds at least one item, not " + size);
        }
        if (all.isEmpty()) {
            return new Page<>(List.of(), 1, size, 0);
        }
        final int pages = (all.size() + size - 1) / size;
        final int number = Math.min(Math.max(1, requested), pages);
        final int from = (number - 1) * size;
        return new Page<>(all.subList(from, Math.min(all.size(), from + size)), number, size, all.size());
    }

    /** How many pages the whole listing needs. One for an empty listing: "page 1 of 1, nothing here". */
    public int pages() {
        return total == 0 ? 1 : (total + size - 1) / size;
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    public boolean hasNext() {
        return number < pages();
    }

    public boolean hasPrevious() {
        return number > 1;
    }

    /** The 1-based index of the first item on this page, for a numbered listing. */
    public int firstIndex() {
        return (number - 1) * size + 1;
    }
}
