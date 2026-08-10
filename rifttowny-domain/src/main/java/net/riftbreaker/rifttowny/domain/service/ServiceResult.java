package net.riftbreaker.rifttowny.domain.service;

import net.riftbreaker.rifttowny.domain.naming.NameProblem;
import net.riftbreaker.rifttowny.domain.org.ChangeDenial;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What a service call did.
 *
 * <p>Three outcomes, not two, because a refusal and an unusable name are different things to a
 * player: "you are already in a town" needs no correction, while "that name is too short and starts
 * with a digit" needs both problems listed. Folding them together would force the command layer to
 * invent one of the messages.</p>
 *
 * <p>Sealed, so a caller cannot forget a case. Nothing here throws — a denial is an expected answer
 * to a player's request, not an error.</p>
 */
public sealed interface ServiceResult<T> {

    /**
     * The change happened.
     *
     * <p>The component is {@code result} rather than {@code value} so it does not clash with the
     * interface's {@code Optional<T> value()} — a record accessor and a default method of the same
     * name with different return types will not compile.</p>
     */
    record Success<T>(T result) implements ServiceResult<T> {
        public Success {
            Objects.requireNonNull(result, "result");
        }
    }

    /** The change was refused by a rule. */
    record Refused<T>(ChangeDenial reason) implements ServiceResult<T> {
        public Refused {
            Objects.requireNonNull(reason, "reason");
        }
    }

    /** The supplied name could not be used, with every problem rather than only the first. */
    record NameRejected<T>(List<NameProblem> problems) implements ServiceResult<T> {
        public NameRejected {
            problems = List.copyOf(Objects.requireNonNull(problems, "problems"));
            if (problems.isEmpty()) {
                throw new IllegalArgumentException("a name rejection must carry at least one problem");
            }
        }
    }

    static <T> ServiceResult<T> success(final T value) {
        return new Success<>(value);
    }

    static <T> ServiceResult<T> refused(final ChangeDenial denial) {
        return new Refused<>(denial);
    }

    static <T> ServiceResult<T> nameRejected(final List<NameProblem> problems) {
        return new NameRejected<>(problems);
    }

    default boolean succeeded() {
        return this instanceof Success<T>;
    }

    default Optional<T> value() {
        return this instanceof Success<T> success ? Optional.of(success.result()) : Optional.empty();
    }

    default Optional<ChangeDenial> denial() {
        return this instanceof Refused<T> refused ? Optional.of(refused.reason()) : Optional.empty();
    }

    default List<NameProblem> nameProblems() {
        return this instanceof NameRejected<T> rejected ? rejected.problems() : List.of();
    }
}
