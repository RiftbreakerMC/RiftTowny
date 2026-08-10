package net.riftbreaker.rifttowny.paper.command.tree;

import java.util.List;

/**
 * Suggestions for an action's own arguments, beyond its child nodes.
 *
 * <p>Town names, resident names, role names. Implementations must not query storage: tab completion
 * runs on the server thread while the player is still typing, and a database round trip there is
 * felt as lag on every keystroke. Suggestions come from the same snapshot caches the placeholders
 * use.</p>
 */
@FunctionalInterface
public interface ArgumentCompleter {

    /**
     * @param arguments what has been typed after the node, with the final element being the partial
     *        word the player is completing — possibly empty
     */
    List<String> suggest(CommandActor actor, List<String> arguments);
}
