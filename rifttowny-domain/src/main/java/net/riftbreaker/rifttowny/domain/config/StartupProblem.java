package net.riftbreaker.rifttowny.domain.config;

import java.util.Objects;

/**
 * Something wrong with the configuration, and what to do about it.
 *
 * @param severity whether this stops startup
 * @param setting the configuration path at fault, so the operator does not have to guess
 * @param problem what is wrong
 * @param remedy what to change
 */
public record StartupProblem(Severity severity, String setting, String problem, String remedy) {

    public enum Severity {
        /** Startup aborts. Continuing would risk data loss or a silently wrong install. */
        ERROR,
        /** Startup continues, but the operator is told. */
        WARNING
    }

    public StartupProblem {
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(setting, "setting");
        Objects.requireNonNull(problem, "problem");
        Objects.requireNonNull(remedy, "remedy");
    }

    public static StartupProblem error(final String setting, final String problem, final String remedy) {
        return new StartupProblem(Severity.ERROR, setting, problem, remedy);
    }

    public static StartupProblem warning(final String setting, final String problem, final String remedy) {
        return new StartupProblem(Severity.WARNING, setting, problem, remedy);
    }

    public boolean fatal() {
        return severity == Severity.ERROR;
    }

    /** One line, formatted for the server log. */
    public String describe() {
        return severity + " [" + setting + "] " + problem + " -> " + remedy;
    }
}
