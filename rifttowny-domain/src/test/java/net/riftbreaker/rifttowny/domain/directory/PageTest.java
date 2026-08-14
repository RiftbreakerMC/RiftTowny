package net.riftbreaker.rifttowny.domain.directory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Paging.
 *
 * <p>Small enough to look correct and exactly the kind of code that is not. Every case here is one
 * that reaches a player as a listing that skips a row, repeats one, or reports a page count that
 * disagrees with the pages it will actually serve.</p>
 */
class PageTest {

    private static List<String> items(final int count) {
        final List<String> all = new ArrayList<>(count);
        for (int index = 1; index <= count; index++) {
            all.add("item-" + index);
        }
        return all;
    }

    @Test
    @DisplayName("a full listing splits into pages that cover it exactly once")
    void pagesCoverEverything() {
        final List<String> all = items(25);
        final List<String> seen = new ArrayList<>();

        for (int number = 1; number <= 3; number++) {
            seen.addAll(Page.of(all, number, 10).items());
        }

        assertThat(seen).containsExactlyElementsOf(all);
    }

    @Test
    @DisplayName("the last page is short rather than padded")
    void lastPageIsShort() {
        final Page<String> page = Page.of(items(25), 3, 10);

        assertThat(page.items()).hasSize(5);
        assertThat(page.number()).isEqualTo(3);
        assertThat(page.pages()).isEqualTo(3);
        assertThat(page.hasNext()).isFalse();
        assertThat(page.hasPrevious()).isTrue();
    }

    @Test
    @DisplayName("a page past the end serves the last page rather than refusing")
    void pastTheEndClampsToTheLast() {
        final Page<String> page = Page.of(items(25), 99, 10);

        assertThat(page.number()).isEqualTo(3);
        assertThat(page.items()).containsExactly("item-21", "item-22", "item-23", "item-24", "item-25");
    }

    @Test
    @DisplayName("page zero and negative pages serve the first page")
    void beforeTheStartClampsToTheFirst() {
        assertThat(Page.of(items(25), 0, 10).number()).isEqualTo(1);
        assertThat(Page.of(items(25), -4, 10).number()).isEqualTo(1);
    }

    @Test
    @DisplayName("an empty listing is one empty page, not zero pages")
    void emptyListingIsOnePage() {
        final Page<String> page = Page.of(List.of(), 1, 10);

        assertThat(page.isEmpty()).isTrue();
        assertThat(page.total()).isZero();
        // Zero would render as "page 1 of 0", which reads as a fault rather than as an empty list.
        assertThat(page.pages()).isEqualTo(1);
        assertThat(page.hasNext()).isFalse();
    }

    @Test
    @DisplayName("a listing that exactly fills a page does not offer an empty one after it")
    void exactFillHasNoTrailingPage() {
        final Page<String> page = Page.of(items(20), 2, 10);

        assertThat(page.pages()).isEqualTo(2);
        assertThat(page.hasNext()).isFalse();
    }

    @Test
    @DisplayName("the first index continues the numbering across pages")
    void indicesContinueAcrossPages() {
        assertThat(Page.of(items(25), 1, 10).firstIndex()).isEqualTo(1);
        assertThat(Page.of(items(25), 2, 10).firstIndex()).isEqualTo(11);
        assertThat(Page.of(items(25), 3, 10).firstIndex()).isEqualTo(21);
    }

    @Test
    @DisplayName("a page of nothing is refused, because it would never terminate")
    void zeroSizeIsRefused() {
        assertThatThrownBy(() -> Page.of(items(5), 1, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
