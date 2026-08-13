package net.riftbreaker.rifttowny.domain.territory;

import net.riftbreaker.rifttowny.domain.naming.OrganisationName;
import net.riftbreaker.rifttowny.domain.org.ResidentId;
import net.riftbreaker.rifttowny.domain.org.TownId;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * What a town leaves behind.
 *
 * <p>Between a town ending and its land becoming wilderness there is an interval, and the interval
 * is the point. The buildings are still standing; reverting instantly would mean the first player
 * to walk past owns everything in them, and a town that lost a war or a mayor would be erased before
 * anybody could take it on. A ruin holds the shell for a while, and then lets go.</p>
 *
 * <p>Immutable. Reclaiming produces a new instance rather than mutating this one, so a ruin that was
 * taken and a ruin that lapsed are different values rather than the same one in two moods.</p>
 *
 * @param formerTown the town that stood here. Kept after the land reverts, because
 *        {@code docs/war-decisions.md} keys anti-recreation on the previous organisation's ruin
 *        record — which cannot be a record if it is swept the moment the claims go
 * @param founder whoever founded the town that fell, or null if that was never recorded
 * @param reclaimedAs the town that took this ruin on, or null while it stands unclaimed
 */
public record Ruin(
        UUID id,
        TownId formerTown,
        OrganisationName name,
        ResidentId founder,
        net.riftbreaker.rifttowny.api.ChunkKey homeblock,
        Instant ruinedAt,
        Instant reclaimableFrom,
        Instant expiresAt,
        Instant reclaimedAt,
        ResidentId reclaimedBy,
        TownId reclaimedAs
) {

    /** How long a ruin stands before its land reverts, unless configured otherwise. */
    public static final Duration DEFAULT_LIFETIME = Duration.ofDays(3);

    /**
     * How long a fallen town lies open before anybody may take it on.
     *
     * <p>Not zero, and the reason is the point of the delay: without it a mayor could disband and
     * immediately re-found on the same ground, shedding whatever the town had accumulated —
     * obligations, a reputation, a war — while keeping its land. The window is the cost of that
     * manoeuvre, and it is also the interval in which the fallen town's neighbours get to pick it
     * over.</p>
     */
    public static final Duration DEFAULT_RECLAIM_DELAY = Duration.ofDays(1);

    public Ruin {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(formerTown, "formerTown");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(ruinedAt, "ruinedAt");
        Objects.requireNonNull(reclaimableFrom, "reclaimableFrom");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }

    /** A town has just fallen. */
    public static Ruin of(
            final TownId formerTown,
            final OrganisationName name,
            final ResidentId founder,
            final net.riftbreaker.rifttowny.api.ChunkKey homeblock,
            final Instant now,
            final Duration reclaimDelay,
            final Duration lifetime
    ) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(reclaimDelay, "reclaimDelay");
        Objects.requireNonNull(lifetime, "lifetime");
        return new Ruin(
                UUID.randomUUID(), formerTown, name, founder, homeblock,
                now, now.plus(reclaimDelay), now.plus(lifetime),
                null, null, null);
    }

    /** The same ruin, taken on again. */
    public Ruin reclaimedBy(final ResidentId who, final TownId asTown, final Instant now) {
        Objects.requireNonNull(who, "who");
        Objects.requireNonNull(asTown, "asTown");
        Objects.requireNonNull(now, "now");
        return new Ruin(
                id, formerTown, name, founder, homeblock, ruinedAt, reclaimableFrom, expiresAt,
                now, who, asTown);
    }

    /**
     * Whether this ruin still holds its land.
     *
     * <p>False once it has been reclaimed as well as once it has lapsed: a reclaimed ruin's chunks
     * belong to the new town, and the row that remains is history rather than territory.</p>
     */
    public boolean stands(final Instant now) {
        return !isReclaimed() && now.isBefore(expiresAt);
    }

    public boolean hasLapsed(final Instant now) {
        return !isReclaimed() && !now.isBefore(expiresAt);
    }

    public boolean isReclaimed() {
        return reclaimedAt != null;
    }

    /**
     * Whether the ruin has lain open long enough to be taken on.
     *
     * <p>Separate from {@link #stands}: a ruin that is too fresh is still there, still unprotected
     * and still being picked over. It simply cannot be rebuilt yet.</p>
     */
    public boolean isReclaimableAt(final Instant now) {
        return stands(now) && !now.isBefore(reclaimableFrom);
    }

    /** How long until it may be taken on, or zero once it may. */
    public Duration untilReclaimable(final Instant now) {
        final Duration left = Duration.between(now, reclaimableFrom);
        return left.isNegative() ? Duration.ZERO : left;
    }

    public Optional<net.riftbreaker.rifttowny.api.ChunkKey> home() {
        return Optional.ofNullable(homeblock);
    }

    public Optional<ResidentId> founderId() {
        return Optional.ofNullable(founder);
    }

    public Optional<TownId> successor() {
        return Optional.ofNullable(reclaimedAs);
    }

    /** How long is left, or zero once it has lapsed. */
    public Duration remaining(final Instant now) {
        final Duration left = Duration.between(now, expiresAt);
        return left.isNegative() ? Duration.ZERO : left;
    }

    public String describe() {
        return name.display() + " (ruin of " + formerTown.value() + ')';
    }
}
