package net.riftbreaker.rifttowny.domain.org;

import net.riftbreaker.rifttowny.domain.event.DomainEvent;
import net.riftbreaker.rifttowny.domain.naming.OrganisationName;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * A nation: its member towns, its capital, its leader.
 *
 * <p>Immutable, like {@link Town}, and for the same reasons. It enforces the invariants only it can
 * see — that the capital is a member town, and that a nation with towns always has a capital — and
 * leaves "a town belongs to at most one nation" to {@link Town}, which owns that fact.</p>
 */
public final class Nation {

    private final NationId id;
    private final OrganisationName name;
    private final ResidentId leader;
    private final TownId capital;
    private final UUID bankAccountId;
    private final Set<TownId> towns;
    private final Instant createdAt;

    private Nation(
            final NationId id,
            final OrganisationName name,
            final ResidentId leader,
            final TownId capital,
            final UUID bankAccountId,
            final Set<TownId> towns,
            final Instant createdAt
    ) {
        this.id = id;
        this.name = name;
        this.leader = leader;
        this.capital = capital;
        this.bankAccountId = bankAccountId;
        // Not Set.copyOf: it returns a set with unspecified iteration order, discarding the
        // insertion order the callers below preserve.
        this.towns = Collections.unmodifiableSet(new LinkedHashSet<>(towns));
        this.createdAt = createdAt;
    }

    /** Founds a nation. The founding town becomes its capital and only member. */
    public static Nation found(
            final NationId id,
            final OrganisationName name,
            final ResidentId leader,
            final TownId capital,
            final UUID bankAccountId,
            final Instant now
    ) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(leader, "leader");
        Objects.requireNonNull(capital, "capital");
        Objects.requireNonNull(bankAccountId, "bankAccountId");
        Objects.requireNonNull(now, "now");
        return new Nation(id, name, leader, capital, bankAccountId, Set.of(capital), now);
    }

    /** Rebuilds a nation from storage. */
    public static Nation restore(
            final NationId id,
            final OrganisationName name,
            final ResidentId leader,
            final TownId capital,
            final UUID bankAccountId,
            final Set<TownId> towns,
            final Instant createdAt
    ) {
        Objects.requireNonNull(towns, "towns");
        Objects.requireNonNull(capital, "capital");
        if (!towns.contains(capital)) {
            throw new IllegalArgumentException(
                    "Nation " + id + " was restored with a capital that is not one of its towns");
        }
        return new Nation(id, name, leader, capital, bankAccountId, towns, createdAt);
    }

    /** Admits a town. {@link Town#joinNation(NationId)} must be applied in the same transaction. */
    public Outcome<Nation> admit(final TownId town) {
        Objects.requireNonNull(town, "town");
        if (towns.contains(town)) {
            return Outcome.denied(ChangeDenial.TOWN_ALREADY_IN_THIS_NATION);
        }
        return Outcome.applied(
                withTowns(plus(towns, town), capital),
                new DomainEvent.TownJoinedNation(town, id));
    }

    /**
     * Releases a town.
     *
     * <p>The capital may leave only when it is the last town, in which case the nation dissolves.
     * That is deliberately allowed: dissolving a nation is a legitimate, recoverable act, whereas
     * leaving member towns under a nation with no capital is not.</p>
     */
    public Outcome<Nation> release(final TownId town) {
        Objects.requireNonNull(town, "town");
        if (!towns.contains(town)) {
            return Outcome.denied(ChangeDenial.TOWN_NOT_IN_THIS_NATION);
        }
        if (town.equals(capital) && towns.size() > 1) {
            return Outcome.denied(ChangeDenial.CAPITAL_MUST_MOVE_FIRST);
        }
        final boolean dissolves = towns.size() == 1;
        final Set<TownId> remaining = minus(towns, town);
        // When the last town leaves, the capital reference is kept rather than nulled: the nation
        // is about to be deleted, and a half-emptied aggregate is easier to misuse than a
        // consistent one that the service is about to discard.
        return Outcome.applied(
                withTowns(remaining, dissolves ? capital : capital),
                new DomainEvent.TownLeftNation(town, id, dissolves));
    }

    /** Moves the capital to another member town. */
    public Outcome<Nation> moveCapital(final TownId town) {
        Objects.requireNonNull(town, "town");
        if (!towns.contains(town)) {
            return Outcome.denied(ChangeDenial.CAPITAL_MUST_BE_A_MEMBER_TOWN);
        }
        if (town.equals(capital)) {
            return Outcome.denied(ChangeDenial.ALREADY_THE_CAPITAL);
        }
        return Outcome.applied(
                withTowns(towns, town),
                new DomainEvent.CapitalMoved(id, capital, town));
    }

    /**
     * Hands leadership to another player.
     *
     * @param candidateIsMember whether the candidate is a resident of one of this nation's towns.
     *        Supplied by the caller because it spans two aggregates: the nation knows its towns, but
     *        only a town knows its residents. Passing it explicitly keeps the dependency visible
     *        instead of hiding a repository lookup inside a domain object
     */
    public Outcome<Nation> transferLeadership(
            final ResidentId candidate, final boolean candidateIsMember) {
        Objects.requireNonNull(candidate, "candidate");
        if (!candidateIsMember) {
            return Outcome.denied(ChangeDenial.LEADER_MUST_BE_A_MEMBER);
        }
        if (candidate.equals(leader)) {
            return Outcome.denied(ChangeDenial.ALREADY_THE_LEADER);
        }
        final Nation updated =
                new Nation(id, name, candidate, capital, bankAccountId, towns, createdAt);
        return Outcome.applied(
                updated, new DomainEvent.NationLeadershipTransferred(id, leader, candidate));
    }

    /** Renames the nation, leaving its id and civic account untouched. */
    public Outcome<Nation> renameTo(final OrganisationName newName) {
        Objects.requireNonNull(newName, "newName");
        if (newName.normalised().equals(name.normalised())
                && newName.display().equals(name.display())) {
            return Outcome.denied(ChangeDenial.NAME_UNCHANGED);
        }
        final Nation updated =
                new Nation(id, newName, leader, capital, bankAccountId, towns, createdAt);
        return Outcome.applied(updated, new DomainEvent.NationRenamed(id, name, newName));
    }

    /** Whether removing this town would end the nation. */
    public boolean wouldDissolveOnLeaving(final TownId town) {
        return towns.size() == 1 && towns.contains(town);
    }

    /**
     * How this person stands in the nation: its leader, a citizen, or an outsider.
     *
     * <p>Takes their town rather than looking it up, for the same reason
     * {@link #transferLeadership} takes {@code candidateIsMember}: nation citizenship is residency in
     * a member town, and only a town knows its residents. Passing it in keeps the dependency visible
     * instead of hiding a repository call inside a domain object.</p>
     *
     * @param theirTown the town this person belongs to, or null if they belong to none
     */
    public net.riftbreaker.rifttowny.domain.role.SystemRole standingOf(
            final ResidentId who, final TownId theirTown) {
        if (leader.equals(who)) {
            return net.riftbreaker.rifttowny.domain.role.SystemRole.LEADER;
        }
        return theirTown != null && towns.contains(theirTown)
                ? net.riftbreaker.rifttowny.domain.role.SystemRole.MEMBER
                : net.riftbreaker.rifttowny.domain.role.SystemRole.VISITOR;
    }

    public NationId id() {
        return id;
    }

    public OrganisationName name() {
        return name;
    }

    public ResidentId leader() {
        return leader;
    }

    public TownId capital() {
        return capital;
    }

    /** The civic account identifier. Stable across rename, leadership transfer and capital moves. */
    public UUID bankAccountId() {
        return bankAccountId;
    }

    public Set<TownId> towns() {
        return towns;
    }

    public int townCount() {
        return towns.size();
    }

    public boolean hasTown(final TownId town) {
        return towns.contains(town);
    }

    public Instant createdAt() {
        return createdAt;
    }

    private Nation withTowns(final Set<TownId> newTowns, final TownId newCapital) {
        return new Nation(id, name, leader, newCapital, bankAccountId, newTowns, createdAt);
    }

    private static Set<TownId> plus(final Set<TownId> source, final TownId added) {
        final Set<TownId> updated = new LinkedHashSet<>(source);
        updated.add(added);
        return updated;
    }

    private static Set<TownId> minus(final Set<TownId> source, final TownId removed) {
        final Set<TownId> updated = new LinkedHashSet<>(source);
        updated.remove(removed);
        return updated;
    }

    @Override
    public boolean equals(final Object other) {
        return other instanceof Nation nation && id.equals(nation.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "Nation[" + name.display() + ' ' + id.value() + ", " + towns.size() + " town(s)]";
    }
}
