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
}
