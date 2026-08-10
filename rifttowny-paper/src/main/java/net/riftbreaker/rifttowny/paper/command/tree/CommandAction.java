package net.riftbreaker.rifttowny.paper.command.tree;

import java.util.List;

/**
 * What a command node does.
 *
 * <p>Returns nothing. Every service call underneath is asynchronous, so an action queues work and
 * lets the reply arrive when the future completes; a return value would only encourage somebody to
 * block a server thread waiting for it.</p>
 */
@FunctionalInterface
public interface CommandAction {

    /**
     * @param arguments what was typed after the node itself, never null and never containing the
     *        node's own name
     */
    void run(CommandActor actor, List<String> arguments);
}
