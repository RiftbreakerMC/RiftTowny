package net.riftbreaker.rifttowny.domain.org;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * An offer that has not been answered yet.
 *
 * <p>Exists because joining is a two-sided act. A nation that could admit a town unilaterally would
 * move that town's protection relationship without asking it; a town that could attach itself to any
 * nation would walk into every member town's territory as a citizen. One side offers, the other
 * accepts, and neither alone is enough.</p>
 *
 * <p><strong>Expiry is part of the value, not a sweep detail.</strong> An offer that never lapses is
 * a standing right to join, and a nation that invited a town a year ago has not agreed to whatever
 * that town has become since.</p>
 *
 * @param inviter the organisation making the offer
 * @param invitee who it is addressed to
 * @param createdBy the resident who sent it, or null when it came from an operator command
 */
public record Invitation(
        UUID id,
        OrganisationId inviter,
        Invitee invitee,
        ResidentId createdBy,
        Instant createdAt,
        Instant expiresAt
) {

    /** How long an unanswered offer stands, unless a caller says otherwise. */
    public static final Duration DEFAULT_LIFETIME = Duration.ofDays(7);

    public Invitation {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(inviter, "inviter");
        Objects.requireNonNull(invitee, "invitee");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (!expiresAt.isAfter(createdAt)) {
            // An invitation that expires when it is made is not an invitation, and the failure it
            // produces later - "no invitation found" moments after one was sent - reads as a bug in
            // the accept path rather than as a bad lifetime here.
            throw new IllegalArgumentException(
                    "An invitation must expire after it was created, got " + expiresAt
                            + " for one created at " + createdAt);
        }
    }

    public static Invitation offer(
            final OrganisationId inviter,
            final Invitee invitee,
            final ResidentId createdBy,
            final Instant now
    ) {
        return offer(inviter, invitee, createdBy, now, DEFAULT_LIFETIME);
    }

    public static Invitation offer(
            final OrganisationId inviter,
            final Invitee invitee,
            final ResidentId createdBy,
            final Instant now,
            final Duration lifetime
    ) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(lifetime, "lifetime");
        return new Invitation(
                UUID.randomUUID(), inviter, invitee, createdBy, now, now.plus(lifetime));
    }

    public boolean hasExpired(final Instant now) {
        return !now.isBefore(expiresAt);
    }

    public Optional<ResidentId> author() {
        return Optional.ofNullable(createdBy);
    }

    /**
     * Who an offer is addressed to.
     *
     * <p>A town or a resident. Sealed so the accept path cannot forget one, and typed so a town id
     * cannot be handed in where a resident's was meant — both are thirty-six characters of hex, and
     * the mistake would silently make an offer nobody can accept.</p>
     */
    public sealed interface Invitee {

        /** The stored identifier. */
        UUID value();

        /** The stored discriminator. Renaming one is a migration. */
        String kind();

        static Invitee of(final TownId town) {
            return new TownInvitee(Objects.requireNonNull(town, "town"));
        }

        static Invitee of(final ResidentId resident) {
            return new ResidentInvitee(Objects.requireNonNull(resident, "resident"));
        }

        /** Rebuilds from storage. Empty for a kind this version does not know. */
        static Optional<Invitee> restore(final String kind, final String value) {
            if (kind == null || value == null) {
                return Optional.empty();
            }
            try {
                return switch (kind) {
                    case "TOWN" -> Optional.of(of(TownId.parse(value)));
                    case "RESIDENT" -> Optional.of(of(ResidentId.parse(value)));
                    default -> Optional.empty();
                };
            } catch (final IllegalArgumentException unparseable) {
                return Optional.empty();
            }
        }
    }

    record TownInvitee(TownId town) implements Invitee {
        @Override
        public UUID value() {
            return town.value();
        }

        @Override
        public String kind() {
            return "TOWN";
        }
    }

    record ResidentInvitee(ResidentId resident) implements Invitee {
        @Override
        public UUID value() {
            return resident.value();
        }

        @Override
        public String kind() {
            return "RESIDENT";
        }
    }
}
