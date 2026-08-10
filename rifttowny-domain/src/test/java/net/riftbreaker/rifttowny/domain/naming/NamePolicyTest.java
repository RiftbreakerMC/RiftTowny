package net.riftbreaker.rifttowny.domain.naming;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NamePolicyTest {

    private final NamePolicy policy = NamePolicy.defaults();

    private OrganisationName accept(final String raw) {
        final NameCheck check = policy.check(raw);
        assertThat(check.isAccepted()).as("expected '%s' to be accepted, got %s", raw, check.problems()).isTrue();
        return check.accepted().orElseThrow();
    }

    @Test
    @DisplayName("a valid name keeps its capitalisation and gains a folded uniqueness key")
    void displayAndNormalisedAreBothKept() {
        final OrganisationName name = accept("Riftholm");

        assertThat(name.display()).isEqualTo("Riftholm");
        assertThat(name.normalised()).isEqualTo("riftholm");
        // The skeleton is intentionally not the normalised form: 'l' folds into the i/l/1 class.
        // It is a comparison key for impersonation checks, never something shown or stored as the
        // uniqueness key.
        assertThat(name.skeleton()).isEqualTo("rifthoim");
    }

    @Test
    @DisplayName("surrounding whitespace is stripped rather than refused")
    void surroundingWhitespaceIsStripped() {
        assertThat(accept("  Riftholm  ").display()).isEqualTo("Riftholm");
    }

    @Test
    @DisplayName("two names differing only in case collide on the uniqueness key")
    void caseOnlyDifferencesCollide() {
        assertThat(accept("Riftholm").collidesWith(accept("RIFTHOLM"))).isTrue();
    }

    @Test
    @DisplayName("digits and separators fold to a skeleton, so an impersonation is detectable")
    void lookalikesShareASkeleton() {
        final OrganisationName original = accept("Riftholm");
        final OrganisationName impostor = accept("R1ft-holm");

        assertThat(impostor.collidesWith(original))
                .as("they are genuinely different rows, so the database will accept both")
                .isFalse();
        assertThat(impostor.looksLike(original))
                .as("but they are indistinguishable in chat, which is what looksLike is for")
                .isTrue();
    }

    @Test
    @DisplayName("a name does not look like itself, so an edit is never flagged as impersonating itself")
    void aNameDoesNotLookLikeItself() {
        assertThat(accept("Riftholm").looksLike(accept("Riftholm"))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Riftholm", "Rift_holm", "Rift-holm", "Aa1", "Zephyria2026"})
    @DisplayName("letters, digits, hyphen and underscore are accepted")
    void acceptableNames(final String raw) {
        assertThat(policy.check(raw).isAccepted()).isTrue();
    }

    @Test
    @DisplayName("an empty or blank name is refused as empty, not as too short")
    void blankIsEmpty() {
        assertThat(policy.check(null).problems()).containsExactly(NameProblem.EMPTY);
        assertThat(policy.check("   ").problems()).containsExactly(NameProblem.EMPTY);
    }

    @Test
    @DisplayName("length bounds are enforced against the schema column width")
    void lengthBounds() {
        assertThat(policy.check("Ab").problems()).contains(NameProblem.TOO_SHORT);
        assertThat(policy.check("A".repeat(33)).problems()).contains(NameProblem.TOO_LONG);
        assertThat(policy.check("A".repeat(32)).isAccepted()).isTrue();
    }

    @Test
    @DisplayName("a policy longer than the 32-character column is refused at construction")
    void policyCannotExceedTheSchema() {
        assertThatThrownBy(() -> new NamePolicy(3, 64, Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32");
    }

    @Test
    @DisplayName("internal whitespace is reported separately from an illegal character")
    void whitespaceIsItsOwnProblem() {
        assertThat(policy.check("Rift holm").problems())
                .contains(NameProblem.CONTAINS_WHITESPACE)
                .doesNotContain(NameProblem.ILLEGAL_CHARACTER);
    }

    @Test
    @DisplayName("punctuation outside hyphen and underscore is an illegal character")
    void punctuationIsIllegal() {
        assertThat(policy.check("Rift!holm").problems()).contains(NameProblem.ILLEGAL_CHARACTER);
    }

    @Test
    @DisplayName("a name must start with a letter, so it never reads as an id")
    void mustStartWithALetter() {
        assertThat(policy.check("1Riftholm").problems()).contains(NameProblem.MUST_START_WITH_LETTER);
        assertThat(policy.check("_Riftholm").problems()).contains(NameProblem.MUST_START_WITH_LETTER);
    }

    @ParameterizedTest
    @ValueSource(strings = {"admin", "Admin", "WILDERNESS", "spawn", "none", "Towny", "RiftTowny"})
    @DisplayName("reserved words are refused whatever their casing")
    void reservedWordsAreRefused(final String raw) {
        assertThat(policy.check(raw).problems()).contains(NameProblem.RESERVED_WORD);
    }

    @Test
    @DisplayName("every problem is reported at once, so a player is not sent round the loop")
    void allProblemsAreCollected() {
        assertThat(policy.check("1 !").problems())
                .contains(
                        NameProblem.CONTAINS_WHITESPACE,
                        NameProblem.ILLEGAL_CHARACTER,
                        NameProblem.MUST_START_WITH_LETTER);

        assertThat(policy.check("1").problems())
                .contains(NameProblem.TOO_SHORT, NameProblem.MUST_START_WITH_LETTER);
    }

    @Test
    @DisplayName("the i/l/1 class folds together, which is the one that actually gets abused")
    void confusableLetterClassFolds() {
        assertThat(NamePolicy.skeleton("riftholm"))
                .isEqualTo(NamePolicy.skeleton("r1ftholm"))
                .isEqualTo(NamePolicy.skeleton("riftho1m"))
                .isEqualTo(NamePolicy.skeleton("rift-holm"));
    }

    @Test
    @DisplayName("a rejection always carries a reason")
    void rejectionCannotBeEmpty() {
        assertThatThrownBy(() -> new NameCheck.Rejected(java.util.List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("genuinely different names keep different skeletons, so the flag is not useless")
    void differentNamesDoNotAllCollapseTogether() {
        assertThat(NamePolicy.skeleton("riftholm")).isNotEqualTo(NamePolicy.skeleton("ashford"));
        assertThat(accept("Riftholm").looksLike(accept("Ashford"))).isFalse();
    }
}
