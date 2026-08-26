package net.riftbreaker.rifttowny.paper.command;

import net.riftbreaker.rifttowny.domain.directory.CivicDirectory;
import net.riftbreaker.rifttowny.domain.directory.Page;
import net.riftbreaker.rifttowny.domain.directory.ResidentProfile;
import net.riftbreaker.rifttowny.domain.directory.ResidentSummary;
import net.riftbreaker.rifttowny.domain.org.Resident;
import net.riftbreaker.rifttowny.domain.resident.NoticePreference;
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
import java.util.Optional;
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
    private final Listings listings;
    private final net.riftbreaker.rifttowny.domain.service.PreferenceService preferences;
    private final boolean noticesEnabledOnThisServer;
    private final Clock clock;

    public ResidentCommands(
            final ResidentRepository residents,
            final PlotService plots,
            final CivicDirectory directory,
            final net.riftbreaker.rifttowny.domain.civic.ResidentNames names,
            final net.riftbreaker.rifttowny.domain.service.PreferenceService preferences,
            final boolean noticesEnabledOnThisServer,
            final MessageService messages,
            final Clock clock
    ) {
        this.residents = Objects.requireNonNull(residents, "residents");
        this.plots = Objects.requireNonNull(plots, "plots");
        this.directory = Objects.requireNonNull(directory, "directory");
        this.names = Objects.requireNonNull(names, "names");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.listings = new Listings(messages);
        this.preferences = Objects.requireNonNull(preferences, "preferences");
        this.noticesEnabledOnThisServer = noticesEnabledOnThisServer;
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
                .child(CommandNode.action("list")
                        .permission("rifttowny.resident.info")
                        .usage("resident list [page]")
                        .describedAs("List everybody who belongs to a town")
                        .completer((actor, args) -> args.size() <= 1 ? List.of("1") : List.of())
                        .runs(this::list, Surface.CHAT))
                .child(settingsTree())
                .runs(this::info, Surface.CHAT);
    }


    /**
     * Everybody in a town, paged.
     *
     * <p>A child rather than an argument of the root, which costs one thing worth naming: a player
     * called {@code list} can no longer be looked up as {@code /resident list}. {@code /resident
     * info list} still finds them, which is the reason that child exists.</p>
     */
    public void list(final CommandActor actor, final List<String> args) {
        final Optional<Listings.Request> request = listings.parse(actor, args);
        if (request.isEmpty()) {
            return;
        }
        final int wanted = request.get().page();
        final Page<ResidentSummary> page =
                directory.residents(names, wanted, Listings.PAGE_SIZE);
        if (page.isEmpty()) {
            messages.send(actor::send, MessageKey.RESIDENT_LIST_EMPTY);
            return;
        }
        messages.send(actor::send, MessageKey.RESIDENT_LIST_HEADER,
                MessageService.value("count", page.total()),
                MessageService.value("page", page.number()),
                MessageService.value("pages", page.pages()));
        int index = page.firstIndex();
        for (final ResidentSummary row : page.items()) {
            messages.sendRaw(actor::send, MessageKey.RESIDENT_LIST_LINE,
                    MessageService.value("index", index++),
                    MessageService.value("resident", row.name()),
                    MessageService.value("town", row.townName()));
        }
        listings.more(actor, page, "/resident list " + (page.number() + 1));
    }

    /**
     * {@code /resident set} — the things a player decides about their own screen.
     *
     * <p>A group with no action of its own, so the tree renderer prints its children as the list of
     * what is settable. That list cannot go stale or lie: a preference appears in it only because
     * somebody added a node, and the node is what makes it settable.</p>
     *
     * <p>No permission beyond reaching the command. These are settings about the player asking, not
     * about a town, so there is nothing for a role to grant and nothing for a mayor to overrule.</p>
     */
    private CommandNode settingsTree() {
        return CommandNode.group("set")
                .permission("rifttowny.resident.set")
                .usage("resident set")
                .describedAs("Change what you see")
                .child(CommandNode.action("notices")
                        .permission("rifttowny.resident.set")
                        .usage("resident set notices <off|chat|actionbar|default>")
                        .describedAs("Whether territory notices reach you, and where they appear")
                        .completer((actor, args) -> args.size() <= 1
                                ? List.of("off", "chat", "actionbar", "default") : List.of())
                        .runs(this::setNotices, Surface.CHAT))
                .build();
    }

    /**
     * Chooses, clears, or says what is currently chosen.
     *
     * <p>Reading it back with no argument is a small departure from {@code /town set board}, which
     * refuses a missing value. It is worth it here: without it a player can set this and never see
     * it, and {@code /resident info} is the wrong place to put it — that screen is somebody's public
     * record, and what they have chosen about their own hotbar is not public.</p>
     */
    private void setNotices(final CommandActor actor, final List<String> args) {
        actor.resident().ifPresentOrElse(who -> {
            if (args.isEmpty()) {
                messages.send(actor::send, MessageKey.RESIDENT_NOTICES_NOW,
                        MessageService.value("value", describeNotice(who)));
                return;
            }
            final String typed = args.getFirst();
            if ("default".equalsIgnoreCase(typed)) {
                then(actor, preferences.clearNotice(who), ignored ->
                        messages.send(actor::send, MessageKey.RESIDENT_NOTICES_DEFAULT));
                return;
            }
            final var chosen = NoticePreference.parse(typed);
            if (chosen.isEmpty()) {
                messages.send(actor::send, MessageKey.RESIDENT_NOTICES_UNKNOWN,
                        MessageService.value("input", typed),
                        MessageService.value("options", NoticePreference.options()));
                return;
            }
            then(actor, preferences.chooseNotice(who, chosen.get()), stored -> {
                messages.send(actor::send, MessageKey.RESIDENT_NOTICES_SET,
                        MessageService.value("value", stored.typed()));
                if (!noticesEnabledOnThisServer) {
                    // Accepted and stored, and then told the truth: this server does not send
                    // territory notices at all, so the setting will not do anything here. A
                    // silently accepted setting that toggles nothing is the state this plugin
                    // keeps finding and closing.
                    messages.sendRaw(actor::send, MessageKey.RESIDENT_NOTICES_DISABLED_HERE);
                }
            });
        }, () -> messages.send(actor::send, MessageKey.COMMAND_PLAYER_ONLY));
    }

    /** What they have chosen, or what the server does for them because they have not. */
    private String describeNotice(final ResidentId who) {
        return preferences.noticeFor(who)
                .map(NoticePreference::typed)
                .orElse("default");
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
