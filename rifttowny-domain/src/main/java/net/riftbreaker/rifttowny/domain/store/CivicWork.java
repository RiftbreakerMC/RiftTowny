package net.riftbreaker.rifttowny.domain.store;

/**
 * A unit of work performed inside one transaction.
 *
 * <p><strong>Abort by throwing, never by returning.</strong> The store commits whenever the work
 * returns normally, so returning a "denied" value after having already written something would
 * commit the partial change. {@link net.riftbreaker.rifttowny.domain.org.Outcome} denials must
 * therefore be converted into a thrown {@link ChangeRefusedException} before any write happens —
 * which is easy to get right, because the aggregates deny before producing a new state to write.</p>
 */
@FunctionalInterface
public interface CivicWork<T> {

    T perform(CivicTransaction transaction) throws Exception;
}
