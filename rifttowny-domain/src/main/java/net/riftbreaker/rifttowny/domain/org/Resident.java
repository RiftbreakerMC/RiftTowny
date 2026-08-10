package net.riftbreaker.rifttowny.domain.org;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * A player, and the one invariant that is theirs alone: a resident belongs to exactly one town.
 *
 * <p>Immutable. Every change returns a new instance inside an {@link Outcome}, so there is no
 * mutable object to share between Folia regions and no window between checking a rule and applying
 * it. The service persists the returned instance inside the same transaction that checked it.</p>
 *
 * <p>Town-side rules — the last resident, the mayor, capacity — belong to {@link Town}. Each
 * aggregate enforces what it owns, and a join that both must agree to is composed by the service
 * calling both.</p>
 */
public final class Resident {

    private final ResidentId id;
    private final String lastKnownName;
    private final TownId town;
    private final Instant joinedAt;
    private final Instant lastSeenAt;

    private Resident(
            final ResidentId id,
            final String lastKnownName,
            final TownId town,
            final Instant joinedAt,
            final Instant lastSeenAt
    ) {
        this.id = id;
        this.lastKnownName = lastKnownName;
        this.town = town;
        this.joinedAt = joinedAt;
        this.lastSeenAt = lastSeenAt;
    }

    /** A player seen for the first time, belonging to no town. */
    public static Resident newcomer(final ResidentId id, final String name, final Instant now) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(now, "now");
        return new Resident(id, requireName(name), null, now, now);
    }

    /** Rebuilds a resident from storage. */
    public static Resident restore(
            final ResidentId id,
            final String lastKnownName,
            final TownId town,
            final Instant joinedAt,
            final Instant lastSeenAt
    ) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(joinedAt, "joinedAt");
        Objects.requireNonNull(lastSeenAt, "lastSeenAt");
        return new Resident(id, requireName(lastKnownName), town, joinedAt, lastSeenAt);
    }

    /**
     * Joins a town.
     *
     * <p>Emits nothing: the resident's own record changing is not an event anybody subscribes to.
     * {@link Town#admit(ResidentId)} emits {@code ResidentAdmitted}, because being joined is the
     * town's news.</p>
     */
    public Outcome<Resident> joinTown(final TownId target) {
        Objects.requireNonNull(target, "target");
        if (town != null) {
            return Outcome.denied(town.equals(target)
                    ? ChangeDenial.ALREADY_IN_THIS_TOWN
                    : ChangeDenial.ALREADY_IN_ANOTHER_TOWN);
        }
        return Outcome.applied(new Resident(id, lastKnownName, target, joinedAt, lastSeenAt));
    }

    /** Leaves whichever town this resident is in. */
    public Outcome<Resident> leaveTown() {
        if (town == null) {
            return Outcome.denied(ChangeDenial.NOT_A_RESIDENT_OF_THIS_TOWN);
        }
        return Outcome.applied(new Resident(id, lastKnownName, null, joinedAt, lastSeenAt));
    }

    /** Records that the player was seen. Not a rule, so it cannot be denied. */
    public Resident seenAt(final Instant when) {
        Objects.requireNonNull(when, "when");
        return new Resident(id, lastKnownName, town, joinedAt, when);
    }

    /** Records a Minecraft name change. The identity is the UUID and does not move. */
    public Resident renamedTo(final String name) {
        return new Resident(id, requireName(name), town, joinedAt, lastSeenAt);
    }

    public ResidentId id() {
        return id;
    }

    public String lastKnownName() {
        return lastKnownName;
    }

    /** The town this resident belongs to, or empty. */
    public Optional<TownId> town() {
        return Optional.ofNullable(town);
    }

    public boolean isResidentOf(final TownId candidate) {
        return town != null && town.equals(candidate);
    }

    public boolean hasTown() {
        return town != null;
    }

    public Instant joinedAt() {
        return joinedAt;
    }

    public Instant lastSeenAt() {
        return lastSeenAt;
    }

    private static String requireName(final String name) {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("a resident's last known name must not be blank");
        }
        return name;
    }

    @Override
    public boolean equals(final Object other) {
        // Identity, not state: two loads of the same resident are the same resident even if one is
        // staler. Comparing every field would make a cache refresh look like a different player.
        return other instanceof Resident resident && id.equals(resident.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "Resident[" + lastKnownName + ' ' + id.value() + ']';
    }
}
