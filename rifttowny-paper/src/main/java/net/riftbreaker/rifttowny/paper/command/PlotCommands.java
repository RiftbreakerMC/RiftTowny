package net.riftbreaker.rifttowny.paper.command;

import net.riftbreaker.rifttowny.api.ChunkKey;
import net.riftbreaker.rifttowny.domain.org.ChangeDenial;
import net.riftbreaker.rifttowny.domain.org.ResidentId;
import net.riftbreaker.rifttowny.domain.org.ResidentRepository;
import net.riftbreaker.rifttowny.domain.org.TownRepository;
import net.riftbreaker.rifttowny.domain.service.PlotService;
import net.riftbreaker.rifttowny.domain.service.ServiceResult;
import net.riftbreaker.rifttowny.domain.territory.Claim;
import net.riftbreaker.rifttowny.domain.territory.PlotType;
import net.riftbreaker.rifttowny.paper.command.tree.CommandActor;
import net.riftbreaker.rifttowny.paper.command.tree.CommandNode;
import net.riftbreaker.rifttowny.paper.command.tree.Surface;
import net.riftbreaker.rifttowny.paper.message.DenialText;
import net.riftbreaker.rifttowny.paper.message.MessageKey;
import net.riftbreaker.rifttowny.paper.message.MessageService;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * The {@code /plot} tree, which is always about the chunk the player is standing in.
 *
 * <p>No plot is ever named. A plot is a square of ground, and the only unambiguous way to point at
 * one is to be on it — a coordinate argument would let somebody take a plot they have never seen
 * and cannot be stopped from taking.</p>
 */
public final class PlotCommands {

    private final PlotService plots;
    private final ResidentRepository residents;
    private final TownRepository towns;
    private final MessageService messages;
    private final DenialText denials;

    public PlotCommands(
            final PlotService plots,
            final ResidentRepository residents,
            final TownRepository towns,
            final MessageService messages,
            final DenialText denials
    ) {
        this.plots = Objects.requireNonNull(plots, "plots");
        this.residents = Objects.requireNonNull(residents, "residents");
        this.towns = Objects.requireNonNull(towns, "towns");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.denials = Objects.requireNonNull(denials, "denials");
    }

    /** Builds the tree. Called once at enable. */
    public CommandNode tree() {
        return CommandNode.group("plot")
                .child(CommandNode.action("info")
                        .permission("rifttowny.plot.info")
                        .usage("plot info")
                        .describedAs("Show the plot you are standing on")
                        .runs(this::info, Surface.CHAT))
                .child(CommandNode.action("claim")
                        .aliases("take")
                        .permission("rifttowny.plot.claim")
                        .usage("plot claim")
                        .describedAs("Take the plot you are standing on")
                        .runs(this::take, Surface.CHAT))
                .child(CommandNode.action("unclaim")
                        .aliases("abandon", "release")
                        .permission("rifttowny.plot.claim")
                        .usage("plot unclaim")
                        .describedAs("Give this plot back to the town")
                        .runs(this::release, Surface.CHAT))
                .child(CommandNode.action("set")
                        .aliases("type")
                        .permission("rifttowny.plot.set")
                        .usage("plot set <type>")
                        .describedAs("Say what this plot is for")
                        .completer((actor, args) -> typeNames())
                        .runs(this::setType, Surface.CHAT))
                .child(CommandNode.action("list")
                        .aliases("mine")
                        .permission("rifttowny.plot.info")
                        .usage("plot list")
                        .describedAs("List the plots you hold")
                        .runs(this::list, Surface.CHAT))
                .build();
    }

    // --- actions -------------------------------------------------------------------------------

    private void info(final CommandActor actor, final List<String> args) {
        whereTheyStand(actor, chunk -> {
            final var claim = plots.at(chunk);
            if (claim.isEmpty()) {
                denied(actor, ChangeDenial.CHUNK_NOT_CLAIMED);
                return;
            }
            final Claim plot = claim.get();
            then(actor, towns.find(plot.town()), town -> {
                messages.send(actor::send, MessageKey.PLOT_INFO_HEADER,
                        MessageService.value("chunk", describe(chunk)));
                line(actor, "Town",
                        town.map(found -> found.name().display()).orElse("unknown"));
                line(actor, "Type", plot.type().name().toLowerCase(Locale.ROOT));
                line(actor, "Kind", plot.kind().name().toLowerCase(Locale.ROOT));
                describeHolder(actor, plot);
            });
        });
    }

    /** Resolves the holder's name, because a UUID tells a player nothing. */
    private void describeHolder(final CommandActor actor, final Claim plot) {
        if (!plot.isHeld()) {
            line(actor, "Held by", "the town");
            return;
        }
        then(actor, residents.find(plot.owner()), holder -> line(actor, "Held by",
                holder.map(net.riftbreaker.rifttowny.domain.org.Resident::lastKnownName)
                        .orElse(plot.owner().value().toString())));
    }

    private void take(final CommandActor actor, final List<String> args) {
        player(actor).ifPresent(who -> whereTheyStand(actor, chunk ->
                reply(actor, plots.take(who, chunk), plot ->
                        messages.send(actor::send, MessageKey.PLOT_TAKEN,
                                MessageService.value("chunk", describe(chunk))))));
    }

    private void release(final CommandActor actor, final List<String> args) {
        player(actor).ifPresent(who -> whereTheyStand(actor, chunk ->
                reply(actor, plots.release(who, chunk), plot ->
                        messages.send(actor::send, MessageKey.PLOT_RELEASED,
                                MessageService.value("chunk", describe(chunk))))));
    }

    private void setType(final CommandActor actor, final List<String> args) {
        if (args.isEmpty()) {
            usage(actor, "plot set <type>");
            return;
        }
        final var type = PlotType.parse(args.getFirst());
        if (type.isEmpty()) {
            messages.send(actor::send, MessageKey.PLOT_UNKNOWN_TYPE,
                    MessageService.value("input", args.getFirst()),
                    MessageService.value("options", String.join(", ", typeNames())));
            return;
        }
        player(actor).ifPresent(who -> whereTheyStand(actor, chunk ->
                reply(actor, plots.setType(who, chunk, type.get()), plot ->
                        messages.send(actor::send, MessageKey.PLOT_TYPE_SET,
                                MessageService.value("chunk", describe(chunk)),
                                MessageService.value("type",
                                        plot.type().name().toLowerCase(Locale.ROOT))))));
    }

    private void list(final CommandActor actor, final List<String> args) {
        player(actor).ifPresent(who -> then(actor, plots.heldBy(who), held -> {
            if (held.isEmpty()) {
                messages.send(actor::send, MessageKey.PLOT_NONE_HELD);
                return;
            }
            messages.send(actor::send, MessageKey.PLOT_LIST_HEADER,
                    MessageService.value("count", held.size()));
            for (final Claim plot : held) {
                messages.sendRaw(actor::send, MessageKey.PLOT_LIST_LINE,
                        MessageService.value("chunk", describe(plot.chunk())),
                        MessageService.value("type",
                                plot.type().name().toLowerCase(Locale.ROOT)));
            }
        }));
    }

    // --- plumbing ------------------------------------------------------------------------------

    private static List<String> typeNames() {
        final List<String> names = new ArrayList<>();
        for (final PlotType type : PlotType.values()) {
            names.add(type.name().toLowerCase(Locale.ROOT));
        }
        return List.copyOf(names);
    }

    private static String describe(final ChunkKey chunk) {
        return chunk.chunkX() + ", " + chunk.chunkZ();
    }

    private java.util.Optional<ResidentId> player(final CommandActor actor) {
        final var who = actor.resident();
        if (who.isEmpty()) {
            messages.send(actor::send, MessageKey.COMMAND_PLAYER_ONLY);
        }
        return who;
    }

    private void whereTheyStand(final CommandActor actor, final Consumer<ChunkKey> work) {
        actor.chunk().ifPresentOrElse(
                work,
                () -> messages.send(actor::send, MessageKey.TOWN_CONSOLE_HAS_NO_CHUNK));
    }

    /** See {@code TownCommands.then}: a bare thenAccept would swallow a storage failure. */
    private <T> void then(
            final CommandActor actor,
            final CompletableFuture<T> pending,
            final Consumer<T> onValue
    ) {
        pending.whenComplete((value, failure) -> {
            if (failure != null) {
                fail(actor, failure);
                return;
            }
            try {
                onValue.accept(value);
            } catch (final RuntimeException thrown) {
                fail(actor, thrown);
            }
        });
    }

    private <T> void reply(
            final CommandActor actor,
            final CompletableFuture<ServiceResult<T>> pending,
            final Consumer<T> onSuccess
    ) {
        pending.whenComplete((result, failure) -> {
            if (failure != null) {
                fail(actor, failure);
                return;
            }
            switch (result) {
                case ServiceResult.Success<T> success -> onSuccess.accept(success.result());
                case ServiceResult.Refused<T> refused -> denied(actor, refused.reason());
                case ServiceResult.NameRejected<T> rejected ->
                        messages.send(actor::send, MessageKey.COMMAND_NAME_REJECTED,
                                MessageService.value("problems", denials.of(rejected.problems())));
            }
        });
    }

    private void fail(final CommandActor actor, final Throwable failure) {
        net.riftbreaker.rifttowny.paper.RiftTownyPlugin.getInstance().getLogger()
                .log(java.util.logging.Level.WARNING, "Command failed for " + actor.name(), failure);
        messages.send(actor::send, MessageKey.COMMAND_FAILED);
    }

    private void denied(final CommandActor actor, final ChangeDenial denial) {
        messages.send(actor::send, MessageKey.COMMAND_DENIED,
                MessageService.value("reason", denials.of(denial)));
    }

    private void usage(final CommandActor actor, final String usage) {
        messages.send(actor::send, MessageKey.COMMAND_USAGE,
                MessageService.value("usage", '/' + usage));
    }

    private void line(final CommandActor actor, final String label, final String value) {
        messages.sendRaw(actor::send, MessageKey.TOWN_INFO_LINE,
                MessageService.value("label", label), MessageService.value("value", value));
    }
}
