package net.riftbreaker.rifttowny.domain.migration;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Everything an import is going to bring in, read out of somewhere else and not yet applied.
 *
 * <p>Deliberately flat, and deliberately made of names rather than references. A source has no
 * RiftTowny ids to give — it is describing another plugin's world — so a town names its mayor and a
 * claim names its town, and the importer is what resolves those into real aggregates. Trying to
 * hand out ids at read time would mean the reader needed the database, and a reader that needs the
 * database cannot be tested against a fixture.</p>
 *
 * <p><strong>Why this exists rather than a reader that writes as it goes.</strong> An import is the
 * one operation on this server that is both enormous and irreversible, and the operator running it
 * has no idea what is in the file. Reading the whole thing first is what makes a dry run possible:
 * every problem can be reported before a single row is written, so the answer to "what will this
 * do" arrives before it does it rather than afterwards.</p>
 */
public record MigrationPlan(
        String sourceDescription,
        List<Resident> residents,
        List<Town> towns,
        List<Nation> nations,
        List<Claim> claims
) {

    public MigrationPlan {
        sourceDescription = sourceDescription == null ? "unknown source" : sourceDescription;
        residents = List.copyOf(Objects.requireNonNullElse(residents, List.of()));
        towns = List.copyOf(Objects.requireNonNullElse(towns, List.of()));
        nations = List.copyOf(Objects.requireNonNullElse(nations, List.of()));
        claims = List.copyOf(Objects.requireNonNullElse(claims, List.of()));
    }

    public static MigrationPlan empty() {
        return new MigrationPlan("nothing", List.of(), List.of(), List.of(), List.of());
    }

    /** How much there is, for the line an operator reads before deciding to go ahead. */
    public String describe() {
        return residents.size() + " resident(s), " + towns.size() + " town(s), "
                + nations.size() + " nation(s), " + claims.size() + " claim(s) from "
                + sourceDescription;
    }

    public boolean isEmpty() {
        return residents.isEmpty() && towns.isEmpty() && nations.isEmpty() && claims.isEmpty();
    }

    /**
     * A player.
     *
     * @param id the account's UUID, which is the one identifier that means the same thing in both
     *        plugins and the only safe way to match a person. Names change
     * @param townName the town they belonged to, or null. Their membership is applied from here
     *        rather than from the town's own list, so the two cannot disagree
     */
    public record Resident(UUID id, String name, String townName, Instant joined, Instant lastSeen) {

        public Resident {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(name, "name");
        }

        public boolean hasTown() {
            return townName != null && !townName.isBlank();
        }
    }

    /**
     * A town.
     *
     * @param mayorName resolved against the residents in the same plan. A town whose mayor is not
     *        in the plan cannot be founded, because founding takes a leader
     * @param board carried across because it is one of the few things a town writes itself, and
     *        losing it in a migration is losing something a player typed
     */
    public record Town(
            String name,
            UUID mayorId,
            String mayorName,
            String nationName,
            String board,
            String tag,
            boolean open,
            boolean publicSpawn,
            boolean neutral,
            Instant founded
    ) {

        public Town {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(mayorId, "mayorId");
        }

        public boolean hasNation() {
            return nationName != null && !nationName.isBlank();
        }
    }

    /** A nation. Its member towns come from each town's own {@code nationName}, not from here. */
    public record Nation(
            String name,
            UUID kingId,
            String capitalTownName,
            String board,
            String tag,
            boolean neutral,
            Instant founded
    ) {

        public Nation {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(kingId, "kingId");
            Objects.requireNonNull(capitalTownName, "capitalTownName");
        }
    }

    /**
     * One claimed chunk.
     *
     * @param worldId the world's UUID. A claim in a world this server does not have is reported and
     *        skipped rather than imported into nowhere — the commonest real cause being a migration
     *        that brings the database without the world folder
     * @param ownerId the resident holding it as a plot, or null when the town holds it directly
     */
    public record Claim(
            String townName,
            UUID worldId,
            int chunkX,
            int chunkZ,
            boolean homeblock,
            boolean outpost,
            UUID ownerId
    ) {

        public Claim {
            Objects.requireNonNull(townName, "townName");
            Objects.requireNonNull(worldId, "worldId");
        }
    }
}
