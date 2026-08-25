package net.riftbreaker.rifttowny.domain.directory;

import net.riftbreaker.rifttowny.api.ChunkKey;
import net.riftbreaker.rifttowny.domain.civic.CivicCache;
import net.riftbreaker.rifttowny.domain.civic.CivicFixture;
import net.riftbreaker.rifttowny.domain.civic.TownFacts;
import net.riftbreaker.rifttowny.domain.naming.NamePolicy;
import net.riftbreaker.rifttowny.domain.org.Nation;
import net.riftbreaker.rifttowny.domain.org.NationId;
import net.riftbreaker.rifttowny.domain.org.Resident;
import net.riftbreaker.rifttowny.domain.org.ResidentId;
import net.riftbreaker.rifttowny.domain.org.Town;
import net.riftbreaker.rifttowny.domain.org.TownId;
import net.riftbreaker.rifttowny.domain.role.Role;
import net.riftbreaker.rifttowny.domain.role.RoleBook;
import net.riftbreaker.rifttowny.domain.role.SystemRole;
import net.riftbreaker.rifttowny.domain.territory.Claim;
import net.riftbreaker.rifttowny.domain.territory.ClaimKind;
import net.riftbreaker.rifttowny.domain.territory.TerritoryIndex;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The listings.
 *
 * <p>Everything here is a join between two caches, which is exactly where a read model goes wrong:
 * a town counted with another town's land, a nation whose totals silently omit a member, a sort that
 * reorders equal rows between one page and the next.</p>
 */
class CivicDirectoryTest {

    private static final UUID WORLD = UUID.randomUUID();

    private CivicCache towns;
    private TerritoryIndex claims;
    private CivicDirectory directory;

    @BeforeEach
    void setUp() {
        towns = CivicCache.empty();
        claims = TerritoryIndex.empty();
        directory = new CivicDirectory(towns, claims);
    }

    private Town foundedTown(final String name, final Instant when, final ResidentId... residents) {
        final ResidentId mayor = residents.length == 0 ? CivicFixture.resident() : residents[0];
        Town town = Town.found(TownId.random(),
                NamePolicy.defaults().check(name).accepted().orElseThrow(),
                mayor, UUID.randomUUID(), when);
        for (int index = 1; index < residents.length; index++) {
            town = town.admit(residents[index]).orElseThrow();
        }
        return town;
    }

    private void remember(final Town town) {
        towns.remember(CivicFixture.facts(town));
    }

    private void claimChunks(final Town town, final int howMany) {
        for (int index = 0; index < howMany; index++) {
            claims.put(Claim.of(new ChunkKey(WORLD, index, 0), town.id(),
                    index == 0 ? ClaimKind.HOMEBLOCK : ClaimKind.ORDINARY, CivicFixture.NOW));
        }
    }

    @Test
    @DisplayName("a town's land is counted from the claim index, not from the town")
    void landComesFromTheIndex() {
        final Town ashford = foundedTown("Ashford", CivicFixture.NOW, CivicFixture.resident());
        remember(ashford);
        claimChunks(ashford, 4);

        final TownSummary summary = directory.town(ashford.id()).orElseThrow();

        assertThat(summary.chunks()).isEqualTo(4);
        assertThat(summary.residents()).isEqualTo(1);
        assertThat(summary.name()).isEqualTo("Ashford");
    }

    @Test
    @DisplayName("one town's land is never counted against another")
    void landIsNotSharedBetweenTowns() {
        final Town ashford = foundedTown("Ashford", CivicFixture.NOW, CivicFixture.resident());
        final Town highholm = foundedTown("Highholm", CivicFixture.NOW, CivicFixture.resident());
        remember(ashford);
        remember(highholm);
        claimChunks(ashford, 3);
        claims.put(Claim.of(new ChunkKey(WORLD, 50, 50), highholm.id(),
                ClaimKind.HOMEBLOCK, CivicFixture.NOW));

        assertThat(directory.town(ashford.id()).orElseThrow().chunks()).isEqualTo(3);
        assertThat(directory.town(highholm.id()).orElseThrow().chunks()).isEqualTo(1);
    }

    @Test
    @DisplayName("a town with no land at all is still listed")
    void landlessTownsAreStillListed() {
        final Town ashford = foundedTown("Ashford", CivicFixture.NOW, CivicFixture.resident());
        remember(ashford);

        assertThat(directory.town(ashford.id()).orElseThrow().chunks()).isZero();
        assertThat(directory.allTowns()).hasSize(1);
    }

    @Test
    @DisplayName("sorting by residents puts the biggest first")
    void sortsByResidents() {
        final Town small = foundedTown("Ashford", CivicFixture.NOW, CivicFixture.resident());
        final Town big = foundedTown("Highholm", CivicFixture.NOW,
                CivicFixture.resident(), CivicFixture.resident(), CivicFixture.resident());
        remember(small);
        remember(big);

        assertThat(directory.towns(CivicSort.RESIDENTS, 1, 10).items())
                .extracting(TownSummary::name)
                .containsExactly("Highholm", "Ashford");
    }

    @Test
    @DisplayName("sorting by age puts the founding towns of a server first")
    void sortsByAge() {
        remember(foundedTown("Newford", CivicFixture.NOW.plusSeconds(86_400), CivicFixture.resident()));
        remember(foundedTown("Oldholm", CivicFixture.NOW, CivicFixture.resident()));

        assertThat(directory.towns(CivicSort.AGE, 1, 10).items())
                .extracting(TownSummary::name)
                .containsExactly("Oldholm", "Newford");
    }

    @Test
    @DisplayName("towns that tie on size still come out in the same order every time")
    void tiesAreBrokenStably() {
        // The paging bug this prevents: two equal rows swapping places between page one and page two
        // shows a player the same town twice and hides another entirely.
        remember(foundedTown("Ashford", CivicFixture.NOW, CivicFixture.resident()));
        remember(foundedTown("Bexley", CivicFixture.NOW, CivicFixture.resident()));
        remember(foundedTown("Caldwell", CivicFixture.NOW, CivicFixture.resident()));

        final List<String> first = directory.towns(CivicSort.RESIDENTS, 1, 10).items()
                .stream().map(TownSummary::name).toList();
        final List<String> again = directory.towns(CivicSort.RESIDENTS, 1, 10).items()
                .stream().map(TownSummary::name).toList();

        assertThat(first).containsExactly("Ashford", "Bexley", "Caldwell").isEqualTo(again);
    }

    @Test
    @DisplayName("a town is found by name whatever case it is typed in")
    void findsTownsByNameCaseInsensitively() {
        remember(foundedTown("Ashford", CivicFixture.NOW, CivicFixture.resident()));

        assertThat(directory.townNamed("ASHFORD")).isPresent();
        assertThat(directory.townNamed("  ashford  ")).isPresent();
        assertThat(directory.townNamed("Ashfor")).isEmpty();
        assertThat(directory.townNamed("")).isEmpty();
        assertThat(directory.townNamed(null)).isEmpty();
    }

    @Test
    @DisplayName("a nation's people and land are summed through its towns")
    void nationsSumThroughTheirTowns() {
        final ResidentId king = CivicFixture.resident();
        final Town capital = foundedTown("Ashford", CivicFixture.NOW, king, CivicFixture.resident());
        final Town member = foundedTown("Highholm", CivicFixture.NOW, CivicFixture.resident());
        claimChunks(capital, 3);
        claims.put(Claim.of(new ChunkKey(WORLD, 90, 90), member.id(),
                ClaimKind.HOMEBLOCK, CivicFixture.NOW));

        final NationId nationId = NationId.random();
        final Nation nation = Nation.restore(nationId,
                NamePolicy.defaults().check("Valen").accepted().orElseThrow(),
                king, capital.id(), UUID.randomUUID(),
                java.util.Set.of(capital.id(), member.id()), CivicFixture.NOW);

        remember(capital.joinNation(nationId).orElseThrow());
        remember(member.joinNation(nationId).orElseThrow());

        final NationSummary summary = directory.nations(List.of(nation)).getFirst();

        assertThat(summary.towns()).isEqualTo(2);
        assertThat(summary.residents()).isEqualTo(3);
        assertThat(summary.chunks()).isEqualTo(4);
    }

    @Test
    @DisplayName("a nation whose member town is not cached is listed low rather than not at all")
    void unknownMemberTownsContributeNothing() {
        final ResidentId king = CivicFixture.resident();
        final Town capital = foundedTown("Ashford", CivicFixture.NOW, king);
        remember(capital);

        final TownId strangerId = TownId.random();
        final Nation nation = Nation.restore(NationId.random(),
                NamePolicy.defaults().check("Valen").accepted().orElseThrow(),
                king, capital.id(), UUID.randomUUID(),
                java.util.Set.of(capital.id(), strangerId), CivicFixture.NOW);

        final NationSummary summary = directory.nations(List.of(nation)).getFirst();

        // A listing is the wrong place to discover an inconsistency: a total that is a little low
        // beats a command that fails outright while somebody is trying to look at the server.
        assertThat(summary.towns()).isEqualTo(2);
        assertThat(summary.residents()).isEqualTo(1);
    }

    @Test
    @DisplayName("residents come out mayor first")
    void residentsAreOrderedMayorFirst() {
        final ResidentId mayor = CivicFixture.resident();
        final ResidentId other = CivicFixture.resident();
        final Town ashford = foundedTown("Ashford", CivicFixture.NOW, mayor, other);
        remember(ashford);

        assertThat(directory.residentsOf(ashford.id())).startsWith(mayor).contains(other);
    }

    @Test
    @DisplayName("a profile carries the town, the standing and the roles the town gave them")
    void profileJoinsEverythingAboutAPlayer() {
        final ResidentId mayor = CivicFixture.resident();
        final ResidentId member = CivicFixture.resident();
        final Town ashford = foundedTown("Ashford", CivicFixture.NOW, mayor, member);

        final RoleBook book = CivicFixture.roles(ashford);
        final Role sheriff = Role.custom(
                net.riftbreaker.rifttowny.domain.role.RoleId.random(),
                net.riftbreaker.rifttowny.domain.org.OrganisationScope.TOWN,
                ashford.id().value(), "Sheriff", 500,
                java.util.Set.of(net.riftbreaker.rifttowny.domain.role.Permission.BUILD),
                CivicFixture.NOW);
        final RoleBook withSheriff = book.create(sheriff, java.util.Set.of()).orElseThrow()
                .assign(member, sheriff.id()).orElseThrow();
        towns.remember(TownFacts.of(ashford, withSheriff));

        final Resident stored = Resident.restore(member, "Ada", ashford.id(),
                CivicFixture.NOW, CivicFixture.NOW.plusSeconds(3_600));

        final ResidentProfile profile = directory.profileOf(stored, 2);

        assertThat(profile.name()).isEqualTo("Ada");
        assertThat(profile.townSummary()).map(TownSummary::name).contains("Ashford");
        assertThat(profile.standing()).isEqualTo(SystemRole.MEMBER);
        assertThat(profile.roles()).contains("Sheriff");
        assertThat(profile.plotsHeld()).isEqualTo(2);
        assertThat(profile.isMayor()).isFalse();
    }

    @Test
    @DisplayName("a player in no town has a profile rather than none")
    void townlessPlayersStillHaveAProfile() {
        final Resident wanderer = Resident.newcomer(CivicFixture.resident(), "Rowan", CivicFixture.NOW);

        final ResidentProfile profile = directory.profileOf(wanderer, 0);

        assertThat(profile.hasTown()).isFalse();
        assertThat(profile.standing()).isEqualTo(SystemRole.VISITOR);
        assertThat(profile.roles()).isEmpty();
    }

    @Test
    @DisplayName("the mayor's profile says so")
    void mayorsAreMarked() {
        final ResidentId mayor = CivicFixture.resident();
        final Town ashford = foundedTown("Ashford", CivicFixture.NOW, mayor);
        remember(ashford);

        final Resident stored = Resident.restore(mayor, "Bede", ashford.id(),
                CivicFixture.NOW, CivicFixture.NOW);

        assertThat(directory.profileOf(stored, 0).isMayor()).isTrue();
    }

    @Test
    @DisplayName("completion names every town without touching the claim index")
    void townNamesAreCheap() {
        remember(foundedTown("Ashford", CivicFixture.NOW, CivicFixture.resident()));
        remember(foundedTown("Highholm", CivicFixture.NOW, CivicFixture.resident()));

        assertThat(directory.townNames()).containsExactlyInAnyOrder("Ashford", "Highholm");
    }

    @Test
    @DisplayName("the resident directory names everybody with the town they belong to")
    void residentsAreListedWithTheirTown() {
        final ResidentId bede = CivicFixture.resident();
        final ResidentId ada = CivicFixture.resident();
        final ResidentId cato = CivicFixture.resident();
        final var names = net.riftbreaker.rifttowny.domain.civic.ResidentNames.empty();
        names.remember(bede, "Bede");
        names.remember(ada, "Ada");
        names.remember(cato, "Cato");

        remember(foundedTown("Ashford", CivicFixture.NOW, bede, ada));
        remember(foundedTown("Highholm", CivicFixture.NOW, cato));

        final Page<ResidentSummary> page = directory.residents(names, 1, 10);

        assertThat(page.total()).isEqualTo(3);
        // Name order, not insertion order and not town order.
        assertThat(page.items()).extracting(ResidentSummary::name)
                .containsExactly("Ada", "Bede", "Cato");
        assertThat(page.items()).extracting(ResidentSummary::townName)
                .containsExactly("Ashford", "Ashford", "Highholm");
    }

    @Test
    @DisplayName("it pages, and the order holds across the boundary")
    void residentsPage() {
        // The bug this is here for: sorting after paging, which puts a name on page two that
        // belongs on page one and shows another twice.
        final var names = net.riftbreaker.rifttowny.domain.civic.ResidentNames.empty();
        final List<ResidentId> people = new java.util.ArrayList<>();
        for (int index = 0; index < 7; index++) {
            final ResidentId who = CivicFixture.resident();
            people.add(who);
            // Deliberately reverse-named: the insertion order is the opposite of the sort order.
            names.remember(who, "Player" + (char) ('G' - index));
        }
        remember(foundedTown("Ashford", CivicFixture.NOW, people.toArray(new ResidentId[0])));

        final Page<ResidentSummary> first = directory.residents(names, 1, 3);
        final Page<ResidentSummary> second = directory.residents(names, 2, 3);
        final Page<ResidentSummary> third = directory.residents(names, 3, 3);

        assertThat(first.items()).extracting(ResidentSummary::name)
                .containsExactly("PlayerA", "PlayerB", "PlayerC");
        assertThat(second.items()).extracting(ResidentSummary::name)
                .containsExactly("PlayerD", "PlayerE", "PlayerF");
        assertThat(third.items()).extracting(ResidentSummary::name).containsExactly("PlayerG");
        assertThat(third.hasNext()).isFalse();
    }

    @Test
    @DisplayName("somebody with no town is not in it, because the cache behind it never held them")
    void townlessAreAbsent() {
        // Stated as a test rather than left to be discovered: the individual lookup reads storage
        // and answers for a townless player, but this directory is bounded by town membership and
        // cannot. A listing that quietly omitted them would read as a bug.
        final ResidentId homeless = CivicFixture.resident();
        final var names = net.riftbreaker.rifttowny.domain.civic.ResidentNames.empty();
        names.remember(homeless, "Wanderer");

        assertThat(directory.residents(names, 1, 10).isEmpty()).isTrue();
    }

    @Test
    @DisplayName("a resident whose town has gone from the cache is not listed against nothing")
    void danglingMembershipIsSkipped() {
        final ResidentId bede = CivicFixture.resident();
        final var names = net.riftbreaker.rifttowny.domain.civic.ResidentNames.empty();
        names.remember(bede, "Bede");
        final Town ashford = foundedTown("Ashford", CivicFixture.NOW, bede);
        remember(ashford);
        towns.forget(ashford.id());

        assertThat(directory.residents(names, 1, 10).isEmpty()).isTrue();
    }
}
