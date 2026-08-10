package net.riftbreaker.rifttowny.paper.message;

import net.riftbreaker.rifttowny.domain.naming.NameProblem;
import net.riftbreaker.rifttowny.domain.org.ChangeDenial;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Turns a refusal into a sentence.
 *
 * <p>Refusal text lives in {@code messages.yml} under {@code denial.*} and {@code name-problem.*},
 * keyed by the enum constant lower-cased with hyphens. Keeping them out of {@link MessageKey} keeps
 * that enum to the things a caller names explicitly, and a test asserts every constant of both
 * enums has an entry — so adding a denial without a message fails the build rather than shipping
 * a player-facing screenful of {@code INSUFFICIENT_ROLE_PRIORITY}.</p>
 *
 * <p>An unmapped constant still renders: it falls back to the humanised enum name. Ugly, and better
 * than blank.</p>
 */
public final class DenialText {

    private final Map<ChangeDenial, String> denials = new EnumMap<>(ChangeDenial.class);
    private final Map<NameProblem, String> nameProblems = new EnumMap<>(NameProblem.class);

    /**
     * @param lookup reads a path from {@code messages.yml}, returning null when absent
     */
    public DenialText(final java.util.function.Function<String, String> lookup) {
        Objects.requireNonNull(lookup, "lookup");
        for (final ChangeDenial denial : ChangeDenial.values()) {
            final String configured = lookup.apply("denial." + path(denial.name()));
            denials.put(denial, configured == null || configured.isBlank()
                    ? humanise(denial.name())
                    : configured);
        }
        for (final NameProblem problem : NameProblem.values()) {
            final String configured = lookup.apply("name-problem." + path(problem.name()));
            nameProblems.put(problem, configured == null || configured.isBlank()
                    ? humanise(problem.name())
                    : configured);
        }
    }

    public String of(final ChangeDenial denial) {
        return denials.getOrDefault(denial, humanise(String.valueOf(denial)));
    }

    public String of(final NameProblem problem) {
        return nameProblems.getOrDefault(problem, humanise(String.valueOf(problem)));
    }

    /** Several name problems as one readable list. */
    public String of(final Iterable<NameProblem> problems) {
        final StringBuilder joined = new StringBuilder();
        for (final NameProblem problem : problems) {
            if (joined.length() > 0) {
                joined.append("; ");
            }
            joined.append(of(problem));
        }
        return joined.toString();
    }

    /** The {@code messages.yml} path for an enum constant. */
    public static String path(final String constant) {
        return constant.toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static String humanise(final String constant) {
        final String spaced = constant.toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1) + '.';
    }
}
