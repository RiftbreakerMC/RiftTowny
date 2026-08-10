package net.riftbreaker.rifttowny.paper.command.tree;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Walks a command tree: which node did they mean, and what should tab suggest.
 *
 * <p>Both answers come from the same structure, which is the point. Written separately, dispatch and
 * completion drift — the classic symptom being a subcommand that works but never appears in tab, or
 * one that tab offers and then reports as unknown.</p>
 *
 * <p>Pure: no Bukkit, no storage, no clock. Everything here is exercised in tests.</p>
 */
public final class CommandRouter {

    private CommandRouter() {
    }

    /**
     * The deepest node the arguments name, and whatever is left over.
     *
     * <p>Stops descending at the first token that is not a child, so {@code /town rename Ashford}
     * resolves to the {@code rename} node with {@code [Ashford]} left for it, and
     * {@code /town nonsense} resolves to {@code town} with {@code [nonsense]} left — which is what
     * lets the caller report an unknown subcommand rather than silently running the group.</p>
     */
    public static Resolution resolve(final CommandNode root, final List<String> arguments) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(arguments, "arguments");

        CommandNode current = root;
        final List<String> path = new ArrayList<>();
        path.add(root.name());

        int index = 0;
        while (index < arguments.size()) {
            final Optional<CommandNode> child = current.child(arguments.get(index));
            if (child.isEmpty()) {
                break;
            }
            current = child.get();
            path.add(current.name());
            index++;
        }

        return new Resolution(current, List.copyOf(arguments.subList(index, arguments.size())),
                List.copyOf(path));
    }

    /**
     * What tab should offer.
     *
     * <p>The last argument is the partial word being typed; everything before it has already been
     * settled. Children the actor cannot use are omitted rather than shown and then refused —
     * offering a subcommand that always answers "no permission" is worse than not offering it,
     * because it advertises that the feature exists to someone who cannot reach it.</p>
     */
    public static List<String> complete(
            final CommandNode root, final List<String> arguments, final CommandActor actor) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(arguments, "arguments");
        Objects.requireNonNull(actor, "actor");

        if (arguments.isEmpty()) {
            return childNames(root, actor, "");
        }

        final List<String> settled = arguments.subList(0, arguments.size() - 1);
        final String partial = arguments.get(arguments.size() - 1).toLowerCase(Locale.ROOT);
        final Resolution resolution = resolve(root, settled);

        // A settled argument that names no child means the player is deep inside an action's own
        // arguments; the tree has nothing more to say, so only the action's completer contributes.
        final List<String> suggestions = new ArrayList<>(
                resolution.arguments().isEmpty() ? childNames(resolution.node(), actor, partial) : List.of());

        resolution.node().completer().ifPresent(completer -> {
            final List<String> forCompleter = new ArrayList<>(resolution.arguments());
            forCompleter.add(partial);
            for (final String suggestion : completer.suggest(actor, List.copyOf(forCompleter))) {
                if (suggestion != null && suggestion.toLowerCase(Locale.ROOT).startsWith(partial)) {
                    suggestions.add(suggestion);
                }
            }
        });

        return List.copyOf(suggestions);
    }

    private static List<String> childNames(
            final CommandNode node, final CommandActor actor, final String partial) {
        final List<String> names = new ArrayList<>();
        for (final CommandNode child : node.children()) {
            if (child.visibleTo(actor) && child.name().startsWith(partial)) {
                names.add(child.name());
            }
        }
        return names;
    }

    /**
     * Where the arguments landed.
     *
     * @param node the deepest node named
     * @param arguments what was left after it
     * @param path the node names walked through, for building a usage line the player recognises
     */
    public record Resolution(CommandNode node, List<String> arguments, List<String> path) {

        public Resolution {
            Objects.requireNonNull(node, "node");
            arguments = List.copyOf(Objects.requireNonNull(arguments, "arguments"));
            path = List.copyOf(Objects.requireNonNull(path, "path"));
        }

        /** How the player would type what they reached, ready to put in a message. */
        public String label() {
            return '/' + String.join(" ", path);
        }

        /** Whether the arguments named something the tree does not have. */
        public boolean hitUnknownSubcommand() {
            return !node().isAction() && !arguments().isEmpty();
        }
    }
}
