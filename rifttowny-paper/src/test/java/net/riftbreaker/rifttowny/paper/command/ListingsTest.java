package net.riftbreaker.rifttowny.paper.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.riftbreaker.rifttowny.domain.directory.CivicSort;
import net.riftbreaker.rifttowny.domain.directory.Page;
import net.riftbreaker.rifttowny.domain.org.ResidentId;
import net.riftbreaker.rifttowny.paper.command.tree.CommandActor;
import net.riftbreaker.rifttowny.paper.message.MessageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How a player types a page and an order.
 *
 * <p>Worth testing because the syntax is forgiving on purpose, and forgiving parsing is where a
 * command quietly does something other than what was asked — showing a list sorted by name to
 * somebody who asked for it sorted by size, and giving them no sign of it.</p>
 */
class ListingsTest {

    private final List<String> sent = new ArrayList<>();
    private Listings listings;
    private CommandActor actor;

    @BeforeEach
    void setUp() {
        final MessageService messages = new MessageService(null, problem -> {
        });
        listings = new Listings(messages);
        actor = actor();
    }

    @Test
    @DisplayName("no arguments means page one, alphabetically")
    void defaultsAreTheObviousOnes() {
        final Listings.Request request = listings.parse(actor, List.of()).orElseThrow();

        assertThat(request.page()).isEqualTo(1);
        assertThat(request.sort()).isEqualTo(CivicSort.NAME);
    }

    @Test
    @DisplayName("the page and the order can be typed in either order")
    void argumentOrderDoesNotMatter() {
        final Listings.Request first = listings.parse(actor, List.of("2", "land")).orElseThrow();
        final Listings.Request second = listings.parse(actor, List.of("land", "2")).orElseThrow();

        assertThat(first).isEqualTo(second);
        assertThat(first.page()).isEqualTo(2);
        assertThat(first.sort()).isEqualTo(CivicSort.LAND);
    }

    @Test
    @DisplayName("an order can be named several ways, because players will")
    void ordersHaveSynonyms() {
        assertThat(listings.parse(actor, List.of("population")).orElseThrow().sort())
                .isEqualTo(CivicSort.RESIDENTS);
        assertThat(listings.parse(actor, List.of("chunks")).orElseThrow().sort())
                .isEqualTo(CivicSort.LAND);
        assertThat(listings.parse(actor, List.of("OLDEST")).orElseThrow().sort())
                .isEqualTo(CivicSort.AGE);
    }

    @Test
    @DisplayName("a word that names no order is reported rather than ignored")
    void nonsenseIsReported() {
        // Ignoring it would show them a list ordered by something they did not ask for, and let them
        // believe it was what they asked for.
        assertThat(listings.parse(actor, List.of("sideways"))).isEmpty();
        assertThat(sent).anyMatch(line -> line.contains("sideways"));
    }

    @Test
    @DisplayName("the next page is offered only when there is one")
    void moreIsOfferedOnlyWhenThereIsMore() {
        listings.more(actor, Page.of(List.of("a", "b", "c"), 1, 2), "/town list 2");
        assertThat(sent).anyMatch(line -> line.contains("/town list 2"));

        sent.clear();
        listings.more(actor, Page.of(List.of("a", "b"), 1, 2), "/town list 2");
        assertThat(sent).isEmpty();
    }

    @Test
    @DisplayName("the order's name round-trips, so the next-page command reproduces the request")
    void sortNameCanBeTypedBack() {
        final Listings.Request request = listings.parse(actor, List.of("residents")).orElseThrow();

        assertThat(CivicSort.parse(request.sortName())).contains(request.sort());
    }

    private CommandActor actor() {
        return new CommandActor() {
            @Override
            public String name() {
                return "Tester";
            }

            @Override
            public Optional<ResidentId> resident() {
                return Optional.empty();
            }

            @Override
            public Optional<net.riftbreaker.rifttowny.api.ChunkKey> chunk() {
                return Optional.empty();
            }

            @Override
            public Optional<net.riftbreaker.rifttowny.domain.territory.SpawnPoint> position() {
                return Optional.empty();
            }

            @Override
            public boolean hasPermission(final String permission) {
                return true;
            }

            @Override
            public void send(final Component message) {
                sent.add(PlainTextComponentSerializer.plainText().serialize(message));
            }
        };
    }
}
