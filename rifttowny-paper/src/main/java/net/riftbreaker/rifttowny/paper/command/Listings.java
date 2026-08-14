package net.riftbreaker.rifttowny.paper.command;

import net.riftbreaker.rifttowny.domain.directory.CivicSort;
import net.riftbreaker.rifttowny.domain.directory.Page;
import net.riftbreaker.rifttowny.paper.command.tree.CommandActor;
import net.riftbreaker.rifttowny.paper.message.MessageKey;
import net.riftbreaker.rifttowny.paper.message.MessageService;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The parts every listing shares: how a page and an order are typed, and how the next page is
 * offered.
 *
 * <p>Written once because a player who has learnt {@code /town list 2 land} will type
 * {@code /nation list 2 land} without thinking, and two implementations of the same syntax is how
 * one of them ends up accepting an order the other rejects.</p>
 */
final class Listings {

    /**
     * Rows per page.
     *
     * <p>Ten plus a header and a footer is twelve lines, which leaves a player's chat history
     * intact. A page that fills the window scrolls away whatever they were reading, and they will
     * run the command again to find it — which scrolls it away again.</p>
     */
    static final int PAGE_SIZE = 10;

    private final MessageService messages;

    Listings(final MessageService messages) {
        this.messages = Objects.requireNonNull(messages, "messages");
    }

    /**
     * Reads {@code [page] [order]} in either order.
     *
     * <p>Position-free on purpose. {@code /town list land 2} and {@code /town list 2 land} are the
     * same request, and a player who guesses wrong should get their list rather than a lecture
     * about argument order. A number is a page; a word that names an order is the order; anything
     * else is a typo worth reporting, because silently ignoring it would show them a list sorted by
     * something they did not ask for and let them believe it was.</p>
     *
     * @return empty when an argument could not be read, having already told the actor why
     */
    Optional<Request> parse(final CommandActor actor, final List<String> args) {
        int page = 1;
        CivicSort sort = CivicSort.NAME;
        for (final String argument : args) {
            final Optional<Integer> number = number(argument);
            if (number.isPresent()) {
                page = number.get();
                continue;
            }
            final Optional<CivicSort> parsed = CivicSort.parse(argument);
            if (parsed.isEmpty()) {
                messages.send(actor::send, MessageKey.LISTING_UNKNOWN_SORT,
                        MessageService.value("input", argument),
                        MessageService.value("options", CivicSort.options()));
                return Optional.empty();
            }
            sort = parsed.get();
        }
        return Optional.of(new Request(page, sort));
    }

    /** Offers the next page, and says nothing when there is not one. */
    void more(final CommandActor actor, final Page<?> page, final String command) {
        if (page.hasNext()) {
            messages.sendRaw(actor::send, MessageKey.LISTING_MORE,
                    MessageService.value("command", command));
        }
    }

    private static Optional<Integer> number(final String raw) {
        try {
            return Optional.of(Integer.parseInt(raw.trim()));
        } catch (final NumberFormatException notANumber) {
            return Optional.empty();
        }
    }

    /** What the player asked for. */
    record Request(int page, CivicSort sort) {

        /** The order's name as it is typed, for building the next-page command. */
        String sortName() {
            return sort.name().toLowerCase(java.util.Locale.ROOT);
        }
    }
}
