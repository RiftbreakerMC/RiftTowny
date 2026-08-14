package net.riftbreaker.rifttowny.paper.command;

import net.riftbreaker.rifttowny.domain.directory.CivicDirectory;
import net.riftbreaker.rifttowny.domain.directory.ResidentProfile;
import net.riftbreaker.rifttowny.domain.org.Resident;
import net.riftbreaker.rifttowny.domain.org.ResidentId;
import net.riftbreaker.rifttowny.domain.org.ResidentRepository;
import net.riftbreaker.rifttowny.domain.service.PlotService;
import net.riftbreaker.rifttowny.paper.command.tree.CommandActor;
import net.riftbreaker.rifttowny.paper.command.tree.CommandNode;
import net.riftbreaker.rifttowny.paper.command.tree.Surface;
import net.riftbreaker.rifttowny.paper.message.MessageKey;
import net.riftbreaker.rifttowny.paper.message.MessageService;
import net.riftbreaker.rifttowny.paper.message.Times;
import org.bukkit.Bukkit;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * The {@code /resident} tree: one player's public record.
 *
 * <p>Everything shown here is public knowledge on the server it describes — which town somebody
 * belongs to, what it lets them do, roughly when they were last around. Nothing here is a position,
 * an inventory or anything else the master brief puts out of reach, and the boundary is deliberate
 * rather than incidental: this is the screen that would be the obvious place to leak them.</p>
 *
 * <p>"Last seen" is an interval rather than a timestamp for the same reason. "Three days ago"
 * answers what the reader is asking; a timestamp to the second tells a stranger the shape of
 * somebody's day.</p>
 */
public final class ResidentCommands {

    private final ResidentRepository residents;
    private final PlotService plots;
    private final CivicDirectory directory;
    private final net.riftbreaker.rifttowny.domain.civic.ResidentNames names;
    private final MessageService messages;
    private final Clock clock;

    public ResidentCommands(
            final ResidentRepository residents,
            final PlotService plots,
            final CivicDirectory directory,
            final net.riftbreaker.rifttowny.domain.civic.ResidentNames names,
            final MessageService messages,
            final Clock clock
    ) {
        this.residents = Objects.requireNonNull(residents, "residents");
        this.plots = Objects.requireNonNull(plots, "plots");
        this.directory = Objects.requireNonNull(directory, "directory");
        this.names = Objects.requireNonNull(names, "names");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Builds the tree. Called once at enable.
     *
     * <p>The root both holds a child and does something itself, which is unusual here and
     * deliberate: {@code /resident Ada} is what a player types and {@code /resident info Ada} is
     * what somebody reading the help types. Both reach the same action, so only one has to be
     * learnt and neither is a special case in the router.</p>
     */
    public CommandNode tree() {
        return CommandNode.group("resident")
                .permission("rifttowny.resident.info")
                .usage("resident [player]")
                .describedAs("Show a player's record, or your own")
                .completer((actor, args) -> args.size() <= 1 ? onlinePlayerNames() : List.of())
                .child(CommandNode.action("info")
                        .permission("rifttowny.resident.info")
                        .usage("resident info [player]")
                        .describedAs("Show a player's record, or your own")
                        .completer((actor, args) -> args.size() <= 1 ? onlinePlayerNames() : List.of())
                        .runs(this::info, Surface.CHAT))
                .runs(this::info, Surface.CHAT);
    }

    /** One player's record, named or your own. */
    public void info(final CommandActor actor, final List<String> args) {
        if (args.isEmpty()) {
            actor.resident().ifPresentOrElse(
                    who -> show(actor, residents.find(who), actor.name()),
                    () -> messages.send(actor::send, MessageKey.COMMAND_PLAYER_ONLY));
            return;
        }
        show(actor, residents.findByName(args.getFirst()), args.getFirst());
    }

    private void show(
            final CommandActor actor,
            final CompletableFuture<java.util.Optional<Resident>> pending,
            final String asked
    ) {
        then(actor, pending, found -> found.ifPresentOrElse(
                resident -> then(actor, plots.heldBy(resident.id()),
                        held -> render(actor, directory.profileOf(resident, held.size()))),
                () -> messages.send(actor::send, MessageKey.RESIDENT_UNKNOWN,
                        MessageService.value("name", asked))));
    }

    private void render(final CommandActor actor, final ResidentProfile profile) {
        messages.send(actor::send, MessageKey.RESIDENT_HEADER,
                MessageService.value("resident", profile.name()));

        line(actor, "Town", profile.townSummary()
                .map(town -> town.name())
                .orElseGet(() -> plain(MessageKey.RESIDENT_TOWNLESS)));
        if (profile.hasTown()) {
            line(actor, "Standing", profile.isMayor() ? "mayor" : "resident");
            if (!profile.roles().isEmpty()) {
                line(actor, "Roles", String.join(", ", profile.roles()));
            }
            line(actor, "Plots", String.valueOf(profile.plotsHeld()));
        }
        line(actor, "Registered", Times.date(profile.joinedAt()));
        line(actor, "Last seen", isOnline(profile.id())
                ? plain(MessageKey.RESIDENT_ONLINE_NOW)
                : Times.ago(profile.lastSeenAt(), clock.instant()));
    }

    /**
     * Whether the player is on this server right now.
     *
     * <p>Only this server. On a network the stored last-seen is what carries somebody's presence
     * elsewhere, and claiming somebody is offline because they are on a different backend would be
     * wrong in the direction that makes people look absent when they are not.</p>
     */
    private static boolean isOnline(final ResidentId who) {
        return Bukkit.getPlayer(who.value()) != null;
    }

    /** A configured word with no formatting, for embedding in another line. */
    private String plain(final MessageKey key) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(messages.render(key));
    }

    private void line(final CommandActor actor, final String label, final String value) {
        messages.sendRaw(actor::send, MessageKey.RESIDENT_LINE,
                MessageService.value("label", label), MessageService.value("value", value));
    }

    /** Consumes a lookup future, reporting a failure instead of losing it. */
    private <T> void then(
            final CommandActor actor,
            final CompletableFuture<T> pending,
            final Consumer<T> onValue
    ) {
        pending.whenComplete((value, failure) -> {
            if (failure != null) {
                net.riftbreaker.rifttowny.paper.RiftTownyPlugin.getInstance().getLogger()
                        .log(java.util.logging.Level.WARNING,
                                "Command failed for " + actor.name(), failure);
                messages.send(actor::send, MessageKey.COMMAND_FAILED);
                return;
            }
            try {
                onValue.accept(value);
            } catch (final RuntimeException thrown) {
                net.riftbreaker.rifttowny.paper.RiftTownyPlugin.getInstance().getLogger()
                        .log(java.util.logging.Level.WARNING,
                                "Command failed for " + actor.name(), thrown);
                messages.send(actor::send, MessageKey.COMMAND_FAILED);
            }
        });
    }

    private static List<String> onlinePlayerNames() {
        final List<String> named = new ArrayList<>();
        Bukkit.getOnlinePlayers().forEach(player -> named.add(player.getName()));
        return named;
    }
}
