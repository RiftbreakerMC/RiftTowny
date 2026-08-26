package net.riftbreaker.rifttowny.domain.resident;

import net.riftbreaker.rifttowny.domain.org.ResidentId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a player has chosen.
 *
 * <p>The whole design rests on one distinction: never having chosen is not the same as having
 * chosen "off". Everything below is really a test of that, because collapsing the two is the one
 * mistake here that cannot be undone afterwards — once a default has been written into a row, there
 * is no way to tell it from a decision.</p>
 */
class ResidentPreferencesTest {

    private final ResidentPreferences preferences = ResidentPreferences.empty();

    private static final ResidentId BEDE = ResidentId.of(UUID.randomUUID());
    private static final ResidentId ADA = ResidentId.of(UUID.randomUUID());

    @Nested
    @DisplayName("choosing")
    class Choosing {

        @Test
        @DisplayName("nobody has chosen anything to begin with")
        void emptyToStart() {
            assertThat(preferences.noticeFor(BEDE)).isEmpty();
            assertThat(preferences.size()).isZero();
        }

        @Test
        @DisplayName("a choice binds one player and nobody else")
        void isNarrow() {
            preferences.choose(BEDE, NoticePreference.OFF);

            assertThat(preferences.noticeFor(BEDE)).contains(NoticePreference.OFF);
            assertThat(preferences.noticeFor(ADA)).isEmpty();
        }

        @Test
        @DisplayName("choosing again replaces rather than accumulating")
        void replaces() {
            preferences.choose(BEDE, NoticePreference.OFF);
            preferences.choose(BEDE, NoticePreference.CHAT);

            assertThat(preferences.noticeFor(BEDE)).contains(NoticePreference.CHAT);
            assertThat(preferences.size()).isEqualTo(1);
        }

        @Test
        @DisplayName("nothing is chosen by nobody")
        void nullsAreSafe() {
            assertThat(preferences.noticeFor(null)).isEmpty();
        }
    }

    @Nested
    @DisplayName("the distinction the whole thing rests on")
    class UnsetIsNotOff {

        @Test
        @DisplayName("clearing a choice is not the same as choosing off")
        void clearingIsNotOff() {
            // A player who turned notices off keeps them off when the operator changes the server
            // setting. A player who cleared their choice moves with the server. If clearing wrote
            // an OFF row, or an OFF row were stored as absent, those two swap places silently.
            preferences.choose(BEDE, NoticePreference.OFF);
            preferences.choose(ADA, NoticePreference.CHAT);

            preferences.clear(ADA);

            assertThat(preferences.noticeFor(BEDE))
                    .as("an explicit off is still a choice")
                    .contains(NoticePreference.OFF);
            assertThat(preferences.noticeFor(ADA))
                    .as("a cleared choice is no choice at all")
                    .isEmpty();
        }

        @Test
        @DisplayName("clearing what was never chosen changes nothing")
        void clearingNothing() {
            preferences.choose(BEDE, NoticePreference.CHAT);

            preferences.clear(ADA);

            assertThat(preferences.size()).isEqualTo(1);
            assertThat(preferences.noticeFor(BEDE)).contains(NoticePreference.CHAT);
        }

        @Test
        @DisplayName("a startup load carries the choices and nothing else")
        void loadReplaces() {
            preferences.choose(BEDE, NoticePreference.OFF);

            preferences.replaceAll(List.of(
                    new ResidentPreferences.Choice(ADA, NoticePreference.ACTION_BAR),
                    // A row whose column is null is a player with a row and no choice, which reads
                    // exactly like a player with no row.
                    new ResidentPreferences.Choice(BEDE, null)));

            assertThat(preferences.noticeFor(ADA)).contains(NoticePreference.ACTION_BAR);
            assertThat(preferences.noticeFor(BEDE)).isEmpty();
            assertThat(preferences.size()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("the values themselves")
    class Values {

        @Test
        @DisplayName("off is the only one that stops a notice")
        void announcing() {
            assertThat(NoticePreference.OFF.announces()).isFalse();
            assertThat(NoticePreference.CHAT.announces()).isTrue();
            assertThat(NoticePreference.ACTION_BAR.announces()).isTrue();
        }

        @Test
        @DisplayName("only the action bar uses the action bar")
        void surface() {
            assertThat(NoticePreference.ACTION_BAR.usesActionBar()).isTrue();
            assertThat(NoticePreference.CHAT.usesActionBar()).isFalse();
        }

        @Test
        @DisplayName("it is typed as one word and parsed as either")
        void parsing() {
            assertThat(NoticePreference.parse("actionbar")).contains(NoticePreference.ACTION_BAR);
            assertThat(NoticePreference.parse("ACTION_BAR")).contains(NoticePreference.ACTION_BAR);
            assertThat(NoticePreference.parse("action-bar")).contains(NoticePreference.ACTION_BAR);
            assertThat(NoticePreference.ACTION_BAR.typed()).isEqualTo("actionbar");
            assertThat(NoticePreference.parse("Off")).contains(NoticePreference.OFF);
        }

        @Test
        @DisplayName("a typo is empty rather than a crash, and so is a value we do not know")
        void refusals() {
            assertThat(NoticePreference.parse("bogus")).isEmpty();
            assertThat(NoticePreference.parse("")).isEmpty();
            assertThat(NoticePreference.parse(null)).isEmpty();
            // Deliberately not a constant: "follow the server" is the absence of a choice, and a
            // parseable DEFAULT would let it be stored.
            assertThat(NoticePreference.parse("default")).isEmpty();
        }
    }
}
