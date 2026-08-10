package net.riftbreaker.rifttowny.paper.command;

import net.riftbreaker.rifttowny.paper.RiftTownyPlugin;
import net.riftbreaker.rifttowny.paper.command.tree.CommandActor;
import net.riftbreaker.rifttowny.paper.command.tree.CommandNode;
import net.riftbreaker.rifttowny.paper.command.tree.CommandRouter;
import net.riftbreaker.rifttowny.paper.message.MessageKey;
import net.riftbreaker.rifttowny.paper.message.MessageService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;

/**
 * Runs a {@link CommandNode} tree as a Bukkit command.
 *
 * <p>The whole Bukkit-facing surface of the command system is this one class, so the routing rules
 * stay testable and there is exactly one place that turns a resolution into a reply.</p>
 */
public final class TreeCommandExecutor implements CommandExecutor, TabCompleter {

    private final CommandNode root;

    public TreeCommandExecutor(final CommandNode root) {
        this.root = Objects.requireNonNull(root, "root");
    }

    @Override
    public boolean onCommand(
            @NotNull final CommandSender sender,
            @NotNull final Command command,
            @NotNull final String label,
            final String @NotNull [] args
    ) {
        final CommandActor actor = actor(sender);
        final MessageService messages = RiftTownyPlugin.getInstance().messages();
        final CommandRouter.Resolution resolution = CommandRouter.resolve(root, List.of(args));

        // Checked on the resolved node only. A parent's permission is not re-tested, because a tree
        // is built so a child's permission is at least as narrow as its parent's; testing both would
        // make an admin-only child under an open parent impossible to express.
        if (!resolution.node().visibleTo(actor)) {
            messages.send(actor::send, MessageKey.COMMAND_NO_PERMISSION);
            return true;
        }

        if (resolution.hitUnknownSubcommand()) {
            messages.send(actor::send, MessageKey.COMMAND_UNKNOWN_SUBCOMMAND,
                    MessageService.value("input", resolution.arguments().getFirst()));
            sendHelp(actor, messages, resolution);
            return true;
        }

        if (resolution.node().isAction()) {
            resolution.node().action().orElseThrow().run(actor, resolution.arguments());
        } else {
            sendHelp(actor, messages, resolution);
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
        return CommandRouter.complete(root, List.of(args), actor(sender));
    }

    private static CommandActor actor(final CommandSender sender) {
        return new BukkitCommandActor(sender, RiftTownyPlugin.getInstance().scheduler());
    }

    /** Lists the children the actor may actually use, with usage and description. */
    private static void sendHelp(
            final CommandActor actor,
            final MessageService messages,
            final CommandRouter.Resolution resolution
    ) {
        messages.send(actor::send, MessageKey.COMMAND_HELP_HEADER);
        for (final CommandNode child : resolution.node().children()) {
            if (!child.visibleTo(actor)) {
                continue;
            }
            messages.sendRaw(actor::send, MessageKey.COMMAND_HELP_LINE,
                    MessageService.value("usage", '/' + child.usage()),
                    MessageService.value("description", child.description()));
        }
    }
}
