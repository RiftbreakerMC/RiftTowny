package net.riftbreaker.rifttowny.domain.directory;

import net.riftbreaker.rifttowny.domain.civic.CivicCache;
import net.riftbreaker.rifttowny.domain.civic.TownFacts;
import net.riftbreaker.rifttowny.domain.org.Nation;
import net.riftbreaker.rifttowny.domain.org.NationId;
import net.riftbreaker.rifttowny.domain.org.ResidentId;
import net.riftbreaker.rifttowny.domain.org.TownId;
import net.riftbreaker.rifttowny.domain.territory.TerritoryIndex;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Every read-only listing, answered from memory.
 *
 * <p>This is the whole reason the caches hold complete data rather than only what protection needs.
 * {@code /town list} on a server with three hundred towns is one pass over two maps here, and would
 * otherwise be a query returning three hundred rows every time somebody is curious — a command
 * players run constantly, and one of the classic ways a town plugin becomes the thing that makes a
 * server stutter.</p>
 *
 * <p>Nothing here can refuse and nothing here writes. It joins {@link CivicCache} to
 * {@link TerritoryIndex}, sorts, and pages.</p>
 *
 * <p><strong>Nations are the exception.</strong> They are not cached — no listener asks about one on
 * a hot path — so the caller reads them from storage and hands them in. That keeps the database
 * access at the caller, where it is already asynchronous, rather than hidden behind a method that
 * looks as cheap as the rest of this class.</p>
 */
public final class CivicDirectory {

    private final CivicCache towns;
    private final TerritoryIndex claims;

    public CivicDirectory(final CivicCache towns, final TerritoryIndex claims) {
        this.towns = Objects.requireNonNull(towns, "towns");
        this.claims = Objects.requireNonNull(claims, "claims");
    }

    // --- towns ---------------------------------------------------------------------------------

    /** Every town, in no particular order. */
    public List<TownSummary> allTowns() {
        final Map<TownId, Integer> land = claims.countsByTown();
        final List<TownSummary> summaries = new ArrayList<>();
        for (final TownId id : towns.townIds()) {
            towns.town(id).ifPresent(facts -> summaries.add(summarise(facts, land)));
        }
        return List.copyOf(summaries);
    }

    /**
     * Every town's name, and nothing else.
     *
     * <p>For tab completion, which runs on the server thread on every keystroke. {@link #allTowns()}
     * would build a chunk count per town each time somebody pressed a key, which is a full pass over
     * the claim index to answer a question that never needed one.</p>
     */
    public List<String> townNames() {
        final List<String> named = new ArrayList<>();
        for (final TownId id : towns.townIds()) {
            towns.town(id).ifPresent(facts -> named.add(facts.displayName()));
        }
        return List.copyOf(named);
    }

    /** One page of towns. */
    public Page<TownSummary> towns(final CivicSort sort, final int page, final int pageSize) {
        Objects.requireNonNull(sort, "sort");
        final List<TownSummary> all = new ArrayList<>(allTowns());
        all.sort(sort.order());
        return Page.of(all, page, pageSize);
    }

    /** One town, if this cache knows it. */
    public Optional<TownSummary> town(final TownId id) {
        final Map<TownId, Integer> land = claims.countsByTown();
        return towns.town(id).map(facts -> summarise(facts, land));
    }

    /** One town by the name a player typed, matched case-insensitively. */
    public Optional<TownSummary> townNamed(final String name) {
        final Map<TownId, Integer> land = claims.countsByTown();
        return factsNamed(name).map(facts -> summarise(facts, land));
    }

    /**
     * A town's cached facts, for a screen that needs more than a listing row.
     *
     * <p>Exposed so a command can reach the aggregate without being handed the whole cache. A
     * command that holds the cache can also be tempted to write to it, and there is exactly one
     * thing allowed to do that.</p>
     */
    public Optional<TownFacts> facts(final TownId id) {
        return towns.town(id);
    }

    /**
     * A town's cached facts by name.
     *
     * <p>Linear over the towns held, which is fine for a command somebody types and would not be
     * for anything on a movement path. The database has an index on the normalised name for the
     * paths that need one; this exists so looking at a town costs no query at all.</p>
     */
    public Optional<TownFacts> factsNamed(final String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        final String wanted = name.trim().toLowerCase(java.util.Locale.ROOT);
        for (final TownId id : towns.townIds()) {
            final Optional<TownFacts> facts = towns.town(id);
            if (facts.isPresent()
                    && facts.get().displayName().toLowerCase(java.util.Locale.ROOT).equals(wanted)) {
                return facts;
            }
        }
        return Optional.empty();
    }

    // --- nations -------------------------------------------------------------------------------

    /**
     * Every nation handed in, with its people and land summed through its towns.
     *
     * <p>A member town this cache does not know contributes nothing rather than throwing. The
     * listing is the wrong place to discover an inconsistency, and a nation whose totals are a
     * little low is a far better outcome than a command that fails outright.</p>
     */
    public List<NationSummary> nations(final Collection<Nation> loaded) {
        Objects.requireNonNull(loaded, "loaded");
        final Map<TownId, Integer> land = claims.countsByTown();
        final List<NationSummary> summaries = new ArrayList<>(loaded.size());
        for (final Nation nation : loaded) {
            int residents = 0;
            int chunks = 0;
            for (final TownId member : nation.towns()) {
                residents += towns.town(member).map(facts -> facts.town().residentCount()).orElse(0);
                chunks += land.getOrDefault(member, 0);
            }
            summaries.add(new NationSummary(
                    nation.id(),
                    nation.name().display(),
                    nation.leader(),
                    nation.capital(),
                    nation.towns().size(),
                    residents,
                    chunks,
                    nation.createdAt()));
        }
        return List.copyOf(summaries);
    }

    /** One page of nations, from a list already read from storage. */
    public Page<NationSummary> nations(
            final Collection<Nation> loaded,
            final CivicSort sort,
            final int page,
            final int pageSize
    ) {
        Objects.requireNonNull(sort, "sort");
        final List<NationSummary> all = new ArrayList<>(nations(loaded));
        all.sort(sort.order());
        return Page.of(all, page, pageSize);
    }

    // --- people --------------------------------------------------------------------------------

    /**
     * Which town somebody belongs to, and how much of it, without a query.
     *
     * <p>Empty for anybody who belongs to no town, which is most players on most servers.</p>
     */
    public Optional<TownSummary> townOf(final ResidentId who) {
        return towns.townOf(who).flatMap(this::town);
    }

    /** The nation somebody belongs to through their town. */
    public Optional<NationId> nationOf(final ResidentId who) {
        return towns.nationOfResident(who);
    }

    /** The cached facts of whichever town somebody belongs to. */
    public Optional<TownFacts> factsOf(final ResidentId who) {
        return towns.townFactsOf(who);
    }

    /** The roles a town has given somebody, named and in the town's own order. */
    public List<String> roleNamesOf(final TownId town, final ResidentId who) {
        return towns.town(town).map(facts -> roleNames(facts, who)).orElseGet(List::of);
    }

    /**
     * Everybody in a town, in a stable order.
     *
     * <p>The mayor first, then everybody else by the name the caller resolves. Ordering by name is
     * the caller's job because names are not in the cache this reads; ordering the mayor first is
     * not, because that is about standing rather than presentation.</p>
     */
    public List<ResidentId> residentsOf(final TownId town) {
        return towns.town(town)
                .map(facts -> {
                    final List<ResidentId> ordered = new ArrayList<>();
                    ordered.add(facts.town().mayor());
                    facts.residents().stream()
                            .filter(resident -> !resident.equals(facts.town().mayor()))
                            .forEach(ordered::add);
                    return List.copyOf(ordered);
                })
                .orElseGet(List::of);
    }

    /**
     * One player's record.
     *
     * <p>The resident row comes from storage because it holds two things no cache does — when they
     * joined and when they were last seen — and their plot count comes from storage for the same
     * reason it is a count rather than a list. Everything else is joined here from memory.</p>
     *
     * @param resident the stored row, which is the identity this describes
     * @param plotsHeld already counted by the caller
     */
    public ResidentProfile profileOf(
            final net.riftbreaker.rifttowny.domain.org.Resident resident, final int plotsHeld) {
        Objects.requireNonNull(resident, "resident");
        final Optional<TownFacts> facts = towns.townFactsOf(resident.id());
        final Map<TownId, Integer> land = claims.countsByTown();
        return new ResidentProfile(
                resident.id(),
                resident.lastKnownName(),
                facts.map(found -> summarise(found, land)).orElse(null),
                facts.flatMap(TownFacts::nation).orElse(null),
                facts.map(found -> found.standingOf(resident.id()))
                        .orElse(net.riftbreaker.rifttowny.domain.role.SystemRole.VISITOR),
                facts.map(found -> roleNames(found, resident.id())).orElseGet(List::of),
                plotsHeld,
                resident.joinedAt(),
                resident.lastSeenAt());
    }

    /**
     * The roles a town has given somebody, in the town's own order.
     *
     * <p>The book's order rather than the assignment order: a town that has arranged its roles by
     * authority expects to see them that way, and the order a resident happened to be given them in
     * is an accident of history that means nothing to anybody reading it.</p>
     */
    private static List<String> roleNames(final TownFacts facts, final ResidentId who) {
        final var held = facts.roles().rolesOf(who);
        final List<String> named = new ArrayList<>(held.size());
        facts.roles().ordered().stream()
                .filter(role -> held.contains(role.id()))
                .forEach(role -> named.add(role.name()));
        return List.copyOf(named);
    }

    // --- diagnostics ---------------------------------------------------------------------------

    /** How many towns this directory can describe, for a status line. */
    public int size() {
        return towns.cachedTowns();
    }

    private static TownSummary summarise(final TownFacts facts, final Map<TownId, Integer> land) {
        return new TownSummary(
                facts.id(),
                facts.displayName(),
                facts.town().mayor(),
                facts.town().residentCount(),
                land.getOrDefault(facts.id(), 0),
                facts.nation().orElse(null),
                facts.town().createdAt());
    }
}
