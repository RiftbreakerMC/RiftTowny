package net.riftbreaker.rifttowny.paper.command;

import net.riftbreaker.rifttowny.api.ApiVersion;
import net.riftbreaker.rifttowny.api.capability.CapabilityState;
import net.riftbreaker.rifttowny.api.capability.CapabilityStatus;
import net.riftbreaker.rifttowny.paper.RiftTownyPlugin;
import net.riftbreaker.rifttowny.paper.message.MessageKey;
import net.riftbreaker.rifttowny.paper.message.MessageService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code /rifttowny} — RiftTowny-specific administration.
 *
 * <p>Separate from {@code /townyadmin}, which mirrors the familiar Towny administration tree. This
 * one is for things Towny never had: platform and storage diagnostics, cache statistics, and the
 * real state of every integration.</p>
 */
public final class RiftTownyCommand implements CommandExecutor, TabCompleter {

    private static final String PERMISSION_STATUS = "rifttowny.admin.status";
    private static final String PERMISSION_MIGRATE = "rifttowny.admin.migrate";
    private static final String PERMISSION_FLAG = "rifttowny.admin.flag";

    /** The scope word for the whole server, as opposed to one world. */
    private static final String SERVER = "server";

    /** The scope word that takes a world name after it. */
    private static final String WORLD = "world";

    /**
     * The word that turns a dry run into a real one.
     *
     * <p>Not {@code yes}, not {@code -f}, and not a second command that is one keystroke from the
     * first. An import is the only irreversible bulk operation here, and the word has to be one
     * nobody types by accident or by muscle memory.</p>
     */
    private static final String CONFIRMATION = "confirm";

    /**
     * The plugin, resolved per call rather than captured.
     *
     * <p>A command object outlives a {@code /reload}: the server keeps the registered executor
     * while the plugin instance behind it is replaced. Holding a reference would leave this command
     * talking to a disabled plugin whose database is already closed.</p>
     */
    private static RiftTownyPlugin plugin() {
        return RiftTownyPlugin.getInstance();
    }

    @Override
    public boolean onCommand(
            @NotNull final CommandSender sender,
            @NotNull final Command command,
            @NotNull final String label,
            final String @NotNull [] args
    ) {
        final MessageService messages = plugin().messages();
        final String subcommand = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);

        switch (subcommand) {
            case "status" -> {
                if (!hasPermission(sender, PERMISSION_STATUS)) {
                    messages.send(sender, MessageKey.COMMAND_NO_PERMISSION);
                    return true;
                }
                sendStatus(sender);
            }
            case "migrate" -> {
                if (!hasPermission(sender, PERMISSION_MIGRATE)) {
                    messages.send(sender, MessageKey.COMMAND_NO_PERMISSION);
                    return true;
                }
                migrate(sender, args);
            }
            case "flag" -> {
                if (!hasPermission(sender, PERMISSION_FLAG)) {
                    messages.send(sender, MessageKey.COMMAND_NO_PERMISSION);
                    return true;
                }
                flag(sender, args);
            }
            case "help" -> sendHelp(sender);
            default -> messages.send(sender, MessageKey.COMMAND_UNKNOWN_SUBCOMMAND,
                    MessageService.value("input", args[0]),
                    MessageService.value("command", "/rifttowny help"));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(
            @NotNull final CommandSender sender,
            @NotNull final Command command,
            @NotNull final String label,
            final String @NotNull [] args
    ) {
        final List<String> candidates = insideFlag(args)
                        ? completeFlag(args)
                        : switch (args.length) {
                            case 1 -> List.of("status", "migrate", "flag", "help");
                            case 2 -> "migrate".equalsIgnoreCase(args[0])
                                    ? List.of("towny") : List.of();
                            // Deliberately not offering the confirmation word. Tab-completing your
                            // way into an irreversible bulk import is exactly what the word is
                            // there to prevent.
                            default -> List.of();
                        };
        if (candidates.isEmpty()) {
            return List.of();
        }
        final String partial = args[args.length - 1];
        final List<String> options = new ArrayList<>();
        for (final String candidate : candidates) {
            // Case-insensitively on both sides: every other candidate here is a lower-case literal,
            // but a world name is whatever the operator called their world, and comparing a capital
            // 'N' against a lower-cased partial would leave Nether uncompletable.
            if (candidate.regionMatches(true, 0, partial, 0, partial.length())) {
                options.add(candidate);
            }
        }
        return options;
    }

    /**
     * What to offer inside {@code /rifttowny flag}.
     *
     * <p>Position-dependent, and the scope is one or two words, so everything after it shifts by
     * one when a world was named. Reading the already-typed scope rather than counting from the end
     * is what keeps {@code flag set world nether <tab>} offering flags rather than world names.</p>
     */
    private static List<String> completeFlag(final String[] args) {
        return switch (flagArgument(args)) {
            case VERB -> List.of("set", "clear", "list");
            case SCOPE -> List.of(SERVER, WORLD);
            case WORLD_NAME -> {
                final List<String> worlds = new ArrayList<>();
                for (final org.bukkit.World world : plugin().getServer().getWorlds()) {
                    worlds.add(world.getName());
                }
                yield worlds;
            }
            case FLAG -> lowerNameList(
                    net.riftbreaker.rifttowny.domain.flag.ProtectionFlag.values());
            case RELATIONSHIP -> lowerNameList(
                    net.riftbreaker.rifttowny.domain.flag.Relationship.values());
            case DECISION -> List.of("allow", "deny");
            case NONE -> List.of();
        };
    }

    /**
     * Whether completion is inside {@code /rifttowny flag} rather than still naming a subcommand.
     *
     * <p>The length test is the whole point: on the first argument the operator is still typing the
     * word {@code flag} itself, and routing there would answer with its verbs instead of completing
     * its name — so typing {@code flag} and pressing tab would offer nothing at all.</p>
     */
    static boolean insideFlag(final String[] args) {
        return args.length > 1 && "flag".equalsIgnoreCase(args[0]);
    }

    /** Which argument of {@code /rifttowny flag} is being typed. */
    enum FlagArgument { VERB, SCOPE, WORLD_NAME, FLAG, RELATIONSHIP, DECISION, NONE }

    /**
     * Which argument the operator is on, from what they have typed so far.
     *
     * <p>Its own function because the scope is one word or two, so everything after it shifts by one
     * when a world was named — and getting that wrong is invisible until somebody tab-completes a
     * world name into a flag slot. Pure, so it can be tested without a server.</p>
     *
     * @param args everything after {@code /rifttowny}, the partial word included
     */
    static FlagArgument flagArgument(final String[] args) {
        final int scopeAt = 2;
        if (args.length <= scopeAt) {
            return FlagArgument.VERB;
        }
        if (args.length == scopeAt + 1) {
            return FlagArgument.SCOPE;
        }
        final boolean named = WORLD.equalsIgnoreCase(args[scopeAt]);
        if (named && args.length == scopeAt + 2) {
            return FlagArgument.WORLD_NAME;
        }
        // 'list' takes nothing after its scope, so offering a flag there would suggest an argument
        // the command would then refuse.
        if ("list".equalsIgnoreCase(args[1])) {
            return FlagArgument.NONE;
        }
        return switch (args.length - (named ? scopeAt + 3 : scopeAt + 2)) {
            case 0 -> FlagArgument.FLAG;
            case 1 -> FlagArgument.RELATIONSHIP;
            case 2 -> "set".equalsIgnoreCase(args[1]) ? FlagArgument.DECISION : FlagArgument.NONE;
            default -> FlagArgument.NONE;
        };
    }

    /**
     * A reply sink that lands on the right thread.
     *
     * <p>Everything in this command that waits on the database answers from the future's own
     * thread, and on Folia writing to a player from there is not allowed. The tree commands have
     * always gone through {@link BukkitCommandActor} for this; this one sent straight to the
     * {@code CommandSender}, which is the same bug three times over.</p>
     *
     * <p>Only needed for a reply that arrives after an await. A message sent while still handling
     * the command is already on the right thread and goes to the sender directly.</p>
     */
    private static java.util.function.Consumer<net.kyori.adventure.text.Component> replyTo(
            final CommandSender sender) {
        return new BukkitCommandActor(sender, plugin().scheduler())::send;
    }

    /**
     * {@code /rifttowny migrate towny [confirm]} — brings a Towny database in.
     *
     * <p>A dry run unless the confirmation word is typed. Everything below runs off the server
     * thread: it opens a second database connection, reads an entire Towny installation and then
     * writes one, none of which a tick can wait for.</p>
     */
    private void migrate(final CommandSender sender, final String[] args) {
        final MessageService messages = plugin().messages();
        final var configured = plugin().settings().townyImport();

        if (args.length < 2 || !"towny".equalsIgnoreCase(args[1])) {
            messages.send(sender, MessageKey.COMMAND_USAGE,
                    MessageService.value("usage", "/rifttowny migrate towny [" + CONFIRMATION + ']'));
            return;
        }
        if (!configured.isConfigured()) {
            messages.send(sender, MessageKey.MIGRATE_NOT_CONFIGURED);
            return;
        }
        if (configured.isAmbiguous()) {
            // Refused rather than resolved by precedence: a server that moved from flatfile to
            // MySQL has both on disk, and the flatfiles are the stale half.
            messages.send(sender, MessageKey.MIGRATE_AMBIGUOUS);
            return;
        }
        final boolean apply = args.length > 2 && CONFIRMATION.equalsIgnoreCase(args[2]);

        messages.send(sender, MessageKey.MIGRATE_STARTED,
                MessageService.value("source", configured.describe()),
                MessageService.value("mode", apply ? "importing" : "dry run"));

        plugin().scheduler().async(() -> {
            final var source = configured.usesFlatfile()
                    ? new net.riftbreaker.rifttowny.storage.migration.TownyFlatFileSource(
                            java.nio.file.Path.of(configured.dataFolder()))
                    : new net.riftbreaker.rifttowny.storage.migration.TownySqlSource(
                            configured.jdbcUrl(), configured.username(), configured.password(),
                            configured.tablePrefix());
            final net.riftbreaker.rifttowny.domain.migration.MigrationPlan plan;
            try {
                plan = source.read();
            } catch (final net.riftbreaker.rifttowny.domain.migration.MigrationSource
                    .MigrationException unreadable) {
                // The message names the database and the driver's own complaint. Never the
                // password: the URL is printed through describe(), which cuts the query string.
                plugin().getLogger().warning("Towny import could not read the source: "
                        + unreadable.getMessage());
                messages.send(replyTo(sender), MessageKey.MIGRATE_UNREADABLE,
                        MessageService.value("reason", unreadable.getMessage()));
                return;
            }

            messages.send(replyTo(sender), MessageKey.MIGRATE_READ,
                    MessageService.value("summary", plan.describe()));
            source.notes().forEach(note -> messages.sendRaw(replyTo(sender), MessageKey.MIGRATE_PROBLEM,
                    MessageService.value("problem", note)));

            final var pending = apply
                    ? plugin().importer().apply(plan)
                    : plugin().importer().preview(plan);

            pending.whenComplete((report, failure) -> {
                if (failure != null) {
                    plugin().getLogger().log(java.util.logging.Level.WARNING,
                            "Towny import failed part-way through", failure);
                    messages.send(replyTo(sender), MessageKey.MIGRATE_FAILED);
                    return;
                }
                messages.send(replyTo(sender), MessageKey.MIGRATE_DONE,
                        MessageService.value("summary", report.describe()));
                report.problems().forEach(problem ->
                        messages.sendRaw(replyTo(sender), MessageKey.MIGRATE_PROBLEM,
                                MessageService.value("problem", problem.describe())));
                if (!apply) {
                    messages.send(replyTo(sender), MessageKey.MIGRATE_DRY_RUN,
                            MessageService.value("command",
                                    "/rifttowny migrate towny " + CONFIRMATION));
                }
            });
        });
    }

    /**
     * {@code /rifttowny flag} — the two layers no town can reach.
     *
     * <p>{@code ADMIN} and {@code WORLD} have been in the resolution ladder since it was written and
     * are consulted on every protection check, but nothing could put a row in either: the service
     * methods were reachable from one test and from nothing else. A layer that always answers "no
     * opinion" because no surface can give it one is not a layer, so this is what makes the seven
     * real.</p>
     *
     * <p><strong>Why an operator command and not a role permission.</strong> {@code ADMIN} is tested
     * first and beats every town's own setting, which is precisely what it is for — an organisation
     * can never grant itself something an administrator has forbidden. There is no town to check
     * ownership against, so {@code FlagService} does no gating at all and this method is the whole
     * of it.</p>
     */
    private void flag(final CommandSender sender, final String[] args) {
        final MessageService messages = plugin().messages();
        final String verb = args.length < 2 ? "" : args[1].toLowerCase(Locale.ROOT);
        if (!List.of("set", "clear", "list").contains(verb)) {
            messages.send(sender, MessageKey.COMMAND_USAGE,
                    MessageService.value("usage", "/rifttowny flag <set|clear|list> …"));
            return;
        }

        // The scope is one or two words - 'server', or 'world <name>' - so everything after it sits
        // at a different index depending on which was typed.
        final java.util.Optional<Scope> scope = parseScope(sender, args, 2);
        if (scope.isEmpty()) {
            return;
        }
        final int rest = scope.get().nextArgument();

        switch (verb) {
            case "list" -> flagList(sender, scope.get());
            case "set" -> {
                if (args.length < rest + 3) {
                    messages.send(sender, MessageKey.COMMAND_USAGE, MessageService.value("usage",
                            "/rifttowny flag set " + scope.get().typed()
                                    + " <flag> <relationship> <allow|deny>"));
                    return;
                }
                flagSet(sender, scope.get(), args[rest], args[rest + 1], args[rest + 2]);
            }
            case "clear" -> {
                if (args.length < rest + 2) {
                    messages.send(sender, MessageKey.COMMAND_USAGE, MessageService.value("usage",
                            "/rifttowny flag clear " + scope.get().typed()
                                    + " <flag> <relationship>"));
                    return;
                }
                flagClear(sender, scope.get(), args[rest], args[rest + 1]);
            }
            default -> throw new IllegalStateException(verb);
        }
    }

    /**
     * Which layer, and where the arguments after it start.
     *
     * @param target what the override is stored against
     * @param typed the scope as the operator wrote it, for echoing back in a usage line
     * @param nextArgument the index of the first argument after the scope
     */
    private record Scope(
            net.riftbreaker.rifttowny.domain.flag.FlagTarget target,
            String typed,
            int nextArgument) {

        String label() {
            return target.source() == net.riftbreaker.rifttowny.domain.flag.FlagSource.ADMIN
                    ? "the whole server" : "the world " + typed.substring(WORLD.length() + 1);
        }
    }

    /**
     * Reads {@code server} or {@code world <name>}.
     *
     * <p>Two words rather than bare world names with {@code server} reserved among them, so a world
     * that happens to be called {@code server} is still addressable and nothing silently means
     * something other than what was typed.</p>
     */
    private java.util.Optional<Scope> parseScope(
            final CommandSender sender, final String[] args, final int at) {
        final MessageService messages = plugin().messages();
        final String word = args.length <= at ? "" : args[at].toLowerCase(Locale.ROOT);
        if (SERVER.equals(word)) {
            return java.util.Optional.of(new Scope(
                    net.riftbreaker.rifttowny.domain.flag.FlagTarget.admin(), SERVER, at + 1));
        }
        if (!WORLD.equals(word) || args.length <= at + 1) {
            messages.send(sender, MessageKey.COMMAND_USAGE,
                    MessageService.value("usage",
                            "/rifttowny flag " + args[1].toLowerCase(Locale.ROOT)
                                    + " <" + SERVER + '|' + WORLD + " <name>> …"));
            return java.util.Optional.empty();
        }
        final org.bukkit.World world = plugin().getServer().getWorld(args[at + 1]);
        if (world == null) {
            // By name rather than by the player's position: an operator setting a world's defaults
            // is usually not standing in it, and a world stores its overrides under its UUID, which
            // nobody can type.
            messages.send(sender, MessageKey.FLAG_UNKNOWN_WORLD,
                    MessageService.value("input", args[at + 1]));
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new Scope(
                net.riftbreaker.rifttowny.domain.flag.FlagTarget.world(world.getUID()),
                WORLD + ' ' + world.getName(), at + 2));
    }

    private void flagList(final CommandSender sender, final Scope scope) {
        final MessageService messages = plugin().messages();
        plugin().flags().of(scope.target()).whenComplete((found, failure) -> {
            if (failure != null) {
                plugin().getLogger().log(java.util.logging.Level.WARNING,
                        "Could not read the overrides for " + scope.target(), failure);
                messages.send(replyTo(sender), MessageKey.COMMAND_FAILED);
                return;
            }
            if (found.isEmpty()) {
                messages.send(replyTo(sender), MessageKey.FLAG_LIST_EMPTY,
                        MessageService.value("target", scope.label()));
                return;
            }
            messages.send(replyTo(sender), MessageKey.FLAG_LIST_HEADER,
                    MessageService.value("target", scope.label()));
            for (final var override : found) {
                messages.sendRaw(replyTo(sender), MessageKey.FLAG_LIST_LINE,
                        MessageService.value("flag", override.flag()),
                        MessageService.value("relationship", override.relationship()),
                        MessageService.value("state", override.allowed() ? "allowed" : "denied"));
            }
        });
    }

    private void flagSet(
            final CommandSender sender,
            final Scope scope,
            final String rawFlag,
            final String rawRelationship,
            final String rawDecision
    ) {
        final MessageService messages = plugin().messages();
        final var flag = parseFlag(sender, rawFlag);
        final var relationship = parseRelationship(sender, rawRelationship);
        final var allowed = parseDecision(rawDecision);
        if (flag.isEmpty() || relationship.isEmpty()) {
            return;
        }
        if (allowed.isEmpty()) {
            messages.send(sender, MessageKey.COMMAND_USAGE, MessageService.value("usage",
                    "/rifttowny flag set " + scope.typed() + ' ' + rawFlag + ' '
                            + rawRelationship + " <allow|deny>"));
            return;
        }
        plugin().flags()
                .setAdministrative(scope.target(), flag.get(), relationship.get(), allowed.get(),
                        residentOf(sender))
                .whenComplete((stored, failure) -> {
                    if (failure != null) {
                        plugin().getLogger().log(java.util.logging.Level.WARNING,
                                "Could not set an administrative flag", failure);
                        messages.send(replyTo(sender), MessageKey.COMMAND_FAILED);
                        return;
                    }
                    messages.send(replyTo(sender), MessageKey.FLAG_SET,
                            MessageService.value("flag", stored.flag()),
                            MessageService.value("relationship", stored.relationship()),
                            MessageService.value("state", stored.allowed() ? "allowed" : "denied"),
                            MessageService.value("scope", scope.label()));
                });
    }

    private void flagClear(
            final CommandSender sender,
            final Scope scope,
            final String rawFlag,
            final String rawRelationship
    ) {
        final MessageService messages = plugin().messages();
        final var flag = parseFlag(sender, rawFlag);
        final var relationship = parseRelationship(sender, rawRelationship);
        if (flag.isEmpty() || relationship.isEmpty()) {
            return;
        }
        plugin().flags().clearAdministrative(scope.target(), flag.get(), relationship.get())
                .whenComplete((removed, failure) -> {
                    if (failure != null) {
                        plugin().getLogger().log(java.util.logging.Level.WARNING,
                                "Could not clear an administrative flag", failure);
                        messages.send(replyTo(sender), MessageKey.COMMAND_FAILED);
                        return;
                    }
                    if (Boolean.TRUE.equals(removed)) {
                        messages.send(replyTo(sender), MessageKey.FLAG_CLEARED,
                                MessageService.value("flag", flag.get()),
                                MessageService.value("relationship", relationship.get()),
                                MessageService.value("scope", scope.label()));
                    } else {
                        // Distinct from a successful clear on purpose: an operator who typed the
                        // wrong relationship would otherwise be told the rule is gone when it stands.
                        messages.send(replyTo(sender), MessageKey.FLAG_NOTHING_TO_CLEAR,
                                MessageService.value("flag", flag.get()),
                                MessageService.value("relationship", relationship.get()),
                                MessageService.value("scope", scope.label()));
                    }
                });
    }

    private java.util.Optional<net.riftbreaker.rifttowny.domain.flag.ProtectionFlag> parseFlag(
            final CommandSender sender, final String raw) {
        final var parsed = net.riftbreaker.rifttowny.domain.flag.ProtectionFlag.parse(raw);
        if (parsed.isEmpty()) {
            plugin().messages().send(sender, MessageKey.FLAG_UNKNOWN,
                    MessageService.value("input", raw),
                    MessageService.value("options", lowerNames(
                            net.riftbreaker.rifttowny.domain.flag.ProtectionFlag.values())));
        }
        return parsed;
    }

    private java.util.Optional<net.riftbreaker.rifttowny.domain.flag.Relationship> parseRelationship(
            final CommandSender sender, final String raw) {
        final var parsed = net.riftbreaker.rifttowny.domain.flag.Relationship.parse(raw);
        if (parsed.isEmpty()) {
            plugin().messages().send(sender, MessageKey.FLAG_UNKNOWN_RELATIONSHIP,
                    MessageService.value("input", raw),
                    MessageService.value("options", lowerNames(
                            net.riftbreaker.rifttowny.domain.flag.Relationship.values())));
        }
        return parsed;
    }

    /**
     * The decision words.
     *
     * <p>The same set {@code /town flag} accepts, deliberately: an operator who has learnt one of
     * these two commands has learnt the other, and a word that works in one and not the other reads
     * as a bug in whichever was typed second.</p>
     */
    static java.util.Optional<Boolean> parseDecision(final String raw) {
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "allow", "allowed", "true", "on", "yes" -> java.util.Optional.of(true);
            case "deny", "denied", "false", "off", "no" -> java.util.Optional.of(false);
            default -> java.util.Optional.empty();
        };
    }

    static List<String> lowerNameList(final Enum<?>[] values) {
        final List<String> names = new ArrayList<>(values.length);
        for (final Enum<?> value : values) {
            names.add(value.name().toLowerCase(Locale.ROOT));
        }
        return List.copyOf(names);
    }

    static String lowerNames(final Enum<?>[] values) {
        return String.join(", ", lowerNameList(values));
    }

    /** Who is setting it, or null for the console — which the override record allows for. */
    private static net.riftbreaker.rifttowny.domain.org.ResidentId residentOf(
            final CommandSender sender) {
        return sender instanceof org.bukkit.entity.Player player
                ? net.riftbreaker.rifttowny.domain.org.ResidentId.of(player.getUniqueId())
                : null;
    }

    private void sendHelp(final CommandSender sender) {
        final MessageService messages = plugin().messages();
        messages.send(sender, MessageKey.COMMAND_HELP_HEADER,
                MessageService.value("command", "RiftTowny administration:"));
        messages.sendRaw(sender, MessageKey.COMMAND_HELP_LINE,
                MessageService.value("usage", "/rifttowny status"),
                MessageService.value("description",
                        "platform, storage, protection, territory, outbox and integrations"));
        messages.sendRaw(sender, MessageKey.COMMAND_HELP_LINE,
                MessageService.value("usage", "/rifttowny migrate towny"),
                MessageService.value("description",
                        "read a Towny database; a dry run unless you add '" + CONFIRMATION + '\''));
        messages.sendRaw(sender, MessageKey.COMMAND_HELP_LINE,
                MessageService.value("usage",
                        "/rifttowny flag <set|clear|list> <server|world <name>>"),
                MessageService.value("description",
                        "the two layers above every town's own settings"));
    }

    private void sendStatus(final CommandSender sender) {
        final MessageService messages = plugin().messages();

        messages.send(sender, MessageKey.STATUS_HEADER,
                MessageService.value("version", plugin().getPluginMeta().getVersion()));
        messages.sendRaw(sender, MessageKey.STATUS_PLATFORM,
                MessageService.value("platform", plugin().platformName()),
                MessageService.value("api", ApiVersion.CURRENT));
        messages.sendRaw(sender, MessageKey.STATUS_STORAGE,
                MessageService.value("backend", plugin().settings().storage().backend()),
                MessageService.value("schema", plugin().schema().currentVersion()),
                MessageService.value("topology", plugin().settings().describeTopology()));

        // Both counter sets already existed and nothing read them: the protection service has
        // counted every check since it was written, and the index every lookup. A counter nobody
        // can see is not a diagnostic, and these are the two an operator reaches for after "is
        // protection running at all" and "why has this got slow".
        final var guarded = plugin().protection().statistics();
        messages.sendRaw(sender, MessageKey.STATUS_PROTECTION,
                MessageService.value("checks", guarded.checks()),
                MessageService.value("refusals", guarded.refusals()),
                MessageService.value("bypasses", guarded.bypasses()));

        final var land = plugin().territoryIndex().statistics();
        messages.sendRaw(sender, MessageKey.STATUS_TERRITORY,
                MessageService.value("claims", land.claims()),
                MessageService.value("hits", land.hits()),
                MessageService.value("misses", land.misses()),
                MessageService.value("generation", land.generation()));

        // The outbox depth is a database read, so it is fetched asynchronously and printed when it
        // arrives. A status command that blocked the server thread to render a diagnostic would be
        // its own outage.
        plugin().outbox().counts().whenComplete((counts, failure) -> {
            if (failure != null) {
                messages.sendRaw(replyTo(sender), MessageKey.STATUS_OUTBOX_UNAVAILABLE,
                        MessageService.value("reason", failure.getMessage()));
            } else {
                messages.sendRaw(replyTo(sender), MessageKey.STATUS_OUTBOX,
                        MessageService.value("pending", counts.pending()),
                        MessageService.value("claimed", counts.claimed()),
                        MessageService.value("failed", counts.failed()));
            }
        });

        // The last tax run, for the same reason as the outbox depth: it is a question an operator
        // asks after something looked wrong, and an unfinished run is exactly what they are looking
        // for. Nothing read this table at all until now.
        plugin().civicStore().inTransaction(transaction -> transaction.taxes().lastRun())
                .whenComplete((run, failure) -> {
                    if (failure != null || run == null || run.isEmpty()) {
                        return;
                    }
                    final var last = run.get();
                    messages.sendRaw(replyTo(sender),
                            last.finished()
                                    ? MessageKey.STATUS_TAX_RUN
                                    : MessageKey.STATUS_TAX_RUN_UNFINISHED,
                            MessageService.value("period", last.periodKey()),
                            MessageService.value("towns", last.townsCharged()),
                            MessageService.value("residents", last.residentsCharged()),
                            MessageService.value("fallen", last.townsFallen()),
                            MessageService.value("server", last.serverId()));
                });

        messages.sendRaw(sender, MessageKey.STATUS_INTEGRATIONS_HEADER);
        for (final CapabilityStatus status : plugin().capabilities().statuses()) {
            final MessageKey key = switch (status.state()) {
                case ACTIVE -> MessageKey.STATUS_INTEGRATION_ACTIVE;
                case FAILED, BLOCKED, PRESENT_UNVERIFIED -> MessageKey.STATUS_INTEGRATION_PROBLEM;
                case ABSENT, DISABLED -> MessageKey.STATUS_INTEGRATION_ABSENT;
            };
            messages.sendRaw(sender, key,
                    MessageService.value("capability", status.capability().name()),
                    MessageService.value("state", status.state()),
                    MessageService.value("detail", detailOf(status)));
        }
    }

    private static String detailOf(final CapabilityStatus status) {
        return status.state() == CapabilityState.ABSENT ? "not installed" : status.detail();
    }

    private boolean hasPermission(final CommandSender sender, final String permission) {
        if (sender.hasPermission(permission)) {
            return true;
        }
        // Optional alias for servers migrating an existing permission set. Off by default, because
        // silently honouring another plugin's permission nodes would be a surprise.
        return plugin().settings().townyPermissionAliases()
                && sender.hasPermission(permission.replaceFirst("^rifttowny\\.", "towny."));
    }
}
