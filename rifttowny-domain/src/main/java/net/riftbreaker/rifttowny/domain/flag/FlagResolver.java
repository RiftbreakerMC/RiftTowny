package net.riftbreaker.rifttowny.domain.flag;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The one place a protection question is answered.
 *
 * <p>Resolution order, first opinion wins:</p>
 *
 * <pre>
 * admin restriction -&gt; war or event override -&gt; area override -&gt; claim setting
 *                   -&gt; organisation setting -&gt; world default -&gt; built-in default
 * </pre>
 *
 * <p>Two properties matter more than anything else here, because this runs inside a block-break
 * listener on the server thread:</p>
 *
 * <ul>
 *   <li><strong>It is pure.</strong> No storage, no Bukkit, no clock. Every layer is handed in
 *       already resolved, which is what lets the caller cache them per chunk and what makes this
 *       exhaustively testable.</li>
 *   <li><strong>It is predictable.</strong> A handful of map lookups, no inheritance between
 *       relationships, no second-guessing the configuration. A resolver that tried to be clever
 *       would be one nobody could reason about when a player asks why they were denied.</li>
 * </ul>
 */
public final class FlagResolver {

    private FlagResolver() {
    }

    /**
     * Answers one question.
     *
     * @param layers in resolution order. Missing layers may simply be omitted rather than passed as
     *        empty ones; the built-in default is always consulted last and needs no entry
     */
    public static FlagDecision resolve(
            final ProtectionFlag flag, final Relationship actual, final List<FlagLayer> layers) {
        // Derived rather than demanded, for the two states a relationship can still describe: a
        // relationship other than wilderness means somebody owns this. A ruin cannot be inferred
        // that way and has to be passed.
        return resolve(
                flag,
                actual,
                actual == Relationship.WILDERNESS ? LandState.WILDERNESS : LandState.CLAIMED,
                layers);
    }

    /**
     * Answers one question about land of a known kind.
     *
     * @param land whose land this is. Separate from the relationship because a world flag reports
     *        at {@link Relationship#WILDERNESS} even inside a town, and because a ruin is neither
     *        claimed nor unclaimed
     */
    public static FlagDecision resolve(
            final ProtectionFlag flag,
            final Relationship actual,
            final LandState land,
            final List<FlagLayer> layers
    ) {
        Objects.requireNonNull(flag, "flag");
        Objects.requireNonNull(actual, "actual");
        Objects.requireNonNull(land, "land");
        Objects.requireNonNull(layers, "layers");

        // A world flag collapses to wilderness: piston and fluid rules are about the land, not
        // about whoever happens to be standing nearby, and letting a member's relationship widen
        // them is exactly how a border grief works.
        final Relationship relationship = flag.effectiveRelationship(actual);

        for (final FlagLayer layer : layers) {
            final Optional<Boolean> opinion = layer.settings().opinionOn(flag, relationship);
            if (opinion.isPresent()) {
                return new FlagDecision(flag, relationship, opinion.get(), layer.source());
            }
        }

        // Nothing had an opinion at the actor's own rung. One flag has somewhere else to look:
        // PVP for an outlaw asks what the town said about visitors, because being allowed to fight
        // is exposure rather than a privilege, and a rung nobody configured must not leave somebody
        // able to be hit and unable to hit back. Reported at the rung that actually answered.
        final Relationship fallback = flag.fallbackRelationship(relationship).orElse(null);
        if (fallback != null) {
            for (final FlagLayer layer : layers) {
                final Optional<Boolean> opinion = layer.settings().opinionOn(flag, fallback);
                if (opinion.isPresent()) {
                    return new FlagDecision(flag, fallback, opinion.get(), layer.source());
                }
            }
            return new FlagDecision(flag, fallback,
                    flag.allowedByDefault(fallback, land), FlagSource.BUILT_IN);
        }
        return new FlagDecision(
                flag, relationship, flag.allowedByDefault(relationship, land), FlagSource.BUILT_IN);
    }

    /**
     * Builds the layer list in the order {@link FlagSource} declares.
     *
     * <p>Offered so callers cannot get the order wrong by assembling the list by hand. Any layer
     * may be null, meaning "this one does not apply here" — most positions have no area and no
     * war.</p>
     */
    public static List<FlagLayer> layers(
            final FlagSettings admin,
            final FlagSettings warOrEvent,
            final FlagSettings area,
            final FlagSettings claim,
            final FlagSettings organisation,
            final FlagSettings world
    ) {
        final List<FlagLayer> layers = new ArrayList<>(6);
        add(layers, FlagSource.ADMIN, admin);
        add(layers, FlagSource.WAR_OR_EVENT, warOrEvent);
        add(layers, FlagSource.AREA, area);
        add(layers, FlagSource.CLAIM, claim);
        add(layers, FlagSource.ORGANISATION, organisation);
        add(layers, FlagSource.WORLD, world);
        return List.copyOf(layers);
    }

    private static void add(
            final List<FlagLayer> layers, final FlagSource source, final FlagSettings settings) {
        if (settings != null && !settings.isEmpty()) {
            layers.add(new FlagLayer(source, settings));
        }
    }

    /** One layer: where it came from, and what it thinks. */
    public record FlagLayer(FlagSource source, FlagSettings settings) {

        public FlagLayer {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(settings, "settings");
        }
    }
}
