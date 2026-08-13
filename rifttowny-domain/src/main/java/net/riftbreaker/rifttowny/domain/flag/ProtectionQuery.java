package net.riftbreaker.rifttowny.domain.flag;

import net.riftbreaker.rifttowny.api.ChunkKey;
import net.riftbreaker.rifttowny.domain.civic.CivicCache;
import net.riftbreaker.rifttowny.domain.civic.TownFacts;
import net.riftbreaker.rifttowny.domain.org.NationId;
import net.riftbreaker.rifttowny.domain.org.ResidentId;
import net.riftbreaker.rifttowny.domain.org.TownId;
import net.riftbreaker.rifttowny.domain.role.Permission;
import net.riftbreaker.rifttowny.domain.territory.Claim;
import net.riftbreaker.rifttowny.domain.territory.Ruin;
import net.riftbreaker.rifttowny.domain.territory.RuinIndex;
import net.riftbreaker.rifttowny.domain.territory.TerritoryIndex;

import java.util.Objects;
import java.util.Optional;

/**
 * The one question a protection listener asks: may this player do this here?
 *
 * <p>Composes the three things that have to agree — where the land is, who the actor is to its
 * owner, and what the flags and roles say — into a single synchronous answer. Everything it touches
 * is in memory, so an answer is a handful of map lookups and never a database read. That is the
 * entire reason {@link TerritoryIndex} and {@link CivicCache} exist.</p>
 *
 * <p><strong>Two gates, and both must pass.</strong> Flags decide what a <em>relationship</em> may
 * do; roles decide what a <em>person</em> may do. An outsider only meets the first, because they
 * hold no role in a town they do not belong to. A member meets both, which is what lets a town admit
 * someone without handing them the right to empty its chests.</p>
 *
 * <p>A denial always names its cause. A player told only "you cannot do that" files a bug report; a
 * player told which relationship and which layer decided goes and asks for a role.</p>
 */
public final class ProtectionQuery {

    private final TerritoryIndex territory;
    private final CivicCache civic;
    private final FlagSettingsSource settings;
    private final RuinIndex ruins;

    /** Uses built-in flag defaults and knows of no ruins. */
    public ProtectionQuery(final TerritoryIndex territory, final CivicCache civic) {
        this(territory, civic, FlagSettingsSource.builtInOnly(), RuinIndex.empty());
    }

    public ProtectionQuery(
            final TerritoryIndex territory,
            final CivicCache civic,
            final FlagSettingsSource settings
    ) {
        this(territory, civic, settings, RuinIndex.empty());
    }

    /**
     * @param ruins consulted when no town owns the chunk. A fallen town's land is not wilderness
     *        yet, and answering as though it were would let the first passer-by dismantle it
     */
    public ProtectionQuery(
            final TerritoryIndex territory,
            final CivicCache civic,
            final FlagSettingsSource settings,
            final RuinIndex ruins
    ) {
        this.territory = Objects.requireNonNull(territory, "territory");
        this.civic = Objects.requireNonNull(civic, "civic");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.ruins = Objects.requireNonNull(ruins, "ruins");
    }

    // --- asking --------------------------------------------------------------------------------

    /** Whether a player may perform an action, using the role permission the flag maps to. */
    public ProtectionAnswer mayAct(
            final ResidentId who, final ChunkKey chunk, final ProtectionFlag flag) {
        return mayAct(who, chunk, flag, permissionFor(flag).orElse(null));
    }

    /**
     * Whether a player may perform an action.
     *
     * @param who the actor, or null for something with no player behind it — a piston, a fire, a
     *        creeper. World flags resolve at wilderness regardless, so a null actor is not a fault
     * @param actionPermission the role permission a member needs, or null when no role governs the
     *        action. Passed explicitly by callers that know better than the default mapping: an
     *        armour stand and a lever are both {@link ProtectionFlag#INTERACT}, but only one of them
     *        is {@link Permission#SWITCH}
     */
    public ProtectionAnswer mayAct(
            final ResidentId who,
            final ChunkKey chunk,
            final ProtectionFlag flag,
            final Permission actionPermission
    ) {
        Objects.requireNonNull(flag, "flag");

        final Optional<Claim> claim = territory.at(chunk);
        if (claim.isEmpty()) {
            final Optional<Ruin> ruin = ruins.at(chunk);
            if (ruin.isPresent()) {
                // A fallen town's land. Resolved at VISITOR whoever is standing there: there is no
                // town left to have a relationship with, and a ruin that answered differently for
                // the mayor who disbanded it would be a way to keep the land quietly.
                return ProtectionAnswer.ruin(
                        FlagResolver.resolve(flag, Relationship.VISITOR, LandState.RUIN,
                                settings.layersFor(chunk, null)),
                        ruin.get());
            }
            // Wilderness. Nobody owns it, so there is no role book to consult and the flag layer
            // alone decides.
            return ProtectionAnswer.from(
                    FlagResolver.resolve(flag, Relationship.WILDERNESS, LandState.WILDERNESS,
                            settings.layersFor(chunk, null)),
                    null);
        }

        final TownId owner = claim.get().town();
        final Optional<TownFacts> facts = civic.town(owner);
        if (facts.isEmpty()) {
            // Claimed land owned by a town this cache cannot describe. That is a fault, not an
            // absence, and allowing it would turn a cache miss into a griefing window.
            return ProtectionAnswer.cacheFault(flag, owner);
        }

        final TownFacts owning = facts.get();
        final Relationship relationship = RelationshipResolver.resolve(viewOf(who, owning));
        final FlagDecision decision =
                FlagResolver.resolve(flag, relationship, settings.layersFor(chunk, owner));
        if (decision.denied()) {
            return ProtectionAnswer.from(decision, owning);
        }
        if (relationship.isMember() && actionPermission != null
                && !owning.allows(who, actionPermission)) {
            // The territory allows it and the member's own roles do not. Reported separately so the
            // player is told to ask for a role rather than told the land is protected.
            return ProtectionAnswer.roleRefusal(decision, owning, actionPermission);
        }
        return ProtectionAnswer.from(decision, owning);
    }

    /** The ruin standing on a chunk, if one is. */
    public Optional<Ruin> ruinAt(final ChunkKey chunk) {
        return territory.at(chunk).isPresent() ? Optional.empty() : ruins.at(chunk);
    }

    /** The relationship alone, for a border message or a map overlay. */
    public Relationship relationshipAt(final ResidentId who, final ChunkKey chunk) {
        final Optional<Claim> claim = territory.at(chunk);
        if (claim.isEmpty()) {
            // A ruin has no members, so everyone standing in one is a visitor to it.
            return ruins.isRuin(chunk) ? Relationship.VISITOR : Relationship.WILDERNESS;
        }
        return civic.town(claim.get().town())
                .map(owning -> RelationshipResolver.resolve(viewOf(who, owning)))
                // An unknown town is a stranger's land as far as the player is concerned, which is
                // the same direction the protection check errs in.
                .orElse(Relationship.VISITOR);
    }

    /** Who owns the chunk, if anybody. */
    public Optional<TownId> ownerAt(final ChunkKey chunk) {
        return territory.ownerOf(chunk);
    }

    /** The owning town's facts, for a listener that also wants to name it in a message. */
    public Optional<TownFacts> ownerFactsAt(final ChunkKey chunk) {
        return territory.ownerOf(chunk).flatMap(civic::town);
    }

    /**
     * The role permission a flag corresponds to, if any.
     *
     * <p>The mapping lives here rather than on {@link ProtectionFlag} because it is the only place
     * that needs both vocabularies: flags describe land, permissions describe people, and joining
     * them anywhere else would couple two enums that are otherwise independent.</p>
     *
     * <p>Empty for the world flags, which have no actor, and for {@link ProtectionFlag#PVP}, which
     * is a property of the land rather than something a town grants a role.</p>
     */
    public static Optional<Permission> permissionFor(final ProtectionFlag flag) {
        if (flag == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(switch (flag) {
            case BUILD -> Permission.BUILD;
            case BREAK -> Permission.BREAK;
            case INTERACT -> Permission.SWITCH;
            case CONTAINER -> Permission.CONTAINER;
            case ENTITY_DAMAGE -> Permission.ENTITY_DAMAGE;
            case VEHICLE -> Permission.VEHICLE;
            case FARMLAND -> Permission.FARMLAND;
            case SHOP_USE -> Permission.SHOP_USE;
            case SPAWNER_USE -> Permission.SPAWNER_USE;
            case FLIGHT -> Permission.FLIGHT;
            case PVP, EXPLOSIONS, FIRE_SPREAD, FLUID_FLOW, PISTONS, REDSTONE, MOB_SPAWNING,
                 EVENT_ACTION, WAR_ACTION -> null;
        });
    }

    // --- internals -----------------------------------------------------------------------------

    private RelationshipResolver.TerritoryView viewOf(
            final ResidentId who, final TownFacts owning) {
        final Optional<TownFacts> actorTown = civic.townFactsOf(who);
        return RelationshipResolver.TerritoryView.claim(
                owning.id(),
                owning.nation().orElse(null),
                actorTown.map(TownFacts::id).orElse(null),
                actorTown.flatMap(TownFacts::nation).orElse(null),
                owning.trusts(who));
    }

    /**
     * The answer, and enough of the reasoning to explain it.
     *
     * @param owner the town that owns the land, or null in wilderness
     * @param ownerName its display name, carried so a message does not need a second lookup
     * @param cacheFault the refusal came from missing cached state rather than from a rule
     * @param missingPermission the role permission the actor lacked, when that is what refused them
     * @param ruin the fallen town whose land this is, or null when it is not a ruin
     */
    public record ProtectionAnswer(
            boolean allowed,
            ProtectionFlag flag,
            Relationship relationship,
            FlagSource source,
            TownId owner,
            String ownerName,
            boolean cacheFault,
            Permission missingPermission,
            Ruin ruin
    ) {

        public ProtectionAnswer {
            Objects.requireNonNull(flag, "flag");
            Objects.requireNonNull(relationship, "relationship");
            Objects.requireNonNull(source, "source");
        }

        static ProtectionAnswer from(final FlagDecision decision, final TownFacts owning) {
            return new ProtectionAnswer(
                    decision.allowed(), decision.flag(), decision.relationship(), decision.source(),
                    owning == null ? null : owning.id(),
                    owning == null ? null : owning.displayName(),
                    false, null, null);
        }

        static ProtectionAnswer ruin(final FlagDecision decision, final Ruin ruin) {
            return new ProtectionAnswer(
                    decision.allowed(), decision.flag(), decision.relationship(), decision.source(),
                    ruin.formerTown(), ruin.name().display(), false, null, ruin);
        }

        static ProtectionAnswer roleRefusal(
                final FlagDecision decision, final TownFacts owning, final Permission missing) {
            return new ProtectionAnswer(
                    false, decision.flag(), decision.relationship(), decision.source(),
                    owning.id(), owning.displayName(), false, missing, null);
        }

        static ProtectionAnswer cacheFault(final ProtectionFlag flag, final TownId owner) {
            return new ProtectionAnswer(
                    false, flag, Relationship.VISITOR, FlagSource.BUILT_IN, owner, null, true,
                    null, null);
        }

        public boolean denied() {
            return !allowed;
        }

        /** Whether this land is a fallen town's rather than a standing one's. */
        public boolean isRuin() {
            return ruin != null;
        }

        public Optional<Ruin> fallenTown() {
            return Optional.ofNullable(ruin);
        }

        /** Whether the refusal came from the actor's roles rather than from the territory. */
        public boolean refusedByRole() {
            return missingPermission != null;
        }

        public Optional<TownId> owningTown() {
            return Optional.ofNullable(owner);
        }

        /** Why, in a form an operator can act on. */
        public String explain() {
            if (cacheFault) {
                return flag + " denied: town " + owner + " is not loaded";
            }
            if (isRuin()) {
                return flag + " in the ruins of " + ownerName + " is "
                        + (allowed ? "allowed" : "denied") + " by " + source;
            }
            if (refusedByRole()) {
                return flag + " denied: no " + missingPermission + " in " + ownerName;
            }
            return flag + " for " + relationship + " is " + (allowed ? "allowed" : "denied")
                    + " by " + source
                    + (ownerName == null ? " in wilderness" : " in " + ownerName);
        }
    }
}
