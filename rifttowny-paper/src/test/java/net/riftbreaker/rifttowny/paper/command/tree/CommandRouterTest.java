package net.riftbreaker.rifttowny.paper.command.tree;

import net.kyori.adventure.text.Component;
import net.riftbreaker.rifttowny.domain.org.ResidentId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CommandRouterTest {

    private final List<String> ran = new ArrayList<>();

    /** A tree shaped like the real one: a group, actions, an alias, a permission, a completer. */
    private CommandNode tree() {
        return CommandNode.group("town")
                .child(CommandNode.action("new")
                        .aliases("create")
                        .permission("rifttowny.town.new")
                        .usage("town new <name>")
                        .describedAs("Found a town")
                        .runs((actor, args) -> ran.add("new " + args), Surface.CHAT))
                .child(CommandNode.action("leave")
                        .runs((actor, args) -> ran.add("leave"), Surface.CHAT, Surface.GUI))
                .child(CommandNode.action("rename")
                        .permission("rifttowny.town.rename")
                        .completer((actor, args) -> List.of("Riftholm", "Ashford"))
                        .runs((actor, args) -> ran.add("rename " + args), Surface.GUI))
                .child(CommandNode.group("role")
                        .permission("rifttowny.role.manage")
                        // Children repeat the parent's permission. A gated group with an open child
                        // is refused at construction, because the router only tests the node the
                        // arguments resolved to.
                        .child(CommandNode.action("add")
                                .permission("rifttowny.role.manage")
                                .runs((actor, args) -> ran.add("role add " + args), Surface.GUI))
                        .child(CommandNode.action("remove")
                                .permission("rifttowny.role.manage")
                                .runs((actor, args) -> ran.add("role remove"), Surface.GUI))
                        .build())
                .build();
    }

    private static CommandActor actorWith(final String... permissions) {
        final Set<String> held = Set.of(permissions);
        return new CommandActor() {
            @Override
            public String name() {
                return "Tester";
            }

            @Override
            public Optional<ResidentId> resident() {
                return Optional.of(ResidentId.of(UUID.randomUUID()));
            }

            @Override
            public boolean hasPermission(final String permission) {
                return held.contains(permission);
            }

            @Override
            public void send(final Component message) {
                // Assertions here are about routing, not rendering.
            }
        };
    }

    @Nested
    @DisplayName("resolving")
    class Resolving {

        @Test
        @DisplayName("no arguments resolve to the root")
        void emptyResolvesToRoot() {
            final var resolution = CommandRouter.resolve(tree(), List.of());

            assertThat(resolution.node().name()).isEqualTo("town");
            assertThat(resolution.arguments()).isEmpty();
            assertThat(resolution.label()).isEqualTo("/town");
        }

        @Test
        @DisplayName("a subcommand resolves, with its own arguments left over")
        void subcommandKeepsItsArguments() {
            final var resolution = CommandRouter.resolve(tree(), List.of("new", "Riftholm"));

            assertThat(resolution.node().name()).isEqualTo("new");
            assertThat(resolution.arguments()).containsExactly("Riftholm");
            assertThat(resolution.label()).isEqualTo("/town new");
        }

        @Test
        @DisplayName("an alias resolves to the same node")
        void aliasesResolve() {
            assertThat(CommandRouter.resolve(tree(), List.of("create", "Riftholm")).node().name())
                    .isEqualTo("new");
        }

        @Test
        @DisplayName("matching ignores case, because players do not")
        void matchingIsCaseInsensitive() {
            assertThat(CommandRouter.resolve(tree(), List.of("NEW")).node().name()).isEqualTo("new");
        }

        @Test
        @DisplayName("a nested group resolves to the deepest action")
        void nestedActionsResolve() {
            final var resolution = CommandRouter.resolve(tree(), List.of("role", "add", "Alder"));

            assertThat(resolution.node().name()).isEqualTo("add");
            assertThat(resolution.arguments()).containsExactly("Alder");
            assertThat(resolution.label()).isEqualTo("/town role add");
        }

        @Test
        @DisplayName("an unknown word stops the walk and is reported, not swallowed")
        void unknownSubcommandIsVisible() {
            final var resolution = CommandRouter.resolve(tree(), List.of("nonsense"));

            assertThat(resolution.node().name()).isEqualTo("town");
            assertThat(resolution.arguments()).containsExactly("nonsense");
            assertThat(resolution.hitUnknownSubcommand())
                    .as("otherwise a typo silently runs the group's help and looks like it worked")
                    .isTrue();
        }

        @Test
        @DisplayName("an action's own argument is not mistaken for an unknown subcommand")
        void actionArgumentsAreNotUnknownSubcommands() {
            assertThat(CommandRouter.resolve(tree(), List.of("new", "Riftholm"))
                    .hitUnknownSubcommand()).isFalse();
        }

        @Test
        @DisplayName("a group reached with no arguments is not an unknown subcommand either")
        void bareGroupIsNotUnknown() {
            assertThat(CommandRouter.resolve(tree(), List.of("role")).hitUnknownSubcommand())
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("completing")
    class Completing {

        @Test
        @DisplayName("an empty argument list offers every permitted child")
        void emptyOffersChildren() {
            final var actor = actorWith("rifttowny.town.new", "rifttowny.town.rename",
                    "rifttowny.role.manage");

            assertThat(CommandRouter.complete(tree(), List.of(), actor))
                    .containsExactly("new", "leave", "rename", "role");
        }

        @Test
        @DisplayName("a partial word filters by prefix")
        void partialFiltersByPrefix() {
            final var actor = actorWith("rifttowny.town.rename", "rifttowny.role.manage");

            assertThat(CommandRouter.complete(tree(), List.of("r"), actor))
                    .containsExactly("rename", "role");
        }

        @Test
        @DisplayName("children the actor cannot use are not advertised")
        void permissionFiltersSuggestions() {
            final var actor = actorWith();

            assertThat(CommandRouter.complete(tree(), List.of(""), actor))
                    .as("offering a subcommand that always refuses advertises a feature they cannot reach")
                    .containsExactly("leave");
        }

        @Test
        @DisplayName("a group offers its own children once it is settled")
        void groupOffersItsChildren() {
            final var actor = actorWith("rifttowny.role.manage");

            assertThat(CommandRouter.complete(tree(), List.of("role", ""), actor))
                    .containsExactly("add", "remove");
        }

        @Test
        @DisplayName("an action's completer supplies its own arguments")
        void completerSuppliesArguments() {
            final var actor = actorWith("rifttowny.town.rename");

            assertThat(CommandRouter.complete(tree(), List.of("rename", ""), actor))
                    .containsExactly("Riftholm", "Ashford");
        }

        @Test
        @DisplayName("a completer's suggestions are filtered by the partial word too")
        void completerSuggestionsAreFiltered() {
            final var actor = actorWith("rifttowny.town.rename");

            assertThat(CommandRouter.complete(tree(), List.of("rename", "ri"), actor))
                    .containsExactly("Riftholm");
        }

        @Test
        @DisplayName("a node with no completer and no children offers nothing")
        void nothingToOffer() {
            final var actor = actorWith("rifttowny.town.new");

            assertThat(CommandRouter.complete(tree(), List.of("new", "Riftholm", ""), actor))
                    .isEmpty();
        }

        @Test
        @DisplayName("an unknown settled word stops the tree contributing suggestions")
        void unknownSettledWordOffersNoChildren() {
            final var actor = actorWith("rifttowny.town.new");

            assertThat(CommandRouter.complete(tree(), List.of("nonsense", ""), actor)).isEmpty();
        }
    }

    @Nested
    @DisplayName("permission narrowing")
    class Narrowing {

        @Test
        @DisplayName("a child with no permission under a gated parent cannot be built")
        void childMayNotBeBroaderThanItsParent() {
            final CommandNode.Builder group = CommandNode.group("role")
                    .permission("rifttowny.role.view")
                    .child(CommandNode.action("list").runs((actor, args) -> { }));

            org.assertj.core.api.Assertions.assertThatThrownBy(group::build)
                    .as("the router tests only the resolved node, so an open child walks straight "
                            + "past the gate its parent carries")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("bypass");
        }

        @Test
        @DisplayName("a child with its own permission is fine, narrower or not")
        void gatedChildIsAccepted() {
            assertThat(CommandNode.group("role")
                    .permission("rifttowny.role.view")
                    .child(CommandNode.action("list")
                            .permission("rifttowny.role.view")
                            .runs((actor, args) -> { }))
                    .build()
                    .children()).hasSize(1);
        }

        @Test
        @DisplayName("an open parent may hold gated children, which is the useful direction")
        void openParentMayHoldGatedChildren() {
            assertThat(CommandNode.group("town")
                    .child(CommandNode.action("delete")
                            .permission("rifttowny.town.delete")
                            .runs((actor, args) -> { }))
                    .build()
                    .children()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("surfaces")
    class Surfaces {

        @Test
        @DisplayName("every action is reachable by command without having to say so")
        void commandIsImplicit() {
            final CommandNode leave = CommandRouter.resolve(tree(), List.of("leave")).node();

            assertThat(leave.surfaces()).contains(Surface.COMMAND);
        }

        @Test
        @DisplayName("no action is GUI-only")
        void noActionIsGuiOnly() {
            final List<String> guiOnly = new ArrayList<>();
            collectGuiOnly(tree(), "", guiOnly);

            assertThat(guiOnly)
                    .as("a GUI-only action locks out scripting, screen readers, and Bedrock "
                            + "until its form exists")
                    .isEmpty();
        }

        private void collectGuiOnly(
                final CommandNode node, final String path, final List<String> found) {
            final String here = path.isEmpty() ? node.name() : path + ' ' + node.name();
            if (node.isAction()
                    && node.surfaces().contains(Surface.GUI)
                    && !node.surfaces().contains(Surface.COMMAND)) {
                found.add(here);
            }
            for (final CommandNode child : node.children()) {
                collectGuiOnly(child, here, found);
            }
        }
    }
}
