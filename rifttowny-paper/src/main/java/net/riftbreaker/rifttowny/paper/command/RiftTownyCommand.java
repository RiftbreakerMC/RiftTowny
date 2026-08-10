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
        if (args.length != 1) {
            return List.of();
        }
        final String partial = args[0].toLowerCase(Locale.ROOT);
        final List<String> options = new ArrayList<>();
        for (final String candidate : List.of("status", "help")) {
            if (candidate.startsWith(partial)) {
                options.add(candidate);
            }
        }
        return options;
    }

    private void sendHelp(final CommandSender sender) {
        final MessageService messages = plugin().messages();
        messages.send(sender, MessageKey.COMMAND_HELP_HEADER,
                MessageService.value("command", "RiftTowny administration:"));
        messages.sendRaw(sender, MessageKey.COMMAND_HELP_LINE,
                MessageService.value("usage", "/rifttowny status"),
                MessageService.value("description", "platform, storage, outbox and integration state"));
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
