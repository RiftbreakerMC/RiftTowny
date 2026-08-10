package net.riftbreaker.rifttowny.domain.naming;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Validates and normalises organisation names.
 *
 * <p>Three forms come out of one pass, because deriving them separately is how they drift:
 * the display name, the case-folded uniqueness key, and a lookalike-folded skeleton used only to
 * flag impersonation.</p>
 *
 * <p>Pure and configuration-driven, so the rules are testable without a server and an operator can
 * loosen them without a code change.</p>
 */
public final class NamePolicy {

    /**
     * Words no organisation may take.
     *
     * <p>Each is here for a concrete reason, not caution: {@code wilderness} and {@code spawn} would
     * make territory messages ambiguous, {@code admin}/{@code staff}/{@code server}/{@code console}
     * invite impersonation, {@code none}/{@code null}/{@code default} collide with the blank values
     * that placeholders return, and {@code towny}/{@code rifttowny} would make a support question
     * unanswerable.</p>
     */
    public static final Set<String> DEFAULT_RESERVED = Set.of(
            "admin", "administrator", "staff", "server", "console", "system", "moderator",
            "wilderness", "wild", "spawn", "none", "null", "default", "all", "here",
            "town", "nation", "towny", "rifttowny", "riftwars", "unknown");

    private final int minLength;
    private final int maxLength;
    private final Set<String> reserved;

    public NamePolicy(final int minLength, final int maxLength, final Set<String> reservedWords) {
        if (minLength < 1) {
            throw new IllegalArgumentException("minLength must be at least 1, got " + minLength);
        }
        if (maxLength < minLength) {
            throw new IllegalArgumentException(
                    "maxLength (" + maxLength + ") must not be below minLength (" + minLength + ')');
        }
        // 32 is the width of name and name_normalised in the V1 schema. Allowing a longer name here
        // would push the failure down to a truncation or a constraint violation at insert time,
        // which reads to a player as "the server broke" rather than "that name is too long".
        if (maxLength > 32) {
            throw new IllegalArgumentException(
                    "maxLength must not exceed the 32-character schema column, got " + maxLength);
        }
        this.minLength = minLength;
        this.maxLength = maxLength;
        this.reserved = Set.copyOf(Objects.requireNonNull(reservedWords, "reservedWords")).stream()
                .map(word -> word.toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /** The shipped defaults: 3 to 32 characters, {@link #DEFAULT_RESERVED}. */
    public static NamePolicy defaults() {
        return new NamePolicy(3, 32, DEFAULT_RESERVED);
    }

    /**
     * Validates a proposed name.
     *
     * <p>Collects every problem rather than stopping at the first, so a player fixing a name is not
     * sent round the loop three times.</p>
     */
    public NameCheck check(final String raw) {
        if (raw == null || raw.isBlank()) {
            return new NameCheck.Rejected(List.of(NameProblem.EMPTY));
        }

        final String display = raw.strip();
        final List<NameProblem> problems = new ArrayList<>();

        if (display.length() < minLength) {
            problems.add(NameProblem.TOO_SHORT);
        }
        if (display.length() > maxLength) {
            problems.add(NameProblem.TOO_LONG);
        }

        boolean whitespace = false;
        boolean illegal = false;
        for (int index = 0; index < display.length(); index++) {
            final char character = display.charAt(index);
            if (Character.isWhitespace(character)) {
                whitespace = true;
            } else if (!isAllowed(character)) {
                illegal = true;
            }
        }
        if (whitespace) {
            problems.add(NameProblem.CONTAINS_WHITESPACE);
        }
        if (illegal) {
            problems.add(NameProblem.ILLEGAL_CHARACTER);
        }

        if (!Character.isLetter(display.charAt(0))) {
            problems.add(NameProblem.MUST_START_WITH_LETTER);
        }

        final String normalised = display.toLowerCase(Locale.ROOT);
        if (reserved.contains(normalised)) {
            problems.add(NameProblem.RESERVED_WORD);
        }

        // No emptiness check on the skeleton: letters are never dropped by the fold, and the
        // leading-letter rule above guarantees at least one. A check here would be unreachable
        // code pretending to be a safeguard.
        final String skeleton = skeleton(normalised);

        return problems.isEmpty()
                ? new NameCheck.Accepted(new OrganisationName(display, normalised, skeleton))
                : new NameCheck.Rejected(problems);
    }

    /**
     * Folds a normalised name to a lookalike skeleton.
     *
     * <p>Drops separators and collapses each set of glyphs that are hard to tell apart in a
     * Minecraft font onto one representative, so {@code R1ft-holm} and {@code Riftholm} share a
     * skeleton.</p>
     *
     * <p>The {@code i}/{@code l}/{@code 1} class is the important one and the reason a
     * digits-only mapping is not enough: folding {@code 1} to {@code l} alone still leaves
     * {@code r1ftholm} and {@code riftholm} different, because the character it is actually
     * impersonating there is {@code i}. All three collapse to one symbol.</p>
     *
     * <p>Deliberately lossy, and deliberately <em>not</em> the uniqueness key: it exists to raise a
     * flag for a human, not to refuse a name outright, because two innocent names can legitimately
     * collide here.</p>
     */
    public static String skeleton(final String normalised) {
        final StringBuilder folded = new StringBuilder(normalised.length());
        for (int index = 0; index < normalised.length(); index++) {
            final char character = Character.toLowerCase(normalised.charAt(index));
            switch (character) {
                case '_', '-' -> { /* separators carry no identity */ }
                case 'i', 'l', '1', '|', '!' -> folded.append('i');
                case 'o', '0' -> folded.append('o');
                case 'e', '3' -> folded.append('e');
                case 'a', '4' -> folded.append('a');
                case 's', '5' -> folded.append('s');
                case 't', '7' -> folded.append('t');
                case 'b', '8' -> folded.append('b');
                case 'g', '9' -> folded.append('g');
                case 'z', '2' -> folded.append('z');
                default -> folded.append(character);
            }
        }
        return folded.toString();
    }

    private static boolean isAllowed(final char character) {
        return Character.isLetterOrDigit(character) || character == '_' || character == '-';
    }

    public int minLength() {
        return minLength;
    }

    public int maxLength() {
        return maxLength;
    }

    public Set<String> reservedWords() {
        return reserved;
    }
}
