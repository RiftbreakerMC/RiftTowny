package net.riftbreaker.rifttowny.paper.command;

import net.riftbreaker.rifttowny.api.ChunkKey;
import net.riftbreaker.rifttowny.domain.flag.FlagOverride;
import net.riftbreaker.rifttowny.domain.flag.FlagSource;
import net.riftbreaker.rifttowny.domain.flag.FlagTarget;
import net.riftbreaker.rifttowny.domain.flag.ProtectionFlag;
import net.riftbreaker.rifttowny.domain.flag.Relationship;
import net.riftbreaker.rifttowny.domain.org.ChangeDenial;
import net.riftbreaker.rifttowny.domain.territory.ClaimKind;
import net.riftbreaker.rifttowny.domain.org.Resident;
import net.riftbreaker.rifttowny.domain.org.ResidentId;
import net.riftbreaker.rifttowny.domain.org.ResidentRepository;
import net.riftbreaker.rifttowny.domain.org.Town;
import net.riftbreaker.rifttowny.domain.org.TownId;
import net.riftbreaker.rifttowny.domain.role.Role;
import net.riftbreaker.rifttowny.domain.service.ServiceResult;
import net.riftbreaker.rifttowny.domain.service.TownRoleService;
import net.riftbreaker.rifttowny.domain.service.TownService;
import net.riftbreaker.rifttowny.paper.command.tree.CommandActor;
import net.riftbreaker.rifttowny.paper.command.tree.CommandNode;
import net.riftbreaker.rifttowny.paper.command.tree.Surface;
import net.riftbreaker.rifttowny.paper.message.DenialText;
import net.riftbreaker.rifttowny.paper.message.MessageKey;
import net.riftbreaker.rifttowny.paper.message.MessageService;
import org.bukkit.Bukkit;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * The {@code /town} tree.
 *
 * <p>Every node here is a thin translation: read the arguments, call a service, render the answer.
 * No rule is decided at this level — authority and invariants are the services' job, and duplicating
 * them here would give two places to disagree the moment one is changed.</p>
 *
 * <p>Actions declare {@link Surface#CHAT} where a clickable component exists and
 * {@link Surface#GUI} once a menu does. Nothing declares {@code GUI} yet, which is exactly what the
 * parity test wants to see: no action is reachable only through a menu.</p>
 */
public final class TownCommands {

    private final TownService towns;
    private final TownRoleService roles;
    private final net.riftbreaker.rifttowny.domain.service.TerritoryService territory;
    private final net.riftbreaker.rifttowny.domain.service.FlagService flags;
    private final net.riftbreaker.rifttowny.domain.service.RuinService ruins;
    private final net.riftbreaker.rifttowny.domain.service.SpawnService spawns;
    private final net.riftbreaker.rifttowny.paper.spawn.TeleportService teleports;
    private final net.riftbreaker.rifttowny.domain.civic.ResidentNames names;
    private final ResidentRepository residents;
    private final net.riftbreaker.rifttowny.domain.org.TownRepository townRepository;
    private final MessageService messages;
    private final DenialText denials;

    public TownCommands(
            final TownService towns,
            final TownRoleService roles,
            final net.riftbreaker.rifttowny.domain.service.TerritoryService territory,
            final net.riftbreaker.rifttowny.domain.service.FlagService flags,
            final net.riftbreaker.rifttowny.domain.service.RuinService ruins,
            final net.riftbreaker.rifttowny.domain.service.SpawnService spawns,
            final net.riftbreaker.rifttowny.paper.spawn.TeleportService teleports,
            final net.riftbreaker.rifttowny.domain.civic.ResidentNames names,
            final ResidentRepository residents,
            final net.riftbreaker.rifttowny.domain.org.TownRepository townRepository,
            final MessageService messages,
            final DenialText denials
    ) {
        this.towns = Objects.requireNonNull(towns, "towns");
        this.roles = Objects.requireNonNull(roles, "roles");
        this.territory = Objects.requireNonNull(territory, "territory");
        this.flags = Objects.requireNonNull(flags, "flags");
        this.ruins = Objects.requireNonNull(ruins, "ruins");
        this.spawns = Objects.requireNonNull(spawns, "spawns");
        this.teleports = Objects.requireNonNull(teleports, "teleports");
        this.names = Objects.requireNonNull(names, "names");
        this.residents = Objects.requireNonNull(residents, "residents");
        this.townRepository = Objects.requireNonNull(townRepository, "townRepository");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.denials = Objects.requireNonNull(denials, "denials");
    }

    /** Builds the tree. Called once at enable. */
    public CommandNode tree() {
        return CommandNode.group("town")
                .child(CommandNode.action("new")
                        .aliases("create", "found")
                        .permission("rifttowny.town.new")
                        .usage("town new <name>")
                        .describedAs("Found a town")
                        .runs(this::found, Surface.CHAT))
                .child(CommandNode.action("info")
                        .permission("rifttowny.town.info")
                        .usage("town info")
                        .describedAs("Show your town")
                        .runs(this::info, Surface.CHAT))
                .child(CommandNode.action("add")
                        .aliases("invite")
                        .permission("rifttowny.town.add")
                        .usage("town add <player>")
                        .describedAs("Invite a player to your town")
                        .completer((actor, args) -> onlinePlayerNames())
                        .runs(this::add, Surface.CHAT))
                .child(CommandNode.action("uninvite")
                        .aliases("withdraw")
                        .permission("rifttowny.town.add")
                        .usage("town uninvite <player>")
                        .describedAs("Withdraw an invitation")
                        .completer((actor, args) -> onlinePlayerNames())
                        .runs(this::uninvite, Surface.CHAT))
                .child(CommandNode.action("invites")
                        .permission("rifttowny.town.invites")
                        .usage("town invites")
                        .describedAs("Show the towns that have invited you")
                        .runs(this::invites, Surface.CHAT))
                .child(CommandNode.action("accept")
                        .aliases("join")
                        .permission("rifttowny.town.invites")
                        .usage("town accept <town>")
                        .describedAs("Accept an invitation")
                        .runs(this::accept, Surface.CHAT))
                .child(CommandNode.action("deny")
                        .aliases("decline")
                        .permission("rifttowny.town.invites")
                        .usage("town deny <town>")
                        .describedAs("Turn an invitation down")
                        .runs(this::decline, Surface.CHAT))
                .child(CommandNode.action("kick")
                        .aliases("remove")
                        .permission("rifttowny.town.kick")
                        .usage("town kick <player>")
                        .describedAs("Remove a resident")
                        .completer((actor, args) -> onlinePlayerNames())
                        .runs(this::kick, Surface.CHAT))
                .child(CommandNode.action("leave")
                        .permission("rifttowny.town.leave")
                        .usage("town leave")
                        .describedAs("Leave your town")
                        .runs(this::leave, Surface.CHAT))
                .child(CommandNode.action("rename")
                        .permission("rifttowny.town.rename")
                        .usage("town rename <name>")
                        .describedAs("Rename your town")
                        .runs(this::rename, Surface.CHAT))
                .child(CommandNode.action("mayor")
                        .permission("rifttowny.town.mayor")
                        .usage("town mayor <player>")
                        .describedAs("Hand over the mayoralty")
                        .completer((actor, args) -> onlinePlayerNames())
                        .runs(this::mayor, Surface.CHAT))
                .child(CommandNode.action("delete")
                        .aliases("disband")
                        .permission("rifttowny.town.delete")
                        .usage("town delete")
                        .describedAs("Disband your town")
                        .runs(this::disband, Surface.CHAT))
                .child(CommandNode.action("claim")
                        .permission("rifttowny.town.claim")
                        .usage("town claim [outpost]")
                        .describedAs("Claim the chunk you are standing in")
                        .completer((actor, args) -> List.of("outpost"))
                        .runs(this::claim, Surface.CHAT))
                .child(CommandNode.action("unclaim")
                        .permission("rifttowny.town.unclaim")
                        .usage("town unclaim")
                        .describedAs("Release the chunk you are standing in")
                        .runs(this::unclaim, Surface.CHAT))
                .child(CommandNode.action("preview")
                        .permission("rifttowny.town.claim")
                        .usage("town preview [outpost]")
                        .describedAs("See what claiming here would do")
                        .completer((actor, args) -> List.of("outpost"))
                        .runs(this::previewClaim, Surface.CHAT))
                .child(CommandNode.action("homeblock")
                        .permission("rifttowny.town.homeblock")
                        .usage("town homeblock")
                        .describedAs("Move your home chunk here")
                        .runs(this::homeblock, Surface.CHAT))
                .child(CommandNode.action("spawn")
                        .permission("rifttowny.town.spawn")
                        .usage("town spawn")
                        .describedAs("Travel to your town's spawn")
                        .runs(this::spawn, Surface.CHAT))
                .child(CommandNode.action("setspawn")
                        .permission("rifttowny.town.setspawn")
                        .usage("town setspawn")
                        .describedAs("Set your town's spawn to where you stand")
                        .runs(this::setSpawn, Surface.CHAT))
                .child(CommandNode.action("delspawn")
                        .aliases("unsetspawn")
                        .permission("rifttowny.town.setspawn")
                        .usage("town delspawn")
                        .describedAs("Remove your town's spawn")
                        .runs(this::deleteSpawn, Surface.CHAT))
                .child(CommandNode.action("reclaim")
                        .permission("rifttowny.town.reclaim")
                        .usage("town reclaim")
                        .describedAs("Rebuild the fallen town you are standing in")
                        .runs(this::reclaim, Surface.CHAT))
                .child(roleTree())
                .child(flagTree())
                .build();
    }

    /**
     * The {@code /town flag} tree.
     *
     * <p>Two scopes, because a town wants both: {@code set} and {@code clear} apply to all of its
     * land, {@code here} to the one chunk the player is standing in. A claim override beats an
     * organisation one, which is what lets a town open a single market square without opening the
     * rest of itself.</p>
     */
    private CommandNode flagTree() {
        return CommandNode.group("flag")
                .permission("rifttowny.town.flag")
                .describedAs("Change what your town allows")
                .usage("town flag")
                .child(CommandNode.action("list")
                        .permission("rifttowny.town.flag")
                        .usage("town flag list [here]")
                        .describedAs("Show what your town has overridden")
                        .completer((actor, args) -> List.of("here"))
                        .runs(this::flagList, Surface.CHAT))
                .child(CommandNode.action("set")
                        .permission("rifttowny.town.flag")
                        .usage("town flag set <flag> <relationship> <allow|deny>")
                        .describedAs("Set a flag for the whole town")
                        .completer(TownCommands::completeFlagArguments)
                        .runs((actor, args) -> flagSet(actor, args, false), Surface.CHAT))
                .child(CommandNode.action("clear")
                        .permission("rifttowny.town.flag")
                        .usage("town flag clear <flag> <relationship>")
                        .describedAs("Remove a town-wide override")
                        .completer(TownCommands::completeFlagArguments)
                        .runs((actor, args) -> flagClear(actor, args, false), Surface.CHAT))
                .child(CommandNode.action("here")
                        .permission("rifttowny.town.flag")
                        .usage("town flag here <flag> <relationship> <allow|deny|clear>")
                        .describedAs("Set a flag on this chunk only")
                        .completer(TownCommands::completeFlagArguments)
                        .runs(this::flagHere, Surface.CHAT))
                .build();
    }

    private CommandNode roleTree() {
        return CommandNode.group("role")
                .permission("rifttowny.role.view")
                .describedAs("Manage town roles")
                .usage("town role")
                .child(CommandNode.action("list")
                        // Repeated from the parent deliberately. The executor tests only the
                        // resolved node, on the assumption that a child is at least as narrow as
                        // its parent; a child with no permission under a gated parent breaks that
                        // assumption and lets /town role list past a gate /town role refuses.
                        .permission("rifttowny.role.view")
                        .usage("town role list")
                        .describedAs("List the town's roles")
                        .runs(this::roleList, Surface.CHAT))
                .child(CommandNode.action("new")
                        .aliases("create")
                        .permission("rifttowny.role.manage")
                        .usage("town role new <name> <priority>")
                        .describedAs("Create a role")
                        .runs(this::roleCreate, Surface.CHAT))
                .child(CommandNode.action("delete")
                        .permission("rifttowny.role.manage")
                        .usage("town role delete <name>")
                        .describedAs("Delete a role")
                        .completer((actor, args) -> List.of())
                        .runs(this::roleDelete, Surface.CHAT))
                .child(CommandNode.action("assign")
                        .aliases("give")
                        .permission("rifttowny.role.assign")
                        .usage("town role assign <player> <role>")
                        .describedAs("Give a resident a role")
                        .completer((actor, args) -> args.size() <= 1 ? onlinePlayerNames() : List.of())
                        .runs(this::roleAssign, Surface.CHAT))
                .child(CommandNode.action("unassign")
                        .aliases("take")
                        .permission("rifttowny.role.assign")
                        .usage("town role unassign <player> <role>")
                        .describedAs("Take a role away")
                        .completer((actor, args) -> args.size() <= 1 ? onlinePlayerNames() : List.of())
                        .runs(this::roleUnassign, Surface.CHAT))
                .build();
    }

    // --- town actions --------------------------------------------------------------------------

    private void found(final CommandActor actor, final List<String> args) {
        player(actor).ifPresent(who -> {
            if (args.isEmpty()) {
                usage(actor, "town new <name>");
                return;
            }
            reply(actor, towns.found(who, actor.name(), args.getFirst()), town ->
                    messages.send(actor::send, MessageKey.TOWN_FOUNDED,
                            MessageService.value("town", town.name().display())));
        });
    }

    private void info(final CommandActor actor, final List<String> args) {
        withTown(actor, (who, town) -> {
            messages.send(actor::send, MessageKey.TOWN_INFO_HEADER,
                    MessageService.value("town", town.name().display()));
            line(actor, "Mayor", names.describe(town.mayor()));
            line(actor, "Residents", String.valueOf(town.residentCount()));
            line(actor, "Nation", town.nation().map(Object::toString).orElse("none"));
            line(actor, "Trusted", String.valueOf(town.trustedOutsiders().size()));
        });
    }

    private void add(final CommandActor actor, final List<String> args) {
        withTownAndTarget(actor, args, "town add <player>", (who, town, target) ->
                reply(actor, towns.invite(who, town.id(), target), invitation ->
                        messages.send(actor::send, MessageKey.TOWN_INVITED,
                                MessageService.value("resident", args.getFirst()),
                                MessageService.value("town", town.name().display()))));
    }

    private void uninvite(final CommandActor actor, final List<String> args) {
        withTownAndTarget(actor, args, "town uninvite <player>", (who, town, target) ->
                reply(actor, towns.withdrawInvitation(who, town.id(), target), ignored ->
                        messages.send(actor::send, MessageKey.TOWN_INVITE_WITHDRAWN,
                                MessageService.value("resident", args.getFirst()))));
    }

    /** What the player has been offered. Their own list, so it needs no town. */
    private void invites(final CommandActor actor, final List<String> args) {
        player(actor).ifPresent(who -> then(actor, towns.invitationsFor(who), offers -> {
            if (offers.isEmpty()) {
                messages.send(actor::send, MessageKey.TOWN_NO_INVITES);
                return;
            }
            messages.send(actor::send, MessageKey.TOWN_INVITES_HEADER);
            for (final var offer : offers) {
                then(actor, townRepository.find(TownId.parse(offer.inviter().value().toString())),
                        town -> messages.sendRaw(actor::send, MessageKey.TOWN_INVITES_LINE,
                                MessageService.value("town", town
                                        .map(found -> found.name().display())
                                        .orElse(offer.inviter().value().toString())),
                                MessageService.value("expires", offer.expiresAt())));
            }
        }));
    }

    private void accept(final CommandActor actor, final List<String> args) {
        withInvitingTown(actor, args, "town accept <town>", (who, town) ->
                reply(actor, towns.acceptInvitation(who, town.id()), updated ->
                        messages.send(actor::send, MessageKey.TOWN_JOINED,
                                MessageService.value("resident", actor.name()),
                                MessageService.value("town", updated.name().display()))));
    }

    private void decline(final CommandActor actor, final List<String> args) {
        withInvitingTown(actor, args, "town deny <town>", (who, town) ->
                reply(actor, towns.declineInvitation(who, town.id()), ignored ->
                        messages.send(actor::send, MessageKey.TOWN_INVITE_DECLINED,
                                MessageService.value("town", town.name().display()))));
    }

    /** Resolves a town by name for a player answering an offer, who is in no town of their own. */
    private void withInvitingTown(
            final CommandActor actor,
            final List<String> args,
            final String usage,
            final BiConsumer<ResidentId, Town> work
    ) {
        if (args.isEmpty()) {
            usage(actor, usage);
            return;
        }
        player(actor).ifPresent(who ->
                then(actor, townRepository.findByName(args.getFirst()), found ->
                        found.ifPresentOrElse(
                                town -> work.accept(who, town),
                                () -> denied(actor, ChangeDenial.TOWN_NOT_FOUND))));
    }

    private void kick(final CommandActor actor, final List<String> args) {
        withTownAndTarget(actor, args, "town kick <player>", (who, town, target) ->
                reply(actor, towns.kick(who, target, town.id()), updated ->
                        messages.send(actor::send, MessageKey.TOWN_KICKED,
                                MessageService.value("resident", args.getFirst()),
                                MessageService.value("town", updated.name().display()))));
    }

    private void leave(final CommandActor actor, final List<String> args) {
        withTown(actor, (who, town) ->
                reply(actor, towns.leave(who, town.id()), updated ->
                        messages.send(actor::send, MessageKey.TOWN_LEFT,
                                MessageService.value("town", updated.name().display()))));
    }

    private void rename(final CommandActor actor, final List<String> args) {
        if (args.isEmpty()) {
            usage(actor, "town rename <name>");
            return;
        }
        withTown(actor, (who, town) ->
                reply(actor, towns.rename(who, town.id(), args.getFirst()), updated ->
                        messages.send(actor::send, MessageKey.TOWN_RENAMED,
                                MessageService.value("town", updated.name().display()))));
    }

    private void mayor(final CommandActor actor, final List<String> args) {
        withTownAndTarget(actor, args, "town mayor <player>", (who, town, target) ->
                reply(actor, towns.transferMayoralty(who, town.id(), target), updated ->
                        messages.send(actor::send, MessageKey.TOWN_MAYOR_TRANSFERRED,
                                MessageService.value("resident", args.getFirst()),
                                MessageService.value("town", updated.name().display()))));
    }

    private void disband(final CommandActor actor, final List<String> args) {
        withTown(actor, (who, town) ->
                reply(actor, towns.disband(who, town.id()), ignored ->
                        messages.send(actor::send, MessageKey.TOWN_DISBANDED,
                                MessageService.value("town", town.name().display()))));
    }

    // --- territory actions ---------------------------------------------------------------------

    private void claim(final CommandActor actor, final List<String> args) {
        whereTheyStand(actor, chunk -> withTown(actor, (who, town) -> {
            final ClaimKind kind = kindFrom(args, town);
            reply(actor, territory.claim(who, town.id(), chunk, kind), created ->
                    then(actor, territory.territoryOf(town.id()), all ->
                            messages.send(actor::send, MessageKey.TOWN_CLAIMED,
                                    MessageService.value("chunk", describe(chunk)),
                                    MessageService.value("kind", created.kind()),
                                    MessageService.value("total", all.size()))));
        }));
    }

    private void unclaim(final CommandActor actor, final List<String> args) {
        whereTheyStand(actor, chunk -> withTown(actor, (who, town) ->
                reply(actor, territory.unclaim(who, town.id(), chunk), released -> {
                    messages.send(actor::send, MessageKey.TOWN_UNCLAIMED,
                            MessageService.value("chunk", describe(released)));
                    // Releasing the chunk a spawn stood in takes the spawn with it, and the person
                    // who did it is the one who should hear about it - not the next resident to
                    // find the command broken.
                    then(actor, spawns.clearIfOutsideTerritory(town.id()), lost -> {
                        if (Boolean.TRUE.equals(lost)) {
                            messages.send(actor::send, MessageKey.TOWN_SPAWN_LOST_WITH_LAND);
                        }
                    });
                })));
    }

    private void homeblock(final CommandActor actor, final List<String> args) {
        whereTheyStand(actor, chunk -> withTown(actor, (who, town) ->
                reply(actor, territory.moveHomeblock(who, town.id(), chunk), moved ->
                        messages.send(actor::send, MessageKey.TOWN_HOMEBLOCK_MOVED,
                                MessageService.value("chunk", describe(moved.newChunk()))))));
    }

    private void previewClaim(final CommandActor actor, final List<String> args) {
        whereTheyStand(actor, chunk -> withTown(actor, (who, town) ->
                then(actor, territory.previewClaim(town.id(), chunk, kindFrom(args, town)),
                        preview -> {
                            if (preview.permitted()) {
                                messages.send(actor::send, MessageKey.TOWN_CLAIM_PREVIEW_OK,
                                        MessageService.value("chunk", describe(chunk)),
                                        MessageService.value("before", preview.claimsBefore()),
                                        MessageService.value("after", preview.claimsAfter()));
                            } else {
                                messages.send(actor::send, MessageKey.TOWN_CLAIM_PREVIEW_REFUSED,
                                        MessageService.value("chunk", describe(chunk)),
                                        MessageService.value("reason",
                                                denials.of(preview.refusal().orElseThrow())));
                            }
                        })));
    }

    /**
     * Which kind the player meant.
     *
     * <p>A town with no land at all is founding its homeblock, so the word is ignored there:
     * {@code TownClaims} refuses anything else as the first claim, and offering a choice that will
     * always be refused is worse than making it for them.</p>
     */
    private ClaimKind kindFrom(final List<String> args, final Town town) {
        if (!args.isEmpty() && args.getFirst().equalsIgnoreCase("outpost")) {
            return ClaimKind.OUTPOST;
        }
        return ClaimKind.ORDINARY;
    }

    /** Runs work with the chunk the actor is standing in, or says the console is nowhere. */
    private void whereTheyStand(final CommandActor actor, final Consumer<ChunkKey> work) {
        actor.chunk().ifPresentOrElse(
                work,
                () -> messages.send(actor::send, MessageKey.TOWN_CONSOLE_HAS_NO_CHUNK));
    }

    private static String describe(final ChunkKey chunk) {
        return chunk.chunkX() + ", " + chunk.chunkZ();
    }

    // --- role actions --------------------------------------------------------------------------

    private void roleList(final CommandActor actor, final List<String> args) {
        withTown(actor, (who, town) -> then(actor, roles.list(town.id()), found -> {
            messages.send(actor::send, MessageKey.ROLE_LIST_HEADER,
                    MessageService.value("town", town.name().display()));
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
            usage(actor, "town role new <name> <priority>");
            return;
        }
        final Optional<Integer> priority = parsePriority(args.get(1));
        if (priority.isEmpty()) {
            usage(actor, "town role new <name> <priority>");
            return;
        }
        withTown(actor, (who, town) -> reply(actor,
                roles.create(who, town.id(), args.getFirst(), priority.get(), java.util.Set.of()),
                role -> messages.send(actor::send, MessageKey.ROLE_CREATED,
                        MessageService.value("role", role.name()))));
    }

    private void roleDelete(final CommandActor actor, final List<String> args) {
        if (args.isEmpty()) {
            usage(actor, "town role delete <name>");
            return;
        }
        withTown(actor, (who, town) -> then(actor, roles.list(town.id()), found -> {
            final Optional<Role> role = byName(found, args.getFirst());
            if (role.isEmpty()) {
                denied(actor, ChangeDenial.ROLE_NOT_FOUND);
                return;
            }
            reply(actor, roles.delete(who, town.id(), role.get().id()), ignored ->
                    messages.send(actor::send, MessageKey.ROLE_DELETED,
                            MessageService.value("role", role.get().name())));
        }));
    }

    private void roleAssign(final CommandActor actor, final List<String> args) {
        withRoleAndTarget(actor, args, "town role assign <player> <role>",
                (who, town, target, role) -> reply(actor,
                        roles.assign(who, town.id(), target, role.id()), ignored ->
                                messages.send(actor::send, MessageKey.ROLE_ASSIGNED,
                                        MessageService.value("resident", args.getFirst()),
                                        MessageService.value("role", role.name()))));
    }

    private void roleUnassign(final CommandActor actor, final List<String> args) {
        withRoleAndTarget(actor, args, "town role unassign <player> <role>",
                (who, town, target, role) -> reply(actor,
                        roles.unassign(who, town.id(), target, role.id()), ignored ->
                                messages.send(actor::send, MessageKey.ROLE_UNASSIGNED,
                                        MessageService.value("resident", args.getFirst()),
                                        MessageService.value("role", role.name()))));
    }

    // --- spawn actions -------------------------------------------------------------------------

    /**
     * Travels to the town's spawn.
     *
     * <p>The destination is resolved and authorised in one go before anybody moves — the service
     * checks the role permission and that the town still owns the land, because a teleport costs a
     * chunk load and a stale spawn drops the player in somebody else's territory.</p>
     */
    private void spawn(final CommandActor actor, final List<String> args) {
        player(actor).ifPresent(who -> {
            // Checked before the lookup: a player on cooldown does not need a database round trip
            // to be told to wait.
            final var wait = teleports.cooldownRemaining(who);
            if (wait.isPresent()) {
                messages.send(actor::send, MessageKey.TOWN_SPAWN_COOLDOWN,
                        MessageService.value("remaining",
                                net.riftbreaker.rifttowny.paper.spawn.SpawnCooldown
                                        .describe(wait.get())));
                return;
            }
            withTown(actor, (ignored, town) ->
                    reply(actor, spawns.travelTo(who, town.id()), destination -> {
                        if (!teleports.warmup().isZero()) {
                            messages.send(actor::send, MessageKey.TOWN_SPAWN_WARMUP,
                                    MessageService.value("seconds",
                                            teleports.warmup().toSeconds()));
                        }
                        then(actor, teleports.travel(who, destination),
                                outcome -> announceArrival(actor, town, outcome));
                    }));
        });
    }

    private void announceArrival(
            final CommandActor actor,
            final Town town,
            final net.riftbreaker.rifttowny.paper.spawn.TeleportService.Outcome outcome
    ) {
        switch (outcome) {
            case ARRIVED -> messages.send(actor::send, MessageKey.TOWN_SPAWN_ARRIVED,
                    MessageService.value("town", town.name().display()));
            case CANCELLED_MOVED ->
                    messages.send(actor::send, MessageKey.TOWN_SPAWN_CANCELLED_MOVED);
            case CANCELLED_DAMAGED ->
                    messages.send(actor::send, MessageKey.TOWN_SPAWN_CANCELLED_DAMAGED);
            // Superseded and gone-offline both mean the player is no longer waiting on this one,
            // and saying so would be a message about a teleport they have already replaced or left.
            case SUPERSEDED, NOT_ONLINE -> { }
            case NO_DESTINATION -> messages.send(actor::send, MessageKey.TOWN_SPAWN_FAILED);
        }
    }

    private void setSpawn(final CommandActor actor, final List<String> args) {
        final var here = actor.position();
        if (here.isEmpty()) {
            messages.send(actor::send, MessageKey.TOWN_CONSOLE_HAS_NO_CHUNK);
            return;
        }
        withTown(actor, (who, town) ->
                reply(actor, spawns.set(who, town.id(), here.get()), set ->
                        messages.send(actor::send, MessageKey.TOWN_SPAWN_SET,
                                MessageService.value("town", town.name().display()),
                                MessageService.value("position", set.describe()))));
    }

    private void deleteSpawn(final CommandActor actor, final List<String> args) {
        withTown(actor, (who, town) ->
                reply(actor, spawns.clear(who, town.id()), ignored ->
                        messages.send(actor::send, MessageKey.TOWN_SPAWN_CLEARED,
                                MessageService.value("town", town.name().display()))));
    }

    /**
     * Rebuilds the fallen town the player is standing in.
     *
     * <p>Takes no name: the town comes back as itself. What does not come back is its population —
     * the residents, roles and treasury belonged to people who have moved on.</p>
     */
    private void reclaim(final CommandActor actor, final List<String> args) {
        player(actor).ifPresent(who -> whereTheyStand(actor, chunk -> {
            final var ruin = ruins.at(chunk);
            if (ruin.isEmpty()) {
                denied(actor, ChangeDenial.NOT_A_RUIN);
                return;
            }
            reply(actor, ruins.reclaim(who, actor.name(), chunk), town ->
                    messages.send(actor::send, MessageKey.TOWN_RECLAIMED,
                            MessageService.value("town", town.name().display())));
        }));
    }

    // --- flag actions --------------------------------------------------------------------------

    /** What this town, or this chunk, has been told. Not what resolves — that is a different sum. */
    private void flagList(final CommandActor actor, final List<String> args) {
        final boolean here = !args.isEmpty() && args.getFirst().equalsIgnoreCase("here");
        if (!here) {
            withTown(actor, (who, town) ->
                    showOverrides(actor, FlagTarget.organisation(town.id()), town.name().display()));
            return;
        }
        whereTheyStand(actor, chunk ->
                showOverrides(actor, FlagTarget.claim(chunk), "chunk " + describe(chunk)));
    }

    private void showOverrides(
            final CommandActor actor, final FlagTarget target, final String label) {
        then(actor, flags.of(target), found -> {
            if (found.isEmpty()) {
                messages.send(actor::send, MessageKey.FLAG_LIST_EMPTY,
                        MessageService.value("target", label));
                return;
            }
            messages.send(actor::send, MessageKey.FLAG_LIST_HEADER,
                    MessageService.value("target", label));
            for (final FlagOverride override : found) {
                messages.sendRaw(actor::send, MessageKey.FLAG_LIST_LINE,
                        MessageService.value("flag", override.flag()),
                        MessageService.value("relationship", override.relationship()),
                        MessageService.value("state", override.allowed() ? "allowed" : "denied"));
            }
        });
    }

    private void flagSet(final CommandActor actor, final List<String> args, final boolean here) {
        final String usage = here
                ? "town flag here <flag> <relationship> <allow|deny|clear>"
                : "town flag set <flag> <relationship> <allow|deny>";
        if (args.size() < 3) {
            usage(actor, usage);
            return;
        }
        parseFlag(actor, args.getFirst()).ifPresent(flag ->
                parseRelationship(actor, args.get(1)).ifPresent(relationship -> {
                    final Optional<Boolean> allowed = parseDecision(args.get(2));
                    if (allowed.isEmpty()) {
                        usage(actor, usage);
                        return;
                    }
                    if (here) {
                        whereTheyStand(actor, chunk -> withTown(actor, (who, town) -> reply(actor,
                                flags.setForClaim(who, town.id(), chunk, flag, relationship,
                                        allowed.get()),
                                stored -> announce(actor, stored))));
                        return;
                    }
                    withTown(actor, (who, town) -> reply(actor,
                            flags.setForTown(who, town.id(), flag, relationship, allowed.get()),
                            stored -> announce(actor, stored)));
                }));
    }

    private void flagClear(final CommandActor actor, final List<String> args, final boolean here) {
        final String usage = here
                ? "town flag here <flag> <relationship> clear"
                : "town flag clear <flag> <relationship>";
        if (args.size() < 2) {
            usage(actor, usage);
            return;
        }
        parseFlag(actor, args.getFirst()).ifPresent(flag ->
                parseRelationship(actor, args.get(1)).ifPresent(relationship -> {
                    if (here) {
                        whereTheyStand(actor, chunk -> withTown(actor, (who, town) -> reply(actor,
                                flags.clearForClaim(who, town.id(), chunk, flag, relationship),
                                target -> announceCleared(actor, target, flag, relationship))));
                        return;
                    }
                    withTown(actor, (who, town) -> reply(actor,
                            flags.clearForTown(who, town.id(), flag, relationship),
                            target -> announceCleared(actor, target, flag, relationship)));
                }));
    }

    /** {@code here} takes the same arguments as {@code set}, plus {@code clear} as a decision. */
    private void flagHere(final CommandActor actor, final List<String> args) {
        if (args.size() >= 3 && args.get(2).equalsIgnoreCase("clear")) {
            flagClear(actor, args, true);
            return;
        }
        flagSet(actor, args, true);
    }

    private void announce(final CommandActor actor, final FlagOverride stored) {
        messages.send(actor::send, MessageKey.FLAG_SET,
                MessageService.value("flag", stored.flag()),
                MessageService.value("relationship", stored.relationship()),
                MessageService.value("state", stored.allowed() ? "allowed" : "denied"),
                MessageService.value("scope", scopeLabel(stored.source())));
    }

    private void announceCleared(
            final CommandActor actor,
            final FlagTarget target,
            final ProtectionFlag flag,
            final Relationship relationship
    ) {
        messages.send(actor::send, MessageKey.FLAG_CLEARED,
                MessageService.value("flag", flag),
                MessageService.value("relationship", relationship),
                MessageService.value("scope", scopeLabel(target.source())));
    }

    private static String scopeLabel(final FlagSource source) {
        return source == FlagSource.CLAIM ? "this chunk" : "town-wide";
    }

    private Optional<ProtectionFlag> parseFlag(final CommandActor actor, final String raw) {
        final Optional<ProtectionFlag> flag = ProtectionFlag.parse(raw);
        if (flag.isEmpty()) {
            messages.send(actor::send, MessageKey.FLAG_UNKNOWN,
                    MessageService.value("input", raw),
                    MessageService.value("options", names(ProtectionFlag.values())));
        }
        return flag;
    }

    private Optional<Relationship> parseRelationship(final CommandActor actor, final String raw) {
        final Optional<Relationship> relationship = Relationship.parse(raw);
        if (relationship.isEmpty()) {
            messages.send(actor::send, MessageKey.FLAG_UNKNOWN_RELATIONSHIP,
                    MessageService.value("input", raw),
                    MessageService.value("options", names(Relationship.values())));
        }
        return relationship;
    }

    /** {@code allow} or {@code deny}. Anything else is a usage error rather than a guess. */
    private static Optional<Boolean> parseDecision(final String raw) {
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "allow", "allowed", "true", "on", "yes" -> Optional.of(true);
            case "deny", "denied", "false", "off", "no" -> Optional.of(false);
            default -> Optional.empty();
        };
    }

    private static String names(final Enum<?>[] values) {
        final List<String> names = new ArrayList<>(values.length);
        for (final Enum<?> value : values) {
            names.add(value.name().toLowerCase(Locale.ROOT));
        }
        return String.join(", ", names);
    }

    private static List<String> completeFlagArguments(
            final CommandActor actor, final List<String> args) {
        return switch (args.size()) {
            case 0, 1 -> lowerNames(ProtectionFlag.values());
            case 2 -> lowerNames(Relationship.values());
            case 3 -> List.of("allow", "deny", "clear");
            default -> List.of();
        };
    }

    private static List<String> lowerNames(final Enum<?>[] values) {
        final List<String> names = new ArrayList<>(values.length);
        for (final Enum<?> value : values) {
            names.add(value.name().toLowerCase(Locale.ROOT));
        }
        return List.copyOf(names);
    }

    // --- plumbing ------------------------------------------------------------------------------

    /** The actor as a player, complaining if they are the console. */
    private Optional<ResidentId> player(final CommandActor actor) {
        final Optional<ResidentId> who = actor.resident();
        if (who.isEmpty()) {
            messages.send(actor::send, MessageKey.COMMAND_PLAYER_ONLY);
        }
        return who;
    }

    /** Loads the actor's town, or says they have none. */
    private void withTown(final CommandActor actor, final BiConsumer<ResidentId, Town> work) {
        player(actor).ifPresent(who -> then(actor, residents.find(who), resident -> {
            final Optional<TownId> townId = resident.flatMap(Resident::town);
            if (townId.isEmpty()) {
                messages.send(actor::send, MessageKey.TOWN_NOT_IN_A_TOWN);
                return;
            }
            then(actor, townRepository.find(townId.get()), town ->
                    town.ifPresentOrElse(
                            found -> work.accept(who, found),
                            () -> denied(actor, ChangeDenial.TOWN_NOT_FOUND)));
        }));
    }

    private void withTownAndTarget(
            final CommandActor actor,
            final List<String> args,
            final String usage,
            final TargetWork work
    ) {
        if (args.isEmpty()) {
            usage(actor, usage);
            return;
        }
        withTown(actor, (who, town) -> then(actor, residents.findByName(args.getFirst()), target ->
                target.ifPresentOrElse(
                        found -> work.accept(who, town, found.id()),
                        () -> denied(actor, ChangeDenial.RESIDENT_NOT_FOUND))));
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
        withTownAndTarget(actor, args, usage, (who, town, target) ->
                then(actor, roles.list(town.id()), found -> byName(found, args.get(1))
                        .ifPresentOrElse(
                                role -> work.accept(who, town, target, role),
                                () -> denied(actor, ChangeDenial.ROLE_NOT_FOUND))));
    }

    /**
     * Consumes a lookup future, reporting a failure instead of losing it.
     *
     * <p>A bare {@code thenAccept} discards the future it returns, so a storage failure completes
     * that future exceptionally and nothing ever looks at it: the player sees no reply at all and
     * the console logs nothing. Every lookup in this class goes through here, and so does every
     * exception thrown inside the consumer body, which would otherwise vanish the same way.</p>
     */
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

    private void fail(final CommandActor actor, final Throwable failure) {
        net.riftbreaker.rifttowny.paper.RiftTownyPlugin.getInstance().getLogger()
                .log(java.util.logging.Level.WARNING, "Command failed for " + actor.name(), failure);
        messages.send(actor::send, MessageKey.COMMAND_FAILED);
    }

    /**
     * Renders a service result.
     *
     * <p>Also the only place a failed future is reported. Without it a database error would complete
     * the future exceptionally and the player would simply see nothing happen.</p>
     */
    private <T> void reply(
            final CommandActor actor,
            final CompletableFuture<ServiceResult<T>> pending,
            final Consumer<T> onSuccess
    ) {
        pending.whenComplete((result, failure) -> {
            if (failure != null) {
                // Reported as a failure rather than a refusal: "you may not do that" would be a lie
                // when the database is down, and silence would look like the command did nothing.
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

    private void denied(final CommandActor actor, final ChangeDenial denial) {
        messages.send(actor::send, MessageKey.COMMAND_DENIED,
                MessageService.value("reason", denials.of(denial)));
    }

    private void usage(final CommandActor actor, final String usage) {
        messages.send(actor::send, MessageKey.COMMAND_USAGE, MessageService.value("usage", '/' + usage));
    }

    private void line(final CommandActor actor, final String label, final String value) {
        messages.sendRaw(actor::send, MessageKey.TOWN_INFO_LINE,
                MessageService.value("label", label), MessageService.value("value", value));
    }

    private static Optional<Role> byName(final List<Role> roles, final String name) {
        final String normalised = name.toLowerCase(Locale.ROOT);
        return roles.stream().filter(role -> role.nameNormalised().equals(normalised)).findFirst();
    }

    private static Optional<Integer> parsePriority(final String raw) {
        try {
            return Optional.of(Integer.parseInt(raw));
        } catch (final NumberFormatException notANumber) {
            return Optional.empty();
        }
    }

    /**
     * Online players only.
     *
     * <p>Tab completion runs on the server thread while the player is still typing, so it must not
     * touch storage. Offline residents are reachable by typing the name in full, which the service
     * resolves properly.</p>
     */
    private static List<String> onlinePlayerNames() {
        final List<String> names = new ArrayList<>();
        Bukkit.getOnlinePlayers().forEach(player -> names.add(player.getName()));
        return names;
    }

    @FunctionalInterface
    private interface TargetWork {
        void accept(ResidentId actor, Town town, ResidentId target);
    }

    @FunctionalInterface
    private interface RoleWork {
        void accept(ResidentId actor, Town town, ResidentId target, Role role);
    }
}
