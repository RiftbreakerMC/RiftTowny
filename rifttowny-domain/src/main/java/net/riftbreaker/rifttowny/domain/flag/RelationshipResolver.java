package net.riftbreaker.rifttowny.domain.flag;

import net.riftbreaker.rifttowny.domain.org.NationId;
import net.riftbreaker.rifttowny.domain.org.TownId;

import java.util.Objects;

/**
 * Works out how someone stands to the land they are acting on.
 *
 * <p>Pure, and everything it needs is handed in. The caller assembles the view from the territory
 * index and its caches; this decides. Splitting it that way is what lets the ladder be exhausted in
 * tests without a database, and what keeps the decision identical whether it is reached from a
 * listener, a command or the public API.</p>
 */
public final class RelationshipResolver {

    private RelationshipResolver() {
    }

    /**
     * The highest relationship that applies.
     *
     * <p>Checked from the top down, because relationships are a ladder and someone can satisfy
     * several rungs at once: a plot owner is also a town member, and answering "member" for them
     * would lose the distinction the ladder exists to make.</p>
     */
    public static Relationship resolve(final TerritoryView view) {
        Objects.requireNonNull(view, "view");

        if (!view.claimed()) {
            return Relationship.WILDERNESS;
        }
        if (view.ownsPlot()) {
            return Relationship.RESIDENT;
        }
        if (sameTown(view)) {
            return Relationship.TOWN;
        }
        // After membership and before every outsider rung, which is the whole of the design.
        //
        // Placing it above RESIDENT and TOWN would let a town strip its own member by outlawing
        // them, and would make an outlawed player who is later admitted a resident with no rights -
        // a state nothing in the join path would notice. Placing it at the bottom, beside VISITOR,
        // would let an outlaw who happens to be in an allied nation keep the ALLY rung and walk in
        // regardless, which is the one case outlawry most obviously has to answer.
        //
        // Between the two, membership supersedes an outlawry because it is the same town's later
        // and more specific decision, and everything else gives way to it.
        if (view.outlawed()) {
            return Relationship.OUTLAW;
        }
        if (sameNation(view)) {
            return Relationship.NATION;
        }
        if (view.allied()) {
            return Relationship.ALLY;
        }
        // Trust is checked last of the positive rungs on purpose: a member who is also on the trust
        // list must not be demoted to it, and the ladder already returned above for them.
        if (view.trustedByOwner()) {
            return Relationship.TRUSTED;
        }
        return Relationship.VISITOR;
    }

    private static boolean sameTown(final TerritoryView view) {
        return view.owningTown() != null && view.owningTown().equals(view.actorTown());
    }

    private static boolean sameNation(final TerritoryView view) {
        return view.owningNation() != null && view.owningNation().equals(view.actorNation());
    }

    /**
     * Everything needed to place someone on the ladder.
     *
     * @param claimed whether anybody owns this chunk
     * @param owningTown the town that does, or null in wilderness
     * @param owningNation that town's nation, or null if it has none
     * @param actorTown the actor's town, or null if they have none
     * @param actorNation the actor's nation, or null
     * @param trustedByOwner whether the owning town trusts them
     * @param ownsPlot whether they hold this particular plot
     * @param outlawed whether the owning town has declared this player unwelcome. Consulted only
     *        for somebody who is not one of its members — see the ordering in {@link #resolve}
     * @param allied whether the two towns are allied. Always false until diplomacy exists —
     *        {@code RT-MOD-DIPLOMACY} supplies it, and until then an ally resolves as a visitor,
     *        which is the safe direction to be wrong in
     */
    public record TerritoryView(
            boolean claimed,
            TownId owningTown,
            NationId owningNation,
            TownId actorTown,
            NationId actorNation,
            boolean trustedByOwner,
            boolean ownsPlot,
            boolean outlawed,
            boolean allied
    ) {
        // No convenience factories. There were three, used by nothing but tests, and every one of
        // them hard-coded `outlawed = false` — so when the OUTLAW rung was added they quietly began
        // resolving an outlawed player as an ordinary visitor, with no compiler signal. A record
        // with nine components and two adjacent booleans is exactly where a helpful shorthand goes
        // stale without anybody noticing, and the canonical constructor cannot: adding a component
        // breaks every call site until somebody decides what it should be there.
    }
}
