package net.riftbreaker.rifttowny.domain.naming;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The outcome of validating a proposed name.
 *
 * <p>Sealed so a caller must handle both cases. An {@code Optional<OrganisationName>} would have
 * thrown away the reasons, and returning null with an out-parameter of problems is how the reason
 * ends up unreported.</p>
 */
public sealed interface NameCheck {

    /** The name is syntactically valid. Uniqueness is a separate, storage-level question. */
    record Accepted(OrganisationName name) implements NameCheck {
        public Accepted {
            Objects.requireNonNull(name, "name");
        }
    }

    /** The name is refused, with every reason rather than only the first. */
    record Rejected(List<NameProblem> problems) implements NameCheck {
        public Rejected {
            problems = List.copyOf(Objects.requireNonNull(problems, "problems"));
            if (problems.isEmpty()) {
                throw new IllegalArgumentException("a rejection must carry at least one problem");
            }
        }
    }

    /** The accepted name, or empty. */
    default Optional<OrganisationName> accepted() {
        return this instanceof Accepted acceptedCheck ? Optional.of(acceptedCheck.name()) : Optional.empty();
    }

    /** Every problem found, empty when accepted. */
    default List<NameProblem> problems() {
        return this instanceof Rejected rejected ? rejected.problems() : List.of();
    }

    default boolean isAccepted() {
        return this instanceof Accepted;
    }
}
