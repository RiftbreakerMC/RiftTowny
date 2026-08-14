package net.riftbreaker.rifttowny.domain.org;

import net.riftbreaker.rifttowny.domain.event.DomainEvent;
import net.riftbreaker.rifttowny.domain.naming.OrganisationName;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * A town: its identity, its members, its leader, and the rules about all three.
 *
 * <p>Immutable. Every change returns a new instance inside an {@link Outcome} together with the
 * events it produced, so an event cannot be emitted for a change that did not happen, and a state
 * the town refused to enter cannot be persisted.</p>
 *
 * <p>The town enforces what it owns: who is a member, who leads, who is trusted, and the ordering
 * rules that stop it becoming leaderless or abandoned. It deliberately does <em>not</em> enforce
 * "a resident belongs to one town" — that is {@link Resident}'s invariant, and duplicating it here
 * would give two places to fix when it changes.</p>
 *
 * <p>The bank account id is separate from the town id and never changes. Renaming and leadership
 * transfer both go through this class precisely so the account cannot be orphaned by either.</p>
 */
public final class Town {

    private final TownId id;
    private final OrganisationName name;
    private final ResidentId mayor;
    private final NationId nation;
    private final UUID bankAccountId;
    private final Set<ResidentId> residents;
    private final Set<ResidentId> trusted;
    private final TownProfile profile;
    private final Instant createdAt;

    private Town(
            final TownId id,
            final OrganisationName name,
            final ResidentId mayor,
            final NationId nation,
            final UUID bankAccountId,
            final Set<ResidentId> residents,
            final Set<ResidentId> trusted,
            final TownProfile profile,
            final Instant createdAt
    ) {
        this.id = id;
        this.name = name;
        this.mayor = mayor;
        this.nation = nation;
        this.bankAccountId = bankAccountId;
        this.profile = profile == null ? TownProfile.empty() : profile;
        // Not Set.copyOf: that returns a set with unspecified iteration order, which would throw
        // away the insertion order the callers below deliberately preserve. An unmodifiable view
        // over a defensive LinkedHashSet copy is immutable and ordered.
        this.residents = Collections.unmodifiableSet(new LinkedHashSet<>(residents));
        this.trusted = Collections.unmodifiableSet(new LinkedHashSet<>(trusted));
        this.createdAt = createdAt;
    }

    /**
     * Founds a town.
     *
     * @param bankAccountId deliberately a separate value from {@code id}. Keeping the civic account
     *        on its own identifier means a future change to how towns are identified cannot orphan
     *        a treasury
     */
    public static Town found(
            final TownId id,
            final OrganisationName name,
            final ResidentId founder,
            final UUID bankAccountId,
            final Instant now
    ) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(founder, "founder");
        Objects.requireNonNull(bankAccountId, "bankAccountId");
        Objects.requireNonNull(now, "now");
        return new Town(id, name, founder, null, bankAccountId, Set.of(founder), Set.of(),
                TownProfile.empty(), now);
    }

    /**
     * Rebuilds a town from storage.
     *
     * <p>Keeps the profile-free signature so the many callers that do not care about presentation
     * are not made to pass {@code TownProfile.empty()} to say nothing.</p>
     */
    public static Town restore(
            final TownId id,
            final OrganisationName name,
            final ResidentId mayor,
            final NationId nation,
            final UUID bankAccountId,
            final Set<ResidentId> residents,
            final Set<ResidentId> trusted,
            final Instant createdAt
    ) {
        return restore(id, name, mayor, nation, bankAccountId, residents, trusted,
                TownProfile.empty(), createdAt);
    }

    /** Rebuilds a town from storage, profile included. */
    public static Town restore(
            final TownId id,
            final OrganisationName name,
            final ResidentId mayor,
            final NationId nation,
            final UUID bankAccountId,
            final Set<ResidentId> residents,
            final Set<ResidentId> trusted,
            final TownProfile profile,
            final Instant createdAt
    ) {
        Objects.requireNonNull(mayor, "mayor");
        Objects.requireNonNull(residents, "residents");
        if (!residents.contains(mayor)) {
            // A town loaded with a mayor who is not a resident is corrupt. Failing here names the
            // problem; failing later would surface as a permission check behaving impossibly.
            throw new IllegalArgumentException(
                    "Town " + id + " was restored with a mayor who is not one of its residents");
        }
        return new Town(id, name, mayor, nation, bankAccountId,
                residents, Objects.requireNonNullElse(trusted, Set.of()), profile, createdAt);
    }

    /** Admits a resident. The caller must also apply {@link Resident#joinTown(TownId)}. */
    public Outcome<Town> admit(final ResidentId resident) {
        Objects.requireNonNull(resident, "resident");
        if (residents.contains(resident)) {
            return Outcome.denied(ChangeDenial.ALREADY_IN_THIS_TOWN);
        }
        final Set<ResidentId> updated = plus(residents, resident);
        // Admission clears any outstanding trust: keeping both would leave a member also carrying
        // outsider permissions, which is the overlap CANNOT_TRUST_A_RESIDENT exists to prevent.
        final Set<ResidentId> updatedTrust = minus(trusted, resident);
        return Outcome.applied(
                withMembers(updated, updatedTrust),
                new DomainEvent.ResidentAdmitted(id, resident));
    }

    /**
     * Releases a resident, whether they left or were removed.
     *
     * @param voluntary true when the resident chose to leave; recorded on the event so an audit can
     *        tell a departure from a kick
     */
    public Outcome<Town> release(final ResidentId resident, final boolean voluntary) {
        Objects.requireNonNull(resident, "resident");
        if (!residents.contains(resident)) {
            return Outcome.denied(ChangeDenial.NOT_A_RESIDENT_OF_THIS_TOWN);
        }
        if (residents.size() == 1) {
            // Checked before the mayor rule: the sole resident is necessarily the mayor, and
            // "transfer to whom?" is not a useful thing to tell them.
            return Outcome.denied(ChangeDenial.LAST_RESIDENT_MUST_DISBAND_INSTEAD);
        }
        if (resident.equals(mayor)) {
            return Outcome.denied(ChangeDenial.MAYOR_MUST_TRANSFER_FIRST);
        }
        return Outcome.applied(
                withMembers(minus(residents, resident), trusted),
                new DomainEvent.ResidentReleased(id, resident, voluntary));
    }

    /** Hands the mayoralty to another resident. */
    public Outcome<Town> transferLeadership(final ResidentId candidate) {
        Objects.requireNonNull(candidate, "candidate");
        if (!residents.contains(candidate)) {
            return Outcome.denied(ChangeDenial.LEADER_MUST_BE_A_MEMBER);
        }
        if (candidate.equals(mayor)) {
            return Outcome.denied(ChangeDenial.ALREADY_THE_LEADER);
        }
        final Town updated = new Town(
                id, name, candidate, nation, bankAccountId, residents, trusted, profile, createdAt);
        return Outcome.applied(updated, new DomainEvent.LeadershipTransferred(id, mayor, candidate));
    }

    /**
     * Renames the town.
     *
     * <p>The id and the bank account are untouched, which is the whole point of this method
     * existing rather than callers building a new {@link Town}.</p>
     */
    public Outcome<Town> renameTo(final OrganisationName newName) {
        Objects.requireNonNull(newName, "newName");
        if (newName.normalised().equals(name.normalised())
                && newName.display().equals(name.display())) {
            return Outcome.denied(ChangeDenial.NAME_UNCHANGED);
        }
        final Town updated = new Town(
                id, newName, mayor, nation, bankAccountId, residents, trusted, profile, createdAt);
        return Outcome.applied(updated, new DomainEvent.TownRenamed(id, name, newName));
    }

    /** Joins a nation. {@link Nation#admit(TownId)} must be applied in the same transaction. */
    public Outcome<Town> joinNation(final NationId target) {
        Objects.requireNonNull(target, "target");
        if (nation != null) {
            return Outcome.denied(nation.equals(target)
                    ? ChangeDenial.TOWN_ALREADY_IN_THIS_NATION
                    : ChangeDenial.TOWN_ALREADY_IN_ANOTHER_NATION);
        }
        final Town updated = new Town(
                id, name, mayor, target, bankAccountId, residents, trusted, profile, createdAt);
        return Outcome.applied(updated, new DomainEvent.TownJoinedNation(id, target));
    }

    /**
     * Leaves its nation.
     *
     * @param dissolvesNation whether this departure ends the nation, supplied by the caller because
     *        only the nation knows its own town count. Carried on the event so a Discord feed can
     *        say "left" or "dissolved" without a second query
     */
    public Outcome<Town> leaveNation(final boolean dissolvesNation) {
        if (nation == null) {
            return Outcome.denied(ChangeDenial.TOWN_NOT_IN_THIS_NATION);
        }
        final Town updated = new Town(
                id, name, mayor, null, bankAccountId, residents, trusted, profile, createdAt);
        return Outcome.applied(updated, new DomainEvent.TownLeftNation(id, nation, dissolvesNation));
    }

    /** Trusts an outsider. Trust grants narrow permissions and never membership. */
    public Outcome<Town> trust(final ResidentId outsider) {
        Objects.requireNonNull(outsider, "outsider");
        if (residents.contains(outsider)) {
            return Outcome.denied(ChangeDenial.CANNOT_TRUST_A_RESIDENT);
        }
        if (trusted.contains(outsider)) {
            return Outcome.denied(ChangeDenial.ALREADY_TRUSTED);
        }
        return Outcome.applied(
                withMembers(residents, plus(trusted, outsider)),
                new DomainEvent.OutsiderTrusted(id, outsider));
    }

    /** Revokes trust. */
    public Outcome<Town> untrust(final ResidentId outsider) {
        Objects.requireNonNull(outsider, "outsider");
        if (!trusted.contains(outsider)) {
            return Outcome.denied(ChangeDenial.NOT_TRUSTED);
        }
        return Outcome.applied(
                withMembers(residents, minus(trusted, outsider)),
                new DomainEvent.OutsiderUntrusted(id, outsider));
    }

    /**
     * How this person stands in the town: its leader, one of its members, or an outsider.
     *
     * <p>Lives here because the town is the only thing that knows who leads it and who belongs to
     * it. The role book takes this as an argument rather than working it out, which keeps the two
     * aggregates independent and makes the dependency visible at the call site.</p>
     */
    public net.riftbreaker.rifttowny.domain.role.SystemRole standingOf(final ResidentId who) {
        if (mayor.equals(who)) {
            return net.riftbreaker.rifttowny.domain.role.SystemRole.LEADER;
        }
        return residents.contains(who)
                ? net.riftbreaker.rifttowny.domain.role.SystemRole.MEMBER
                : net.riftbreaker.rifttowny.domain.role.SystemRole.VISITOR;
    }

    /**
     * What someone is entitled to here.
     *
     * <p>Residency is checked first and trust grants nothing, so no combination can produce
     * membership rights for a non-resident.</p>
     */
    public MembershipRights rightsOf(final ResidentId who) {
        if (residents.contains(who)) {
            return MembershipRights.forResident();
        }
        return trusted.contains(who)
                ? MembershipRights.forTrustedOutsider()
                : MembershipRights.forVisitor();
    }

    public TownId id() {
        return id;
    }

    public OrganisationName name() {
        return name;
    }

    public ResidentId mayor() {
        return mayor;
    }

    public Optional<NationId> nation() {
        return Optional.ofNullable(nation);
    }

    /** The civic account identifier. Stable across rename, leadership transfer and nation changes. */
    public UUID bankAccountId() {
        return bankAccountId;
    }

    public Set<ResidentId> residents() {
        return residents;
    }

    public Set<ResidentId> trustedOutsiders() {
        return trusted;
    }

    public int residentCount() {
        return residents.size();
    }

    public boolean hasResident(final ResidentId who) {
        return residents.contains(who);
    }

    public Instant createdAt() {
        return createdAt;
    }

    /** What the town says about itself, and who it lets in. Never null. */
    public TownProfile profile() {
        return profile;
    }

    /**
     * Replaces the profile.
     *
     * <p>One method for all six settings rather than six, because none of them can be refused:
     * a board is whatever its mayor typed and a colour is whatever parsed. There is no rule for
     * this aggregate to enforce, and inventing {@code setBoard} / {@code setTag} / {@code setOpen}
     * here would be six methods that all do the same nothing.</p>
     *
     * <p>Refused only when it would change nothing, matching {@link #renameTo}. A no-op write is
     * a transaction, an event and a cache refresh for a value that already had that value.</p>
     */
    public Outcome<Town> withProfile(final TownProfile updated) {
        Objects.requireNonNull(updated, "updated");
        if (updated.equals(profile)) {
            return Outcome.denied(ChangeDenial.NOTHING_TO_CHANGE);
        }
        return Outcome.applied(
                new Town(id, name, mayor, nation, bankAccountId, residents, trusted,
                        updated, createdAt),
                new DomainEvent.TownProfileChanged(id));
    }

    private Town withMembers(final Set<ResidentId> newResidents, final Set<ResidentId> newTrusted) {
        return new Town(id, name, mayor, nation, bankAccountId, newResidents, newTrusted,
                profile, createdAt);
    }

    private static Set<ResidentId> plus(final Set<ResidentId> source, final ResidentId added) {
        // Insertion-ordered, so a GUI resident list and an audit trail read in the order people
        // actually joined rather than in hash order, which changes between JVM runs.
        final Set<ResidentId> updated = new LinkedHashSet<>(source);
        updated.add(added);
        return updated;
    }

    private static Set<ResidentId> minus(final Set<ResidentId> source, final ResidentId removed) {
        final Set<ResidentId> updated = new LinkedHashSet<>(source);
        updated.remove(removed);
        return updated;
    }

    @Override
    public boolean equals(final Object other) {
        return other instanceof Town town && id.equals(town.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "Town[" + name.display() + ' ' + id.value() + ", " + residents.size() + " resident(s)]";
    }
}
