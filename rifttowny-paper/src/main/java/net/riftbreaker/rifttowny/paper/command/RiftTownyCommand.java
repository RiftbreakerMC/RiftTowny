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
        final List<String> candidates = switch (args.length) {
            case 1 -> List.of("status", "migrate", "help");
            case 2 -> "migrate".equalsIgnoreCase(args[0]) ? List.of("towny") : List.of();
            // Deliberately not offering the confirmation word. Tab-completing your way into an
            // irreversible bulk import is exactly what the word is there to prevent.
            default -> List.of();
        };
        if (candidates.isEmpty()) {
            return List.of();
        }
        final String partial = args[args.length - 1].toLowerCase(Locale.ROOT);
        final List<String> options = new ArrayList<>();
        for (final String candidate : candidates) {
            if (candidate.startsWith(partial)) {
                options.add(candidate);
            }
        }
        return options;
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
                messages.send(sender, MessageKey.MIGRATE_UNREADABLE,
                        MessageService.value("reason", unreadable.getMessage()));
                return;
            }

            messages.send(sender, MessageKey.MIGRATE_READ,
                    MessageService.value("summary", plan.describe()));
            source.notes().forEach(note -> messages.sendRaw(sender, MessageKey.MIGRATE_PROBLEM,
                    MessageService.value("problem", note)));

            final var pending = apply
                    ? plugin().importer().apply(plan)
                    : plugin().importer().preview(plan);

            pending.whenComplete((report, failure) -> {
                if (failure != null) {
                    plugin().getLogger().log(java.util.logging.Level.WARNING,
                            "Towny import failed part-way through", failure);
                    messages.send(sender, MessageKey.MIGRATE_FAILED);
                    return;
                }
                messages.send(sender, MessageKey.MIGRATE_DONE,
                        MessageService.value("summary", report.describe()));
                report.problems().forEach(problem ->
                        messages.sendRaw(sender, MessageKey.MIGRATE_PROBLEM,
                                MessageService.value("problem", problem.describe())));
                if (!apply) {
                    messages.send(sender, MessageKey.MIGRATE_DRY_RUN,
                            MessageService.value("command",
                                    "/rifttowny migrate towny " + CONFIRMATION));
                }
            });
        });
    }

    private void sendHelp(final CommandSender sender) {
        final MessageService messages = plugin().messages();
        messages.send(sender, MessageKey.COMMAND_HELP_HEADER,
                MessageService.value("command", "RiftTowny administration:"));
        messages.sendRaw(sender, MessageKey.COMMAND_HELP_LINE,
                MessageService.value("usage", "/rifttowny status"),
                MessageService.value("description", "platform, storage, outbox and integration state"));
        messages.sendRaw(sender, MessageKey.COMMAND_HELP_LINE,
                MessageService.value("usage", "/rifttowny migrate towny"),
                MessageService.value("description",
                        "read a Towny database; a dry run unless you add '" + CONFIRMATION + '\''));
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

        // The outbox depth is a database read, so it is fetched asynchronously and printed when it
        // arrives. A status command that blocked the server thread to render a diagnostic would be
        // its own outage.
        plugin().outbox().counts().whenComplete((counts, failure) -> {
            if (failure != null) {
                messages.sendRaw(sender, MessageKey.STATUS_OUTBOX_UNAVAILABLE,
                        MessageService.value("reason", failure.getMessage()));
            } else {
                messages.sendRaw(sender, MessageKey.STATUS_OUTBOX,
                        MessageService.value("pending", counts.pending()),
                        MessageService.value("claimed", counts.claimed()),
                        MessageService.value("failed", counts.failed()));
            }
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
