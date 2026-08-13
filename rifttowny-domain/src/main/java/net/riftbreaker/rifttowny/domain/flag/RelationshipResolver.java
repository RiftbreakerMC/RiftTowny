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
            boolean allied
    ) {

        /** Unclaimed land. */
        public static TerritoryView wilderness() {
            return new TerritoryView(false, null, null, null, null, false, false, false);
        }

        /** A claim, with the actor's own affiliations. */
        public static TerritoryView claim(
                final TownId owningTown,
                final NationId owningNation,
                final TownId actorTown,
                final NationId actorNation,
                final boolean trustedByOwner
        ) {
            return claim(owningTown, owningNation, actorTown, actorNation, trustedByOwner, false);
        }

        /** The same, for a chunk the actor may hold as a plot. */
        public static TerritoryView claim(
                final TownId owningTown,
                final NationId owningNation,
                final TownId actorTown,
                final NationId actorNation,
                final boolean trustedByOwner,
                final boolean holdsPlot
        ) {
            return new TerritoryView(
                    true, owningTown, owningNation, actorTown, actorNation,
                    trustedByOwner, holdsPlot, false);
        }
    }
}
