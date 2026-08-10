package net.riftbreaker.rifttowny.paper.command.tree;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * One node of a command tree: either a group that only holds children, or an action that runs.
 *
 * <p>Immutable, and built once at enable. Keeping the tree as data rather than a chain of
 * {@code if (args[0].equals(...))} is what makes tab completion fall out for free instead of being
 * written a second time and drifting from the dispatch it is supposed to describe.</p>
 */
public final class CommandNode {

    private final String name;
    private final List<String> aliases;
    private final String permission;
    private final String description;
    private final String usage;
    private final List<CommandNode> children;
    private final CommandAction action;
    private final Set<Surface> surfaces;
    private final ArgumentCompleter completer;

    private CommandNode(final Builder builder) {
        this.name = builder.name;
        this.aliases = List.copyOf(builder.aliases);
        this.permission = builder.permission;
        this.description = builder.description;
        this.usage = builder.usage;
        this.children = List.copyOf(builder.children);
        this.action = builder.action;
        this.surfaces = builder.surfaces.isEmpty()
                ? Set.of()
                : java.util.Collections.unmodifiableSet(EnumSet.copyOf(builder.surfaces));
        this.completer = builder.completer;
    }

    /** A node that only holds children. Running it prints its children as help. */
    public static Builder group(final String name) {
        return new Builder(name);
    }

    /** A node that does something. */
    public static Builder action(final String name) {
        return new Builder(name);
    }

    public String name() {
        return name;
    }

    public List<String> aliases() {
        return aliases;
    }

    public Optional<String> permission() {
        return Optional.ofNullable(permission);
    }

    public String description() {
        return description == null ? "" : description;
    }

    /** How to type it, without the leading slash. */
    public String usage() {
        return usage == null ? name : usage;
    }

    public List<CommandNode> children() {
        return children;
    }

    public Optional<CommandAction> action() {
        return Optional.ofNullable(action);
    }

    public boolean isAction() {
        return action != null;
    }

    /** Which surfaces this action is reachable through. Empty for a group. */
    public Set<Surface> surfaces() {
        return surfaces;
    }

    public Optional<ArgumentCompleter> completer() {
        return Optional.ofNullable(completer);
    }

    /** Whether this node answers to a typed word, matching its name or any alias. */
    public boolean matches(final String token) {
        if (token == null) {
            return false;
        }
        final String lowered = token.toLowerCase(Locale.ROOT);
        if (name.equals(lowered)) {
            return true;
        }
        return aliases.contains(lowered);
    }

    /** Whether the actor may see or run this node. A node with no permission is open to all. */
    public boolean visibleTo(final CommandActor actor) {
        return permission == null || actor.hasPermission(permission);
    }

    public Optional<CommandNode> child(final String token) {
        return children.stream().filter(child -> child.matches(token)).findFirst();
    }

    @Override
    public String toString() {
        return "CommandNode[" + name + (isAction() ? "" : ", " + children.size() + " child(ren)") + ']';
    }

    /** Assembles a node. Names and aliases are lower-cased so matching never has to think about it. */
    public static final class Builder {

        private final String name;
        private final List<String> aliases = new ArrayList<>();
        private final List<CommandNode> children = new ArrayList<>();
        private final Set<Surface> surfaces = EnumSet.noneOf(Surface.class);
        private String permission;
        private String description;
        private String usage;
        private CommandAction action;
        private ArgumentCompleter completer;

        private Builder(final String name) {
            this.name = Objects.requireNonNull(name, "name").toLowerCase(Locale.ROOT);
        }

        public Builder aliases(final String... values) {
            for (final String value : values) {
                aliases.add(value.toLowerCase(Locale.ROOT));
            }
            return this;
        }

        public Builder permission(final String value) {
            this.permission = value;
            return this;
        }

        public Builder describedAs(final String value) {
            this.description = value;
            return this;
        }

        public Builder usage(final String value) {
            this.usage = value;
            return this;
        }

        public Builder child(final CommandNode value) {
            children.add(Objects.requireNonNull(value, "child"));
            return this;
        }

        public Builder completer(final ArgumentCompleter value) {
            this.completer = value;
            return this;
        }

        /**
         * Marks the node as doing something, and declares which surfaces it is reachable through.
         *
         * <p>{@link Surface#COMMAND} is added automatically: a node in a command tree is reachable
         * by command by definition, and making callers repeat it invites one of them to forget and
         * fail the parity check for a reason that is not a real gap.</p>
         */
        public CommandNode runs(final CommandAction value, final Surface... alsoAvailableOn) {
            this.action = Objects.requireNonNull(value, "action");
            surfaces.add(Surface.COMMAND);
            surfaces.addAll(List.of(alsoAvailableOn));
            return new CommandNode(this);
        }

        /** Finishes a group, which has no action of its own. */
        public CommandNode build() {
            return new CommandNode(this);
        }
    }
}
