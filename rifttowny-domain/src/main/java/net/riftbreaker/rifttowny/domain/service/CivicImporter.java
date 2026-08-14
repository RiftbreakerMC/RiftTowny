package net.riftbreaker.rifttowny.domain.service;

import net.riftbreaker.rifttowny.api.ChunkKey;
import net.riftbreaker.rifttowny.domain.migration.MigrationPlan;
import net.riftbreaker.rifttowny.domain.migration.MigrationReport;
import net.riftbreaker.rifttowny.domain.naming.NameCheck;
import net.riftbreaker.rifttowny.domain.naming.NamePolicy;
import net.riftbreaker.rifttowny.domain.naming.OrganisationName;
import net.riftbreaker.rifttowny.domain.org.Nation;
import net.riftbreaker.rifttowny.domain.org.NationId;
import net.riftbreaker.rifttowny.domain.org.NationProfile;
import net.riftbreaker.rifttowny.domain.org.OrganisationScope;
import net.riftbreaker.rifttowny.domain.org.Resident;
import net.riftbreaker.rifttowny.domain.org.ResidentId;
import net.riftbreaker.rifttowny.domain.org.Town;
import net.riftbreaker.rifttowny.domain.org.TownId;
import net.riftbreaker.rifttowny.domain.org.TownProfile;
import net.riftbreaker.rifttowny.domain.role.RoleBook;
import net.riftbreaker.rifttowny.domain.store.CivicStore;
import net.riftbreaker.rifttowny.domain.territory.Claim;
import net.riftbreaker.rifttowny.domain.territory.ClaimKind;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

/**
 * Brings another plugin's world in.
 *
 * <p>Everything hard about a migration is here rather than in whichever
 * {@link net.riftbreaker.rifttowny.domain.migration.MigrationSource} produced the plan, so it is
 * reasoned about and tested once.</p>
 *
 * <h2>The four rules</h2>
 *
 * <p><strong>It never overwrites.</strong> A town whose name already exists here is skipped and
 * reported, never merged into and never replaced. An import is run by somebody who does not know
 * exactly what is in the file, on a server that may already have towns on it, and the failure mode
 * of guessing is somebody's town quietly becoming somebody else's.</p>
 *
 * <p><strong>It is idempotent.</strong> Everything already present is skipped, so running it twice
 * imports what the first run missed and nothing else. That is what makes a partial failure
 * survivable: fix the cause, run it again.</p>
 *
 * <p><strong>It commits per town, not once.</strong> One transaction over a whole server would be
 * enormous, would hold locks for its duration, and would throw away every good town because one was
 * bad. Per town, a failure costs that town and the run continues — and because the run is
 * idempotent, the retry picks up exactly where it stopped.</p>
 *
 * <p><strong>The order is forced by the aggregates, not chosen.</strong> Residents first, because
 * {@link Town#restore} refuses a mayor who is not one of its residents. Towns next. Nations after
 * towns, because {@link Nation#restore} refuses a capital that is not one of its towns. Claims
 * last, because a claim references a town. Any other order fails on a rule rather than on a
 * lookup.</p>
 */
public final class CivicImporter {

    private final CivicStore store;
    private final NamePolicy namePolicy;
    private final Clock clock;
    private final CivicCacheRefresher refresher;
    private final Predicate<UUID> worldExists;

    /**
     * @param worldExists whether this server actually has a world. A claim in a world that is not
     *        here would be territory nobody can visit and protection nobody can trigger; the
     *        commonest cause is a database copied across without its world folder
     */
    public CivicImporter(
            final CivicStore store,
            final NamePolicy namePolicy,
            final Clock clock,
            final CivicCacheRefresher refresher,
            final Predicate<UUID> worldExists
    ) {
        this.store = Objects.requireNonNull(store, "store");
        this.namePolicy = Objects.requireNonNull(namePolicy, "namePolicy");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.refresher = Objects.requireNonNull(refresher, "refresher");
        this.worldExists = Objects.requireNonNull(worldExists, "worldExists");
    }

    /**
     * Says what an import would do, writing nothing.
     *
     * <p>The default, and the reason the plan is read whole before anything is applied. An import
     * is the one operation here that is both enormous and irreversible.</p>
     */
    public CompletableFuture<MigrationReport> preview(final MigrationPlan plan) {
        return run(plan, false);
    }

    /** Does it. */
    public CompletableFuture<MigrationReport> apply(final MigrationPlan plan) {
        return run(plan, true);
    }

    private CompletableFuture<MigrationReport> run(final MigrationPlan plan, final boolean apply) {
        Objects.requireNonNull(plan, "plan");
        final MigrationReport.Builder report = new MigrationReport.Builder();

        // Read once, up front: what this server already has. Everything below tests against this
        // rather than querying per row, which turns an import of three hundred towns from six
        // hundred lookups into two.
        return store.inTransaction(transaction -> {
            final Map<String, TownId> existingTowns = new HashMap<>();
            for (final Town town : transaction.towns().all()) {
                existingTowns.put(town.name().normalised(), town.id());
            }
            final Set<String> existingNations = new HashSet<>();
            for (final Nation nation : transaction.nations().all()) {
                existingNations.add(nation.name().normalised());
            }
            return new Existing(existingTowns, existingNations);
        }).thenCompose(existing -> {
            final Plan prepared = validate(plan, existing, report);
            if (!apply) {
                return CompletableFuture.completedFuture(report.build(false));
            }
            return write(prepared, report).thenApply(ignored -> report.build(true));
        });
    }

    // --- validation ------------------------------------------------------------------------------

    /**
     * Works out what can actually be brought in, reporting everything that cannot.
     *
     * <p>Pure: it writes nothing and touches no storage, which is what lets a dry run be exactly the
     * same computation as the real thing.</p>
     */
    private Plan validate(
            final MigrationPlan plan,
            final Existing existing,
            final MigrationReport.Builder report
    ) {
        final Map<UUID, MigrationPlan.Resident> residents = new LinkedHashMap<>();
        for (final MigrationPlan.Resident resident : plan.residents()) {
            if (residents.putIfAbsent(resident.id(), resident) != null) {
                report.problem(MigrationReport.Problem.skipped("resident", resident.name(),
                        "the same account appears twice in the source"));
            }
        }

        // Counted here rather than while writing, so a dry run and a real run report identical
        // numbers by construction. Counting at write time made preview say "0 towns" for an import
        // that would bring in a hundred, which is the one thing a dry run must never do.
        final Map<String, PreparedTown> towns = new LinkedHashMap<>();
        for (final MigrationPlan.Town town : plan.towns()) {
            prepareTown(town, residents, existing, towns, report).ifPresent(prepared -> {
                towns.put(key(town.name()), prepared);
                report.town();
                prepared.members().forEach(ignored -> report.resident());
            });
        }

        final List<PreparedNation> nations = new ArrayList<>();
        for (final MigrationPlan.Nation nation : plan.nations()) {
            prepareNation(nation, towns, existing, report).ifPresent(prepared -> {
                nations.add(prepared);
                report.nation();
            });
        }

        final Map<String, List<Claim>> claims = new LinkedHashMap<>();
        final Set<ChunkKey> taken = new HashSet<>();
        for (final MigrationPlan.Claim claim : plan.claims()) {
            prepareClaim(claim, towns, taken, report).ifPresent(prepared -> {
                claims.computeIfAbsent(key(claim.townName()), ignored -> new ArrayList<>())
                        .add(prepared);
                report.claim();
            });
        }

        return new Plan(towns, nations, claims);
    }

    private Optional<PreparedTown> prepareTown(
            final MigrationPlan.Town town,
            final Map<UUID, MigrationPlan.Resident> residents,
            final Existing existing,
            final Map<String, PreparedTown> alreadyPrepared,
            final MigrationReport.Builder report
    ) {
        final NameCheck check = namePolicy.check(town.name());
        if (!(check instanceof NameCheck.Accepted accepted)) {
            // A name this server's own policy refuses. Renaming it automatically was considered and
            // rejected: a town arriving under a name its members do not recognise is worse than one
            // an operator is told to rename by hand.
            report.problem(MigrationReport.Problem.skipped("town", town.name(),
                    "the name is not one this server accepts: " + check.problems()));
            return Optional.empty();
        }
        if (existing.towns().containsKey(accepted.name().normalised())) {
            report.problem(MigrationReport.Problem.skipped("town", town.name(),
                    "a town of that name is already here, and an import never overwrites one"));
            return Optional.empty();
        }
        if (alreadyPrepared.containsKey(key(town.name()))) {
            report.problem(MigrationReport.Problem.skipped("town", town.name(),
                    "two towns in the source share that name"));
            return Optional.empty();
        }
        if (!residents.containsKey(town.mayorId())) {
            // Founding takes a leader, and Town.restore refuses a mayor who is not a resident.
            report.problem(MigrationReport.Problem.skipped("town", town.name(),
                    "its mayor is not in the source, so there is nobody to lead it"));
            return Optional.empty();
        }

        final List<MigrationPlan.Resident> members = new ArrayList<>();
        for (final MigrationPlan.Resident resident : residents.values()) {
            if (resident.hasTown() && key(resident.townName()).equals(key(town.name()))) {
                members.add(resident);
            }
        }
        if (members.stream().noneMatch(member -> member.id().equals(town.mayorId()))) {
            // The mayor exists but their own record does not name this town. Taken as membership
            // anyway rather than refused: a town that names its mayor is the stronger statement,
            // and the alternative loses the whole town over one inconsistent row.
            report.problem(MigrationReport.Problem.adjusted("town", town.name(),
                    "its mayor's record did not name it; admitted them anyway"));
            members.add(residents.get(town.mayorId()));
        }
        return Optional.of(new PreparedTown(TownId.random(), accepted.name(), town, members));
    }

    private Optional<PreparedNation> prepareNation(
            final MigrationPlan.Nation nation,
            final Map<String, PreparedTown> towns,
            final Existing existing,
            final MigrationReport.Builder report
    ) {
        final NameCheck check = namePolicy.check(nation.name());
        if (!(check instanceof NameCheck.Accepted accepted)) {
            report.problem(MigrationReport.Problem.skipped("nation", nation.name(),
                    "the name is not one this server accepts: " + check.problems()));
            return Optional.empty();
        }
        if (existing.nations().contains(accepted.name().normalised())) {
            report.problem(MigrationReport.Problem.skipped("nation", nation.name(),
                    "a nation of that name is already here"));
            return Optional.empty();
        }
        final PreparedTown capital = towns.get(key(nation.capitalTownName()));
        if (capital == null) {
            // Nation.restore refuses a capital that is not one of its towns, so a nation whose
            // capital was skipped cannot be built at all.
            report.problem(MigrationReport.Problem.skipped("nation", nation.name(),
                    "its capital '" + nation.capitalTownName() + "' was not imported"));
            return Optional.empty();
        }

        final List<PreparedTown> members = new ArrayList<>();
        for (final PreparedTown town : towns.values()) {
            if (town.source().hasNation()
                    && key(town.source().nationName()).equals(key(nation.name()))) {
                members.add(town);
            }
        }
        if (!members.contains(capital)) {
            members.add(capital);
        }
        return Optional.of(new PreparedNation(NationId.random(), accepted.name(), nation, capital,
                members));
    }

    private Optional<Claim> prepareClaim(
            final MigrationPlan.Claim claim,
            final Map<String, PreparedTown> towns,
            final Set<ChunkKey> taken,
            final MigrationReport.Builder report
    ) {
        final PreparedTown town = towns.get(key(claim.townName()));
        if (town == null) {
            // Reported once per town rather than once per chunk: a skipped town takes hundreds of
            // claims with it, and a report with four hundred identical lines is not a report.
            return Optional.empty();
        }
        if (!worldExists.test(claim.worldId())) {
            report.problem(MigrationReport.Problem.skipped("claim", claim.townName(),
                    "world " + claim.worldId() + " is not on this server"));
            return Optional.empty();
        }
        final ChunkKey chunk = new ChunkKey(claim.worldId(), claim.chunkX(), claim.chunkZ());
        if (!taken.add(chunk)) {
            report.problem(MigrationReport.Problem.skipped("claim", claim.townName(),
                    "two towns claim " + chunk + "; only the first was taken"));
            return Optional.empty();
        }

        final ClaimKind kind = claim.homeblock()
                ? ClaimKind.HOMEBLOCK
                : claim.outpost() ? ClaimKind.OUTPOST : ClaimKind.ORDINARY;
        Claim prepared = Claim.of(chunk, town.id(), kind, clock.instant());
        if (claim.ownerId() != null && town.members().stream()
                .anyMatch(member -> member.id().equals(claim.ownerId()))) {
            prepared = prepared.heldBy(ResidentId.of(claim.ownerId()));
        }
        return Optional.of(prepared);
    }

    // --- writing ---------------------------------------------------------------------------------

    /** One transaction per town, then one per nation. A failure costs that one and no other. */
    private CompletableFuture<Void> write(final Plan plan, final MigrationReport.Builder report) {
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);

        for (final PreparedTown town : plan.towns().values()) {
            final List<Claim> land = plan.claims().getOrDefault(key(town.source().name()), List.of());
            chain = chain.thenCompose(ignored -> writeTown(town, land, report));
        }
        for (final PreparedNation nation : plan.nations()) {
            chain = chain.thenCompose(ignored -> writeNation(nation, report));
        }
        return chain;
    }

    private CompletableFuture<Void> writeTown(
            final PreparedTown town, final List<Claim> land, final MigrationReport.Builder report) {
        return store.<Void>inTransaction(transaction -> {
            final Instant founded = Objects.requireNonNullElseGet(
                    town.source().founded(), clock::instant);

            final Set<ResidentId> members = new java.util.LinkedHashSet<>();
            for (final MigrationPlan.Resident member : town.members()) {
                final ResidentId id = ResidentId.of(member.id());
                transaction.residents().save(Resident.restore(id, member.name(), town.id(),
                        Objects.requireNonNullElse(member.joined(), founded),
                        Objects.requireNonNullElse(member.lastSeen(), founded)));
                members.add(id);
            }

            transaction.towns().save(Town.restore(town.id(), town.name(),
                    ResidentId.of(town.source().mayorId()), null, UUID.randomUUID(),
                    members, Set.of(), profileOf(town.source()), founded));
            // Without a role book the town's land refuses every action, so this is not optional
            // decoration - it is the difference between an imported town and a bricked one.
            transaction.roles().save(
                    RoleBook.defaultsFor(OrganisationScope.TOWN, town.id().value(), founded));

            for (final Claim claim : land) {
                transaction.claims().insert(claim);
            }
            return null;
        }).thenCompose(ignored -> refresher.refresh(town.id()));
    }

    private CompletableFuture<Void> writeNation(
            final PreparedNation nation, final MigrationReport.Builder report) {
        return store.<Void>inTransaction(transaction -> {
            final Instant founded = Objects.requireNonNullElseGet(
                    nation.source().founded(), clock::instant);
            final Set<TownId> members = new java.util.LinkedHashSet<>();
            for (final PreparedTown town : nation.members()) {
                members.add(town.id());
            }

            // The nation row first: rt_town.nation_id references it, so pointing a town at an id
            // that does not exist yet is a constraint violation rather than a sentence.
            transaction.nations().save(Nation.restore(nation.id(), nation.name(),
                    ResidentId.of(nation.source().kingId()), nation.capital().id(),
                    UUID.randomUUID(), members, profileOf(nation.source()), founded));
            transaction.roles().save(
                    RoleBook.defaultsFor(OrganisationScope.NATION, nation.id().value(), founded));

            for (final PreparedTown town : nation.members()) {
                transaction.towns().find(town.id())
                        .flatMap(found -> found.joinNation(nation.id()).value())
                        .ifPresent(transaction.towns()::save);
            }
            return null;
        }).thenCompose(ignored -> refresher.refreshNation(nation.id()));
    }

    private static TownProfile profileOf(final MigrationPlan.Town town) {
        return TownProfile.empty()
                .withBoard(town.board())
                .withTag(town.tag())
                .withOpen(town.open())
                .withPublicSpawn(town.publicSpawn())
                .withNeutral(town.neutral());
    }

    private static NationProfile profileOf(final MigrationPlan.Nation nation) {
        return NationProfile.empty()
                .withBoard(nation.board())
                .withTag(nation.tag())
                .withNeutral(nation.neutral());
    }

    /** Names are matched case-insensitively, because a source's capitalisation is not a fact. */
    private static String key(final String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    private record Existing(Map<String, TownId> towns, Set<String> nations) {
    }

    private record PreparedTown(
            TownId id,
            OrganisationName name,
            MigrationPlan.Town source,
            List<MigrationPlan.Resident> members) {
    }

    private record PreparedNation(
            NationId id,
            OrganisationName name,
            MigrationPlan.Nation source,
            PreparedTown capital,
            List<PreparedTown> members) {
    }

    private record Plan(
            Map<String, PreparedTown> towns,
            List<PreparedNation> nations,
            Map<String, List<Claim>> claims) {
    }
}
