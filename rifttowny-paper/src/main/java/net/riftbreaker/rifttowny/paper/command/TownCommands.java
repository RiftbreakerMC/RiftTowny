package net.riftbreaker.rifttowny.paper.command;

import net.riftbreaker.rifttowny.api.ChunkKey;
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
    private final ResidentRepository residents;
    private final net.riftbreaker.rifttowny.domain.org.TownRepository townRepository;
    private final MessageService messages;
    private final DenialText denials;

    public TownCommands(
            final TownService towns,
            final TownRoleService roles,
            final net.riftbreaker.rifttowny.domain.service.TerritoryService territory,
            final ResidentRepository residents,
            final net.riftbreaker.rifttowny.domain.org.TownRepository townRepository,
            final MessageService messages,
            final DenialText denials
    ) {
        this.towns = Objects.requireNonNull(towns, "towns");
        this.roles = Objects.requireNonNull(roles, "roles");
        this.territory = Objects.requireNonNull(territory, "territory");
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
                        .describedAs("Add a resident")
                        .completer((actor, args) -> onlinePlayerNames())
                        .runs(this::add, Surface.CHAT))
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
                .child(roleTree())
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
            line(actor, "Mayor", town.mayor().value().toString());
            line(actor, "Residents", String.valueOf(town.residentCount()));
            line(actor, "Nation", town.nation().map(Object::toString).orElse("none"));
            line(actor, "Trusted", String.valueOf(town.trustedOutsiders().size()));
        });
    }

    private void add(final CommandActor actor, final List<String> args) {
        withTownAndTarget(actor, args, "town add <player>", (who, town, target) ->
                reply(actor, towns.join(who, target, town.id()), updated ->
                        messages.send(actor::send, MessageKey.TOWN_JOINED,
                                MessageService.value("resident", args.getFirst()),
                                MessageService.value("town", updated.name().display()))));
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
                reply(actor, territory.unclaim(who, town.id(), chunk), released ->
                        messages.send(actor::send, MessageKey.TOWN_UNCLAIMED,
                                MessageService.value("chunk", describe(released))))));
    }

    private void homeblock(final CommandActor actor, final List<String> args) {
        whereTheyStand(actor, chunk -> withTown(actor, (who, town) ->
                reply(actor, territory.moveHomeblock(who, town.id(), chunk), moved ->
                        messages.send(actor::send, MessageKey.TOWN_HOMEBLOCK_MOVED,
                                MessageService.value("chunk", describe(moved))))));
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
