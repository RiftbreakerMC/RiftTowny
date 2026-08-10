package net.riftbreaker.rifttowny.domain.org;

import net.riftbreaker.rifttowny.domain.event.DomainEvent;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * The result of asking an aggregate to change.
 *
 * <p>Either the change was applied — yielding the new aggregate and the events it produced — or it
 * was denied, with the reason. Sealed, so a caller cannot forget the denial case; and because the
 * new aggregate only exists inside {@link Applied}, there is no way to accidentally persist a state
 * the aggregate refused to enter.</p>
 *
 * <p>Aggregates are immutable: a method returns a new instance rather than mutating this one. That
 * is what makes them safe to hold in a cache, hand between Folia regions, and compare before and
 * after in a test.</p>
 *
 * @param <T> the aggregate type
 */
public sealed interface Outcome<T> {

    /**
     * The change happened.
     *
     * <p>The component is {@code aggregate} rather than {@code value} so it does not clash with the
     * interface's {@code Optional<T> value()} — the record accessor and the default method would
     * otherwise be the same name with incompatible return types.</p>
     */
    record Applied<T>(T aggregate, List<DomainEvent> events) implements Outcome<T> {
        public Applied {
            Objects.requireNonNull(aggregate, "aggregate");
            events = List.copyOf(Objects.requireNonNull(events, "events"));
        }
    }

    /** The change was refused, and nothing was produced. */
    record Denied<T>(ChangeDenial reason) implements Outcome<T> {
        public Denied {
            Objects.requireNonNull(reason, "reason");
        }
    }

    static <T> Outcome<T> applied(final T aggregate, final DomainEvent... events) {
        return new Applied<>(aggregate, List.of(events));
    }

    static <T> Outcome<T> denied(final ChangeDenial reason) {
        return new Denied<>(reason);
    }

    default boolean wasApplied() {
        return this instanceof Applied<T>;
    }

    /** The new aggregate, or empty when denied. */
    default Optional<T> value() {
        return this instanceof Applied<T> applied ? Optional.of(applied.aggregate()) : Optional.empty();
    }

    /** The events produced, empty when denied. */
    default List<DomainEvent> events() {
        return this instanceof Applied<T> applied ? applied.events() : List.of();
    }

    /** The denial reason, or empty when applied. */
    default Optional<ChangeDenial> denial() {
        return this instanceof Denied<T> denied ? Optional.of(denied.reason()) : Optional.empty();
    }

    /**
     * The new aggregate, or a failure naming the denial.
     *
     * <p>For call sites that have already established the change is legal — a test, or a service
     * that checked first inside the same transaction. Never for handling player input, where the
     * denial is the answer rather than an error.</p>
     */
    default T orElseThrow() {
        if (this instanceof Applied<T> applied) {
            return applied.aggregate();
        }
        throw new NoSuchElementException("Change was denied: " + ((Denied<T>) this).reason());
    }

    /**
     * Chains a second change onto an applied outcome, accumulating both sets of events.
     *
     * <p>A denial short-circuits, so a sequence of changes either happens completely or not at all —
     * which is what lets a service apply several aggregate methods inside one transaction without
     * hand-written unwinding.</p>
     */
    default Outcome<T> then(final Function<T, Outcome<T>> next) {
        Objects.requireNonNull(next, "next");
        if (!(this instanceof Applied<T> first)) {
            return this;
        }
        final Outcome<T> second = next.apply(first.aggregate());
        if (!(second instanceof Applied<T> applied)) {
            return second;
        }
        final List<DomainEvent> combined = new java.util.ArrayList<>(first.events());
        combined.addAll(applied.events());
        return new Applied<>(applied.aggregate(), combined);
    }
}
