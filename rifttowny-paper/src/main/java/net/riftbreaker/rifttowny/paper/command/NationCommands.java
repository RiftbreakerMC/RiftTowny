package net.riftbreaker.rifttowny.paper.command;

import net.riftbreaker.rifttowny.domain.org.ChangeDenial;
import net.riftbreaker.rifttowny.domain.org.Invitation;
import net.riftbreaker.rifttowny.domain.org.Nation;
import net.riftbreaker.rifttowny.domain.org.NationId;
import net.riftbreaker.rifttowny.domain.org.NationRepository;
import net.riftbreaker.rifttowny.domain.org.Resident;
import net.riftbreaker.rifttowny.domain.org.ResidentId;
import net.riftbreaker.rifttowny.domain.org.ResidentRepository;
import net.riftbreaker.rifttowny.domain.org.Town;
import net.riftbreaker.rifttowny.domain.org.TownId;
import net.riftbreaker.rifttowny.domain.org.TownRepository;
import net.riftbreaker.rifttowny.domain.role.Role;
import net.riftbreaker.rifttowny.domain.service.NationService;
import net.riftbreaker.rifttowny.domain.service.ServiceResult;
import net.riftbreaker.rifttowny.paper.command.tree.CommandActor;
import net.riftbreaker.rifttowny.paper.command.tree.CommandNode;
import net.riftbreaker.rifttowny.paper.command.tree.Surface;
import net.riftbreaker.rifttowny.paper.message.DenialText;
import net.riftbreaker.rifttowny.paper.message.MessageKey;
import net.riftbreaker.rifttowny.paper.message.MessageService;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * The {@code /nation} tree.
 *
 * <p>Thin, like {@code /town}: read the arguments, call the service, render the answer. Every rule —
 * who may invite, who may accept, what happens to the capital — belongs to {@link NationService},
 * because the public API reaches the same methods and a check that lived here would be one anybody
 * could walk around.</p>
 *
 * <p>The join flow is two commands on purpose, one per side. A nation runs {@code /nation invite},
 * and the town's own leadership runs {@code /nation join}. Neither alone moves a town, which is what
 * stops a nation conscripting one and stops a town letting itself into somebody else's territory.</p>
 */
public final class NationCommands {

    private final NationService nations;
    private final net.riftbreaker.rifttowny.domain.service.NationRoleService roles;
    private final net.riftbreaker.rifttowny.domain.civic.ResidentNames names;
    private final ResidentRepository residents;
    private final TownRepository towns;
    private final NationRepository nationRepository;
    private final MessageService messages;
    private final DenialText denials;

    public NationCommands(
            final NationService nations,
            final net.riftbreaker.rifttowny.domain.service.NationRoleService roles,
            final net.riftbreaker.rifttowny.domain.civic.ResidentNames names,
            final ResidentRepository residents,
            final TownRepository towns,
            final NationRepository nationRepository,
            final MessageService messages,
            final DenialText denials
    ) {
        this.nations = Objects.requireNonNull(nations, "nations");
        this.roles = Objects.requireNonNull(roles, "roles");
        this.names = Objects.requireNonNull(names, "names");
        this.residents = Objects.requireNonNull(residents, "residents");
        this.towns = Objects.requireNonNull(towns, "towns");
        this.nationRepository = Objects.requireNonNull(nationRepository, "nationRepository");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.denials = Objects.requireNonNull(denials, "denials");
    }

    /** Builds the tree. Called once at enable. */
    public CommandNode tree() {
        return CommandNode.group("nation")
                .child(CommandNode.action("new")
                        .aliases("create", "found")
                        .permission("rifttowny.nation.new")
                        .usage("nation new <name>")
                        .describedAs("Found a nation around your town")
                        .runs(this::found, Surface.CHAT))
                .child(CommandNode.action("info")
                        .permission("rifttowny.nation.info")
                        .usage("nation info")
                        .describedAs("Show your nation")
                        .runs(this::info, Surface.CHAT))
                .child(CommandNode.action("invite")
                        .permission("rifttowny.nation.invite")
                        .usage("nation invite <town>")
                        .describedAs("Offer membership to a town")
                        .runs(this::invite, Surface.CHAT))
                .child(CommandNode.action("withdraw")
                        .aliases("uninvite")
                        .permission("rifttowny.nation.invite")
                        .usage("nation withdraw <town>")
                        .describedAs("Withdraw an offer")
                        .runs(this::withdraw, Surface.CHAT))
                .child(CommandNode.action("invites")
                        .permission("rifttowny.nation.info")
                        .usage("nation invites")
                        .describedAs("Show who has invited your town")
                        .runs(this::invitations, Surface.CHAT))
                .child(CommandNode.action("join")
                        .aliases("accept")
                        .permission("rifttowny.nation.join")
                        .usage("nation join <nation>")
                        .describedAs("Accept an invitation for your town")
                        .runs(this::join, Surface.CHAT))
                .child(CommandNode.action("leave")
                        .permission("rifttowny.nation.leave")
                        .usage("nation leave")
                        .describedAs("Take your town out of its nation")
                        .runs(this::leave, Surface.CHAT))
                .child(CommandNode.action("expel")
                        .aliases("kick")
                        .permission("rifttowny.nation.expel")
                        .usage("nation expel <town>")
                        .describedAs("Remove a member town")
                        .runs(this::expel, Surface.CHAT))
                .child(CommandNode.action("capital")
                        .permission("rifttowny.nation.capital")
                        .usage("nation capital <town>")
                        .describedAs("Move the capital")
                        .runs(this::capital, Surface.CHAT))
                .child(CommandNode.action("king")
                        .aliases("leader")
                        .permission("rifttowny.nation.king")
                        .usage("nation king <player>")
                        .describedAs("Hand over the crown")
                        .completer((actor, args) -> onlinePlayerNames())
                        .runs(this::king, Surface.CHAT))
                .child(CommandNode.action("rename")
                        .permission("rifttowny.nation.rename")
                        .usage("nation rename <name>")
                        .describedAs("Rename your nation")
                        .runs(this::rename, Surface.CHAT))
                .child(CommandNode.action("delete")
                        .aliases("disband")
                        .permission("rifttowny.nation.delete")
                        .usage("nation delete")
                        .describedAs("Disband your nation")
                        .runs(this::disband, Surface.CHAT))
                .child(roleTree())
                .build();
    }

    /**
     * The {@code /nation role} tree.
     *
     * <p>The same shape as {@code /town role}, because the rules behind it are the same ones. The
     * one difference a player can see is who a role may be given to: a nation's roles go to citizens
     * of its member towns, not to its own residents, because it has none.</p>
     */
    private CommandNode roleTree() {
        return CommandNode.group("role")
                .permission("rifttowny.nation.role.view")
                .describedAs("Manage nation roles")
                .usage("nation role")
                .child(CommandNode.action("list")
                        // Repeated from the parent deliberately; see TownCommands.roleTree.
                        .permission("rifttowny.nation.role.view")
                        .usage("nation role list")
                        .describedAs("List the nation's roles")
                        .runs(this::roleList, Surface.CHAT))
                .child(CommandNode.action("new")
                        .aliases("create")
                        .permission("rifttowny.nation.role.manage")
                        .usage("nation role new <name> <priority>")
                        .describedAs("Create a role")
                        .runs(this::roleCreate, Surface.CHAT))
                .child(CommandNode.action("delete")
                        .permission("rifttowny.nation.role.manage")
                        .usage("nation role delete <name>")
                        .describedAs("Delete a role")
                        .runs(this::roleDelete, Surface.CHAT))
                .child(CommandNode.action("assign")
                        .aliases("give")
                        .permission("rifttowny.nation.role.assign")
                        .usage("nation role assign <player> <role>")
                        .describedAs("Give a citizen a role")
                        .completer((actor, args) -> args.size() <= 1 ? onlinePlayerNames() : List.of())
                        .runs(this::roleAssign, Surface.CHAT))
                .child(CommandNode.action("unassign")
                        .aliases("take")
                        .permission("rifttowny.nation.role.assign")
                        .usage("nation role unassign <player> <role>")
                        .describedAs("Take a role away")
                        .completer((actor, args) -> args.size() <= 1 ? onlinePlayerNames() : List.of())
                        .runs(this::roleUnassign, Surface.CHAT))
                .build();
    }

    // --- actions -------------------------------------------------------------------------------

    private void found(final CommandActor actor, final List<String> args) {
        if (args.isEmpty()) {
            usage(actor, "nation new <name>");
            return;
        }
        withTown(actor, (who, town) ->
                reply(actor, nations.found(who, town.id(), args.getFirst()), nation ->
                        messages.send(actor::send, MessageKey.NATION_FOUNDED,
                                MessageService.value("nation", nation.name().display()))));
    }

    private void info(final CommandActor actor, final List<String> args) {
        withNation(actor, (who, nation) -> {
            messages.send(actor::send, MessageKey.NATION_INFO_HEADER,
                    MessageService.value("nation", nation.name().display()));
            line(actor, "Leader", names.describe(nation.leader()));
            line(actor, "Towns", String.valueOf(nation.townCount()));
            then(actor, towns.find(nation.capital()), capital -> line(actor, "Capital",
                    capital.map(found -> found.name().display()).orElse("unknown")));
        });
    }

    private void invite(final CommandActor actor, final List<String> args) {
        withNationAndTown(actor, args, "nation invite <town>", (who, nation, target) ->
                reply(actor, nations.invite(who, nation.id(), target.id()), invitation ->
                        messages.send(actor::send, MessageKey.NATION_INVITED,
                                MessageService.value("town", target.name().display()),
                                MessageService.value("nation", nation.name().display()))));
    }

    private void withdraw(final CommandActor actor, final List<String> args) {
        withNationAndTown(actor, args, "nation withdraw <town>", (who, nation, target) ->
                reply(actor, nations.withdraw(who, nation.id(), target.id()), ignored ->
                        messages.send(actor::send, MessageKey.NATION_INVITE_WITHDRAWN,
                                MessageService.value("town", target.name().display()))));
    }

    private void invitations(final CommandActor actor, final List<String> args) {
        withTown(actor, (who, town) -> then(actor, nations.invitationsFor(town.id()), offers -> {
            if (offers.isEmpty()) {
                messages.send(actor::send, MessageKey.NATION_NO_INVITES);
                return;
            }
            messages.send(actor::send, MessageKey.NATION_INVITES_HEADER,
                    MessageService.value("town", town.name().display()));
            for (final Invitation offer : offers) {
                then(actor, nationRepository.find(NationId.parse(offer.inviter().value().toString())),
                        nation -> messages.sendRaw(actor::send, MessageKey.NATION_INVITES_LINE,
                                MessageService.value("nation", nation
                                        .map(found -> found.name().display())
                                        .orElse(offer.inviter().value().toString())),
                                MessageService.value("expires", offer.expiresAt())));
            }
        }));
    }

    private void join(final CommandActor actor, final List<String> args) {
        if (args.isEmpty()) {
            usage(actor, "nation join <nation>");
            return;
        }
        withTown(actor, (who, town) -> then(actor, nationRepository.findByName(args.getFirst()),
                found -> found.ifPresentOrElse(
                        nation -> reply(actor, nations.accept(who, town.id(), nation.id()),
                                joined -> messages.send(actor::send, MessageKey.NATION_JOINED,
                                        MessageService.value("town", town.name().display()),
                                        MessageService.value("nation", joined.name().display()))),
                        () -> denied(actor, ChangeDenial.NATION_NOT_FOUND))));
    }

    private void leave(final CommandActor actor, final List<String> args) {
        withTown(actor, (who, town) ->
                reply(actor, nations.leave(who, town.id()), nation ->
                        messages.send(actor::send, MessageKey.NATION_LEFT,
                                MessageService.value("town", town.name().display()),
                                MessageService.value("nation", nation.name().display()))));
    }

    private void expel(final CommandActor actor, final List<String> args) {
        withNationAndTown(actor, args, "nation expel <town>", (who, nation, target) ->
                reply(actor, nations.expel(who, nation.id(), target.id()), ignored ->
                        messages.send(actor::send, MessageKey.NATION_EXPELLED,
                                MessageService.value("town", target.name().display()),
                                MessageService.value("nation", nation.name().display()))));
    }

    private void capital(final CommandActor actor, final List<String> args) {
        withNationAndTown(actor, args, "nation capital <town>", (who, nation, target) ->
                reply(actor, nations.moveCapital(who, nation.id(), target.id()), updated ->
                        messages.send(actor::send, MessageKey.NATION_CAPITAL_MOVED,
                                MessageService.value("town", target.name().display()))));
    }

    private void king(final CommandActor actor, final List<String> args) {
        if (args.isEmpty()) {
            usage(actor, "nation king <player>");
            return;
        }
        withNation(actor, (who, nation) -> then(actor, residents.findByName(args.getFirst()),
                target -> target.ifPresentOrElse(
                        found -> reply(actor,
                                nations.transferLeadership(who, nation.id(), found.id()),
                                updated -> messages.send(actor::send, MessageKey.NATION_KING_TRANSFERRED,
                                        MessageService.value("resident", args.getFirst()),
                                        MessageService.value("nation", updated.name().display()))),
                        () -> denied(actor, ChangeDenial.RESIDENT_NOT_FOUND))));
    }

    private void rename(final CommandActor actor, final List<String> args) {
        if (args.isEmpty()) {
            usage(actor, "nation rename <name>");
            return;
        }
        withNation(actor, (who, nation) ->
                reply(actor, nations.rename(who, nation.id(), args.getFirst()), updated ->
                        messages.send(actor::send, MessageKey.NATION_RENAMED,
                                MessageService.value("nation", updated.name().display()))));
    }

    private void disband(final CommandActor actor, final List<String> args) {
        withNation(actor, (who, nation) ->
                reply(actor, nations.disband(who, nation.id()), ignored ->
                        messages.send(actor::send, MessageKey.NATION_DISBANDED,
                                MessageService.value("nation", nation.name().display()))));
    }

    // --- role actions --------------------------------------------------------------------------

    private void roleList(final CommandActor actor, final List<String> args) {
        withNation(actor, (who, nation) -> then(actor, roles.list(nation.id()), found -> {
            messages.send(actor::send, MessageKey.ROLE_LIST_HEADER,
                    MessageService.value("town", nation.name().display()));
            for (final Role role : found) {
                messages.sendRaw(actor::send, MessageKey.ROLE_LIST_LINE,
                        MessageService.value("role", role.name()),
                        MessageService.value("priority", role.priority()),
                        MessageService.value("permissions", role.permissions().size()));
            }
        }));
    }

    private void roleCreate(final CommandActor actor, final List<String> args) {
        if (args.size() < 2) {
            usage(actor, "nation role new <name> <priority>");
            return;
        }
        final Optional<Integer> priority = parsePriority(args.get(1));
        if (priority.isEmpty()) {
            usage(actor, "nation role new <name> <priority>");
            return;
        }
        withNation(actor, (who, nation) -> reply(actor,
                roles.create(who, nation.id(), args.getFirst(), priority.get(), java.util.Set.of()),
                role -> messages.send(actor::send, MessageKey.ROLE_CREATED,
                        MessageService.value("role", role.name()))));
    }

    private void roleDelete(final CommandActor actor, final List<String> args) {
        if (args.isEmpty()) {
            usage(actor, "nation role delete <name>");
            return;
        }
        withNation(actor, (who, nation) -> then(actor, roles.list(nation.id()), found -> {
            final Optional<Role> role = byName(found, args.getFirst());
            if (role.isEmpty()) {
                denied(actor, ChangeDenial.ROLE_NOT_FOUND);
                return;
            }
            reply(actor, roles.delete(who, nation.id(), role.get().id()), ignored ->
                    messages.send(actor::send, MessageKey.ROLE_DELETED,
                            MessageService.value("role", role.get().name())));
        }));
    }

    private void roleAssign(final CommandActor actor, final List<String> args) {
        withRoleAndTarget(actor, args, "nation role assign <player> <role>",
                (who, nation, target, role) -> reply(actor,
                        roles.assign(who, nation.id(), target, role.id()), ignored ->
                                messages.send(actor::send, MessageKey.ROLE_ASSIGNED,
                                        MessageService.value("resident", args.getFirst()),
                                        MessageService.value("role", role.name()))));
    }

    private void roleUnassign(final CommandActor actor, final List<String> args) {
        withRoleAndTarget(actor, args, "nation role unassign <player> <role>",
                (who, nation, target, role) -> reply(actor,
                        roles.unassign(who, nation.id(), target, role.id()), ignored ->
                                messages.send(actor::send, MessageKey.ROLE_UNASSIGNED,
                                        MessageService.value("resident", args.getFirst()),
                                        MessageService.value("role", role.name()))));
    }

    private void withRoleAndTarget(
            final CommandActor actor,
            final List<String> args,
            final String usage,
            final RoleWork work
    ) {
        if (args.size() < 2) {
            usage(actor, usage);
            return;
        }
        withNation(actor, (who, nation) -> then(actor, residents.findByName(args.getFirst()),
                target -> target.ifPresentOrElse(
                        found -> then(actor, roles.list(nation.id()), all -> byName(all, args.get(1))
                                .ifPresentOrElse(
                                        role -> work.accept(who, nation, found.id(), role),
                                        () -> denied(actor, ChangeDenial.ROLE_NOT_FOUND))),
                        () -> denied(actor, ChangeDenial.RESIDENT_NOT_FOUND))));
    }

    private static Optional<Role> byName(final List<Role> roles, final String name) {
        final String normalised = name.toLowerCase(java.util.Locale.ROOT);
        return roles.stream().filter(role -> role.nameNormalised().equals(normalised)).findFirst();
    }

    private static Optional<Integer> parsePriority(final String raw) {
        try {
            return Optional.of(Integer.parseInt(raw));
        } catch (final NumberFormatException notANumber) {
            return Optional.empty();
        }
    }

    // --- plumbing ------------------------------------------------------------------------------

    private Optional<ResidentId> player(final CommandActor actor) {
        final Optional<ResidentId> who = actor.resident();
        if (who.isEmpty()) {
            messages.send(actor::send, MessageKey.COMMAND_PLAYER_ONLY);
        }
        return who;
    }

    /** The actor's town, or a complaint. Nations are reached through a town, never directly. */
    private void withTown(final CommandActor actor, final BiConsumer<ResidentId, Town> work) {
        player(actor).ifPresent(who -> then(actor, residents.find(who), resident -> {
            final Optional<TownId> townId = resident.flatMap(Resident::town);
            if (townId.isEmpty()) {
                messages.send(actor::send, MessageKey.TOWN_NOT_IN_A_TOWN);
                return;
            }
            then(actor, towns.find(townId.get()), town -> town.ifPresentOrElse(
                    found -> work.accept(who, found),
                    () -> denied(actor, ChangeDenial.TOWN_NOT_FOUND)));
        }));
    }

    /** The nation the actor's town belongs to. */
    private void withNation(final CommandActor actor, final BiConsumer<ResidentId, Nation> work) {
        withTown(actor, (who, town) -> {
            final Optional<NationId> nationId = town.nation();
            if (nationId.isEmpty()) {
                denied(actor, ChangeDenial.TOWN_NOT_IN_A_NATION);
                return;
            }
            then(actor, nationRepository.find(nationId.get()), nation -> nation.ifPresentOrElse(
                    found -> work.accept(who, found),
                    () -> denied(actor, ChangeDenial.NATION_NOT_FOUND)));
        });
    }

    private void withNationAndTown(
            final CommandActor actor,
            final List<String> args,
            final String usage,
            final TownWork work
    ) {
        if (args.isEmpty()) {
            usage(actor, usage);
            return;
        }
        withNation(actor, (who, nation) -> then(actor, towns.findByName(args.getFirst()),
                target -> target.ifPresentOrElse(
                        found -> work.accept(who, nation, found),
                        () -> denied(actor, ChangeDenial.TOWN_NOT_FOUND))));
    }

    /** See {@code TownCommands.then}: a bare thenAccept would swallow a storage failure. */
    private <T> void then(
            final CommandActor actor,
            final CompletableFuture<T> pending,
            final Consumer<T> onValue
    ) {
        pending.whenComplete((value, failure) -> {
            if (failure != null) {
                fail(actor, failure);
                return;
            }
            try {
                onValue.accept(value);
            } catch (final RuntimeException thrown) {
                fail(actor, thrown);
            }
        });
    }

    private <T> void reply(
            final CommandActor actor,
            final CompletableFuture<ServiceResult<T>> pending,
            final Consumer<T> onSuccess
    ) {
        pending.whenComplete((result, failure) -> {
            if (failure != null) {
                fail(actor, failure);
                return;
            }
            switch (result) {
                case ServiceResult.Success<T> success -> onSuccess.accept(success.result());
                case ServiceResult.Refused<T> refused -> denied(actor, refused.reason());
                case ServiceResult.NameRejected<T> rejected ->
                        messages.send(actor::send, MessageKey.COMMAND_NAME_REJECTED,
                                MessageService.value("problems", denials.of(rejected.problems())));
            }
        });
    }

    private void fail(final CommandActor actor, final Throwable failure) {
        net.riftbreaker.rifttowny.paper.RiftTownyPlugin.getInstance().getLogger()
                .log(java.util.logging.Level.WARNING, "Command failed for " + actor.name(), failure);
        messages.send(actor::send, MessageKey.COMMAND_FAILED);
    }

    private void denied(final CommandActor actor, final ChangeDenial denial) {
        messages.send(actor::send, MessageKey.COMMAND_DENIED,
                MessageService.value("reason", denials.of(denial)));
    }

    private void usage(final CommandActor actor, final String usage) {
        messages.send(actor::send, MessageKey.COMMAND_USAGE,
                MessageService.value("usage", '/' + usage));
    }

    private void line(final CommandActor actor, final String label, final String value) {
        messages.sendRaw(actor::send, MessageKey.TOWN_INFO_LINE,
                MessageService.value("label", label), MessageService.value("value", value));
    }

    private static List<String> onlinePlayerNames() {
        final List<String> names = new java.util.ArrayList<>();
        org.bukkit.Bukkit.getOnlinePlayers().forEach(player -> names.add(player.getName()));
        return names;
    }

    @FunctionalInterface
    private interface TownWork {
        void accept(ResidentId actor, Nation nation, Town target);
    }

    @FunctionalInterface
    private interface RoleWork {
        void accept(ResidentId actor, Nation nation, ResidentId target, Role role);
    }
}
