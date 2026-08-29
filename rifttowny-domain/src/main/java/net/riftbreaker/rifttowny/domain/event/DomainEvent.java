package net.riftbreaker.rifttowny.domain.event;

import net.riftbreaker.rifttowny.domain.naming.OrganisationName;
import net.riftbreaker.rifttowny.domain.org.NationId;
import net.riftbreaker.rifttowny.domain.org.ResidentId;
import net.riftbreaker.rifttowny.domain.org.TownId;

import java.util.Objects;

/**
 * Something that happened to an organisation.
 *
 * <p>Produced by the aggregate that made the change, so an event cannot be emitted for a change
 * that did not actually take place. The service layer persists the new state and these events in
 * the same transaction, which is what makes the outbox exactly-once rather than best-effort.</p>
 *
 * <p>Sealed: adding a case forces every dispatcher to acknowledge it, rather than silently dropping
 * a new event type on the floor.</p>
 */
public sealed interface DomainEvent {

    /** A short stable name, used as the outbox {@code event_type} and the audit action. */
    String type();

    record ResidentAdmitted(TownId town, ResidentId resident) implements DomainEvent {
        public ResidentAdmitted {
            Objects.requireNonNull(town, "town");
            Objects.requireNonNull(resident, "resident");
        }

        @Override
        public String type() {
            return "town.resident.admitted";
        }
    }

    record ResidentReleased(TownId town, ResidentId resident, boolean voluntary) implements DomainEvent {
        public ResidentReleased {
            Objects.requireNonNull(town, "town");
            Objects.requireNonNull(resident, "resident");
        }

        @Override
        public String type() {
            return "town.resident.released";
        }
    }

    record LeadershipTransferred(TownId town, ResidentId from, ResidentId to) implements DomainEvent {
        public LeadershipTransferred {
            Objects.requireNonNull(town, "town");
            Objects.requireNonNull(to, "to");
        }

        @Override
        public String type() {
            return "town.leadership.transferred";
        }
    }

    record TownRenamed(TownId town, OrganisationName from, OrganisationName to) implements DomainEvent {
        public TownRenamed {
            Objects.requireNonNull(town, "town");
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
        }

        @Override
        public String type() {
            return "town.renamed";
        }
    }

    /**
     * A town changed what it says about itself, or who it lets in.
     *
     * <p>Carries only the town. Which of the six settings moved is deliberately not on the event:
     * a board and a tag are player-written text, and putting them on an event puts them in the
     * outbox, in a Discord relay and in a log line, which is three more places a moderator has to
     * go to erase something somebody wrote. A subscriber that needs the new value reads the
     * town.</p>
     */
    record TownProfileChanged(TownId town) implements DomainEvent {
        public TownProfileChanged {
            Objects.requireNonNull(town, "town");
        }

        @Override
        public String type() {
            return "town.profile-changed";
        }
    }

    /**
     * A tax run finished, with what it did.
     *
     * <p>The counts rather than the detail. A run touches every town on the server, and an event
     * carrying each charge would be thousands of rows in the outbox for one scheduled sweep;
     * a consumer that wants the detail has the ledger, which is where each movement already is.</p>
     *
     * <p>Published in the same transaction that marks the run finished, so there is no state in
     * which a run is complete in the table and unannounced in the outbox.</p>
     */
    record TaxRunCompleted(
            String periodKey, int townsCharged, int residentsCharged, int townsFallen)
            implements DomainEvent {

        public TaxRunCompleted {
            Objects.requireNonNull(periodKey, "periodKey");
        }

        @Override
        public String type() {
            return "tax.run-completed";
        }
    }

    /**
     * A town fell because it could not pay.
     *
     * <p>Separate from {@link TownDisbanded}, which the collapse also emits. That one says the town
     * is gone; this one says why, and the difference matters to everybody downstream: a town that
     * chose to disband and a town taken by a timer read identically in a Discord relay otherwise,
     * and the second is the one a moderator gets asked about.</p>
     *
     * <p>Published after the collapse has committed. Announcing a fall that then failed to happen
     * would be worse than announcing it late.</p>
     */
    record TownFellBankrupt(TownId town, String reason) implements DomainEvent {

        public TownFellBankrupt {
            Objects.requireNonNull(town, "town");
            Objects.requireNonNull(reason, "reason");
        }

        @Override
        public String type() {
            return "tax.town-fell-bankrupt";
        }
    }

    /**
     * One nation declared something about another.
     *
     * <p>Carries the declarer, the target and the kind — never "these two are now allied", because
     * a single declaration does not make an alliance. A subscriber that wants to know whether the
     * pair is actually allied asks the diplomacy book, which is the only thing that can answer.</p>
     */
    record RelationDeclared(NationId declarer, NationId target, String relation)
            implements DomainEvent {

        public RelationDeclared {
            Objects.requireNonNull(declarer, "declarer");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(relation, "relation");
        }

        @Override
        public String type() {
            return "nation.relation-declared";
        }
    }

    /** One nation took a declaration back. */
    record RelationWithdrawn(NationId declarer, NationId target, String relation)
            implements DomainEvent {

        public RelationWithdrawn {
            Objects.requireNonNull(declarer, "declarer");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(relation, "relation");
        }

        @Override
        public String type() {
            return "nation.relation-withdrawn";
        }
    }

    /** The same, one level up. */
    record NationProfileChanged(NationId nation) implements DomainEvent {
        public NationProfileChanged {
            Objects.requireNonNull(nation, "nation");
        }

        @Override
        public String type() {
            return "nation.profile-changed";
        }
    }

    record TownFounded(TownId town, OrganisationName name, ResidentId founder) implements DomainEvent {
        public TownFounded {
            Objects.requireNonNull(town, "town");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(founder, "founder");
        }

        @Override
        public String type() {
            return "town.founded";
        }
    }


    /**
     * Two towns became one.
     *
     * <p>Carries what was lost as well as what moved, because nothing else records it: the absorbed
     * town's row is gone by the time anything reads this, and its name and its custom roles exist
     * nowhere else afterwards.</p>
     */
    record TownsMerged(
            TownId survivor,
            TownId absorbed,
            OrganisationName absorbedName,
            int residentsMoved,
            int chunksMoved,
            java.util.List<String> rolesLost
    ) implements DomainEvent {

        public TownsMerged {
            Objects.requireNonNull(survivor, "survivor");
            Objects.requireNonNull(absorbed, "absorbed");
            Objects.requireNonNull(absorbedName, "absorbedName");
            rolesLost = java.util.List.copyOf(rolesLost);
        }

        @Override
        public String type() {
            return "town.merged";
        }
    }
    /**
     * @param residentsReleased how many players became townless, so an announcement can say how many
     *        people this affected without a second query against a town that no longer exists
     */
    record TownDisbanded(TownId town, OrganisationName name, int residentsReleased)
            implements DomainEvent {
        public TownDisbanded {
            Objects.requireNonNull(town, "town");
            Objects.requireNonNull(name, "name");
        }

        @Override
        public String type() {
            return "town.disbanded";
        }
    }

    record TownJoinedNation(TownId town, NationId nation) implements DomainEvent {
        public TownJoinedNation {
            Objects.requireNonNull(town, "town");
            Objects.requireNonNull(nation, "nation");
        }

        @Override
        public String type() {
            return "nation.town.joined";
        }
    }

    record TownLeftNation(TownId town, NationId nation, boolean dissolvesNation) implements DomainEvent {
        public TownLeftNation {
            Objects.requireNonNull(town, "town");
            Objects.requireNonNull(nation, "nation");
        }

        @Override
        public String type() {
            return "nation.town.left";
        }
    }

    record NationCreated(
            NationId nation, OrganisationName name, ResidentId founder, TownId capital)
            implements DomainEvent {

        public NationCreated {
            Objects.requireNonNull(nation, "nation");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(founder, "founder");
            Objects.requireNonNull(capital, "capital");
        }

        @Override
        public String type() {
            return "nation.created";
        }
    }

    /**
     * A nation ended.
     *
     * @param townsReleased how many member towns were freed, so an announcement can say whether a
     *        federation collapsed or a one-town nation quietly folded
     */
    record NationDisbanded(NationId nation, OrganisationName name, int townsReleased)
            implements DomainEvent {

        public NationDisbanded {
            Objects.requireNonNull(nation, "nation");
            Objects.requireNonNull(name, "name");
        }

        @Override
        public String type() {
            return "nation.disbanded";
        }
    }

    record CapitalMoved(NationId nation, TownId from, TownId to) implements DomainEvent {
        public CapitalMoved {
            Objects.requireNonNull(nation, "nation");
            Objects.requireNonNull(to, "to");
        }

        @Override
        public String type() {
            return "nation.capital.moved";
        }
    }

    record NationLeadershipTransferred(NationId nation, ResidentId from, ResidentId to)
            implements DomainEvent {
        public NationLeadershipTransferred {
            Objects.requireNonNull(nation, "nation");
            Objects.requireNonNull(to, "to");
        }

        @Override
        public String type() {
            return "nation.leadership.transferred";
        }
    }

    record NationRenamed(NationId nation, OrganisationName from, OrganisationName to)
            implements DomainEvent {
        public NationRenamed {
            Objects.requireNonNull(nation, "nation");
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
        }

        @Override
        public String type() {
            return "nation.renamed";
        }
    }

    /**
     * A role change.
     *
     * <p>One record covering create, delete, rename, permission change and assignment, rather than
     * six near-identical ones. Role edits are frequent and structurally uniform, and a consumer
     * routing them to Discord or an audit log wants one shape with a discriminator, not a switch
     * over six types that all carry the same three fields.</p>
     *
     * @param organisationScope town or nation
     * @param organisationId the town or nation UUID
     * @param roleId the role that changed
     * @param roleName its name at the time, so an audit entry survives the role being deleted
     * @param action what happened
     * @param detail the permission, the old name, or the resident — whatever the action needs
     */
    record RoleChanged(
            net.riftbreaker.rifttowny.domain.org.OrganisationScope organisationScope,
            java.util.UUID organisationId,
            java.util.UUID roleId,
            String roleName,
            RoleAction action,
            String detail
    ) implements DomainEvent {
        public RoleChanged {
            Objects.requireNonNull(organisationScope, "organisationScope");
            Objects.requireNonNull(organisationId, "organisationId");
            Objects.requireNonNull(roleId, "roleId");
            Objects.requireNonNull(roleName, "roleName");
            Objects.requireNonNull(action, "action");
            detail = detail == null ? "" : detail;
        }

        @Override
        public String type() {
            return "role." + action.name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** What happened to a role. */
    enum RoleAction {
        CREATED, DELETED, RENAMED, REPRIORITISED, PERMISSION_GRANTED, PERMISSION_REVOKED,
        ASSIGNED, UNASSIGNED
    }


    /**
     * A town declared somebody unwelcome.
     *
     * <p>Carries who did it, unlike most events here, because this one is a sanction and the
     * question that follows it is always "who". The same reason the column exists.</p>
     */
    record OutlawDeclared(TownId town, ResidentId outlaw, ResidentId by) implements DomainEvent {

        public OutlawDeclared {
            Objects.requireNonNull(town, "town");
            Objects.requireNonNull(outlaw, "outlaw");
        }

        @Override
        public String type() {
            return "town.outlaw-declared";
        }
    }

    /** A town lifted one. */
    record OutlawPardoned(TownId town, ResidentId outlaw, ResidentId by) implements DomainEvent {

        public OutlawPardoned {
            Objects.requireNonNull(town, "town");
            Objects.requireNonNull(outlaw, "outlaw");
        }

        @Override
        public String type() {
            return "town.outlaw-pardoned";
        }
    }
    /**
     * @param chunk rendered rather than structured, because an outbox payload is read by Discord
     *        templates and map links, and both want it as text
     */
    record ChunkClaimed(TownId town, String chunk, String kind) implements DomainEvent {
        public ChunkClaimed {
            Objects.requireNonNull(town, "town");
            Objects.requireNonNull(chunk, "chunk");
            Objects.requireNonNull(kind, "kind");
        }

        @Override
        public String type() {
            return "town.chunk.claimed";
        }
    }

    record ChunkUnclaimed(TownId town, String chunk) implements DomainEvent {
        public ChunkUnclaimed {
            Objects.requireNonNull(town, "town");
            Objects.requireNonNull(chunk, "chunk");
        }

        @Override
        public String type() {
            return "town.chunk.unclaimed";
        }
    }

    record HomeblockMoved(TownId town, String chunk) implements DomainEvent {
        public HomeblockMoved {
            Objects.requireNonNull(town, "town");
            Objects.requireNonNull(chunk, "chunk");
        }

        @Override
        public String type() {
            return "town.homeblock.moved";
        }
    }

    record OutsiderTrusted(TownId town, ResidentId outsider) implements DomainEvent {
        public OutsiderTrusted {
            Objects.requireNonNull(town, "town");
            Objects.requireNonNull(outsider, "outsider");
        }

        @Override
        public String type() {
            return "town.trust.granted";
        }
    }

    record OutsiderUntrusted(TownId town, ResidentId outsider) implements DomainEvent {
        public OutsiderUntrusted {
            Objects.requireNonNull(town, "town");
            Objects.requireNonNull(outsider, "outsider");
        }

        @Override
        public String type() {
            return "town.trust.revoked";
        }
    }

    /** A resident took a plot inside their town. */
    record PlotHeld(TownId town, String chunk, ResidentId holder) implements DomainEvent {

        public PlotHeld {
            Objects.requireNonNull(town, "town");
            Objects.requireNonNull(chunk, "chunk");
            Objects.requireNonNull(holder, "holder");
        }

        @Override
        public String type() {
            return "plot.held";
        }
    }

    /**
     * A plot went back to the town.
     *
     * @param by whoever did it, which is not always the holder — a town may reclaim the plot of a
     *        member who has stopped playing, and an audit wants to tell those apart
     */
    record PlotReleased(TownId town, String chunk, ResidentId formerHolder, ResidentId by)
            implements DomainEvent {

        public PlotReleased {
            Objects.requireNonNull(town, "town");
            Objects.requireNonNull(chunk, "chunk");
        }

        @Override
        public String type() {
            return "plot.released";
        }
    }

    /** A plot was put to a different use. */
    record PlotTypeChanged(TownId town, String chunk, String plotType) implements DomainEvent {

        public PlotTypeChanged {
            Objects.requireNonNull(town, "town");
            Objects.requireNonNull(chunk, "chunk");
            Objects.requireNonNull(plotType, "plotType");
        }

        @Override
        public String type() {
            return "plot.type-changed";
        }
    }

    /**
     * A town fell and left a ruin.
     *
     * <p>Distinct from {@link TownDisbanded}, which says the organisation ended. This says its land
     * did not: the buildings are still standing and can be taken on until the window closes.</p>
     */
    record TownRuined(
            java.util.UUID ruin, TownId formerTown, OrganisationName name, int chunks)
            implements DomainEvent {

        public TownRuined {
            Objects.requireNonNull(ruin, "ruin");
            Objects.requireNonNull(formerTown, "formerTown");
            Objects.requireNonNull(name, "name");
        }

        @Override
        public String type() {
            return "ruin.created";
        }
    }

    /** Somebody took a ruin on, founding a new town on its territory. */
    record RuinReclaimed(
            java.util.UUID ruin,
            TownId formerTown,
            OrganisationName formerName,
            TownId newTown,
            ResidentId by
    ) implements DomainEvent {

        public RuinReclaimed {
            Objects.requireNonNull(ruin, "ruin");
            Objects.requireNonNull(formerTown, "formerTown");
            Objects.requireNonNull(formerName, "formerName");
            Objects.requireNonNull(newTown, "newTown");
            Objects.requireNonNull(by, "by");
        }

        @Override
        public String type() {
            return "ruin.reclaimed";
        }
    }

    /** A ruin's window closed and its land reverted to wilderness. */
    record RuinLapsed(
            java.util.UUID ruin, TownId formerTown, OrganisationName name, int chunksReleased)
            implements DomainEvent {

        public RuinLapsed {
            Objects.requireNonNull(ruin, "ruin");
            Objects.requireNonNull(formerTown, "formerTown");
            Objects.requireNonNull(name, "name");
        }

        @Override
        public String type() {
            return "ruin.lapsed";
        }
    }

    /**
     * A protection flag was set.
     *
     * <p>Carries the scope and target as strings rather than a typed identifier. The target is a
     * chunk, a town, a world or the whole server depending on the scope, and an event that had to
     * name a type for each would need four records to say one thing.</p>
     *
     * <p>Worth recording at all because "why can visitors suddenly build here" is a question neither
     * the resolver nor the state of the table can answer afterwards — only the change can.</p>
     */
    record FlagChanged(
            String scope, String target, String flag, String relationship, boolean allowed)
            implements DomainEvent {

        public FlagChanged {
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(flag, "flag");
            Objects.requireNonNull(relationship, "relationship");
        }

        @Override
        public String type() {
            return "flag.set";
        }
    }

    /** A protection flag override was removed, letting the layer below answer again. */
    record FlagCleared(String scope, String target, String flag, String relationship)
            implements DomainEvent {

        public FlagCleared {
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(flag, "flag");
            Objects.requireNonNull(relationship, "relationship");
        }

        @Override
        public String type() {
            return "flag.cleared";
        }
    }
}
