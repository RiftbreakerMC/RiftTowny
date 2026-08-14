package net.riftbreaker.rifttowny.domain.migration;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * What an import did, or what it would do.
 *
 * <p>The same type for both, because a dry run that reported in a different shape from the real
 * thing would be a dry run nobody could trust. The only difference between previewing and applying
 * is {@link #applied()}.</p>
 */
public record MigrationReport(
        boolean applied,
        int residents,
        int towns,
        int nations,
        int claims,
        List<Problem> problems
) {

    public MigrationReport {
        problems = List.copyOf(Objects.requireNonNullElse(problems, List.of()));
    }

    /** Whether anything at all was refused. */
    public boolean isClean() {
        return problems.isEmpty();
    }

    /** Problems bad enough that something was left out. */
    public List<Problem> skipped() {
        return problems.stream().filter(problem -> problem.severity() == Severity.SKIPPED).toList();
    }

    public String describe() {
        final String what = applied ? "Imported " : "Would import ";
        return what + residents + " resident(s), " + towns + " town(s), " + nations
                + " nation(s), " + claims + " claim(s)"
                + (problems.isEmpty() ? "." : "; " + problems.size() + " problem(s).");
    }

    /**
     * How bad a problem is.
     *
     * <p>Two levels and not five. The only question an operator actually has is whether something
     * was left behind, and a scale with a middle invites arguing about which rung a thing is on
     * rather than fixing it.</p>
     */
    public enum Severity {

        /** Something was left out. The import continued without it. */
        SKIPPED,

        /** Something was imported, but not exactly as it was. */
        ADJUSTED
    }

    /**
     * One thing that did not come across cleanly.
     *
     * @param subject what it was about, named the way the source named it, so an operator can find
     *        it in the file they came from rather than in ours
     */
    public record Problem(Severity severity, String kind, String subject, String detail) {

        public Problem {
            Objects.requireNonNull(severity, "severity");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(subject, "subject");
            Objects.requireNonNull(detail, "detail");
        }

        public static Problem skipped(final String kind, final String subject, final String detail) {
            return new Problem(Severity.SKIPPED, kind, subject, detail);
        }

        public static Problem adjusted(final String kind, final String subject, final String detail) {
            return new Problem(Severity.ADJUSTED, kind, subject, detail);
        }

        public String describe() {
            return severity.name().toLowerCase(Locale.ROOT) + ' ' + kind + " '" + subject
                    + "': " + detail;
        }
    }

    /** Accumulates a report while an import runs. Not thread-safe; an import is one job. */
    public static final class Builder {

        private final List<Problem> problems = new ArrayList<>();
        private int residents;
        private int towns;
        private int nations;
        private int claims;

        public void resident() {
            residents++;
        }

        public void town() {
            towns++;
        }

        public void nation() {
            nations++;
        }

        public void claim() {
            claims++;
        }

        public void claims(final int howMany) {
            claims += howMany;
        }

        public void problem(final Problem problem) {
            problems.add(Objects.requireNonNull(problem, "problem"));
        }

        public MigrationReport build(final boolean applied) {
            return new MigrationReport(applied, residents, towns, nations, claims,
                    List.copyOf(problems));
        }
    }
}
