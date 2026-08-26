package net.riftbreaker.rifttowny.storage;

import net.riftbreaker.rifttowny.domain.org.ResidentId;
import net.riftbreaker.rifttowny.domain.resident.NoticePreference;
import net.riftbreaker.rifttowny.domain.resident.ResidentPreferences;
import net.riftbreaker.rifttowny.domain.service.PreferenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Preferences through real storage.
 *
 * <p>The domain test covers the rule. What only a database shows is that clearing really deletes the
 * row rather than writing something into it — which is the difference between a player who follows
 * the server and one who is pinned to whatever it happened to be that day.</p>
 */
class PreferenceServiceTest extends SqliteFixture {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-08-15T09:00:00Z"), ZoneOffset.UTC);
    private static final ResidentId BEDE = ResidentId.of(UUID.randomUUID());

    private final ResidentPreferences book = ResidentPreferences.empty();

    private JdbcCivicStore store;
    private PreferenceService preferences;

    @BeforeEach
    void createService() {
        store = new JdbcCivicStore(database, DIRECT, CLOCK);
        preferences = new PreferenceService(store, CLOCK, book);
    }

    @Test
    @DisplayName("a choice is written and reaches the cache")
    void writesAndCaches() {
        preferences.chooseNotice(BEDE, NoticePreference.OFF).join();

        assertThat(book.noticeFor(BEDE)).contains(NoticePreference.OFF);
        assertThat(store.inTransaction(t -> t.preferences().find(BEDE)).join())
                .get()
                .extracting(ResidentPreferences.Choice::notice)
                .isEqualTo(NoticePreference.OFF);
    }

    @Test
    @DisplayName("choosing again replaces the row rather than adding one")
    void upserts() {
        preferences.chooseNotice(BEDE, NoticePreference.OFF).join();
        preferences.chooseNotice(BEDE, NoticePreference.CHAT).join();

        assertThat(store.inTransaction(t -> t.preferences().all()).join()).hasSize(1);
        assertThat(book.noticeFor(BEDE)).contains(NoticePreference.CHAT);
    }

    @Test
    @DisplayName("clearing deletes the row, so the player follows the server again")
    void clearingDeletes() {
        // Not a stored default. A row saying "whatever the server did on the day I typed this" is
        // the bug this design exists to avoid.
        preferences.chooseNotice(BEDE, NoticePreference.ACTION_BAR).join();

        assertThat(preferences.clearNotice(BEDE).join()).isTrue();

        assertThat(store.inTransaction(t -> t.preferences().find(BEDE)).join()).isEmpty();
        assertThat(book.noticeFor(BEDE)).isEmpty();
    }

    @Test
    @DisplayName("clearing what was never set is not a failure")
    void clearingNothing() {
        assertThat(preferences.clearNotice(BEDE).join()).isFalse();
        assertThat(book.noticeFor(BEDE)).isEmpty();
    }

    @Test
    @DisplayName("a startup load rebuilds the cache from the table")
    void loadRebuilds() {
        preferences.chooseNotice(BEDE, NoticePreference.CHAT).join();
        book.replaceAll(java.util.List.of());

        assertThat(preferences.loadAll().join()).isEqualTo(1);
        assertThat(book.noticeFor(BEDE)).contains(NoticePreference.CHAT);
    }
}
