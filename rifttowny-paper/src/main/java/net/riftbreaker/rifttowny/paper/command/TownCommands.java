package net.riftbreaker.rifttowny.paper.command;

import net.riftbreaker.rifttowny.api.ChunkKey;
import net.riftbreaker.rifttowny.domain.directory.CivicDirectory;
import net.riftbreaker.rifttowny.domain.directory.Page;
import net.riftbreaker.rifttowny.domain.directory.TerritoryMap;
import net.riftbreaker.rifttowny.domain.directory.TownSummary;
import net.riftbreaker.rifttowny.domain.flag.FlagOverride;
import net.riftbreaker.rifttowny.domain.flag.FlagSource;
import net.riftbreaker.rifttowny.domain.flag.FlagTarget;
import net.riftbreaker.rifttowny.domain.flag.ProtectionFlag;
import net.riftbreaker.rifttowny.domain.flag.Relationship;
import net.riftbreaker.rifttowny.domain.org.ChangeDenial;
import net.riftbreaker.rifttowny.domain.territory.ClaimKind;
import net.riftbreaker.rifttowny.domain.org.Resident;
import net.riftbreaker.rifttowny.domain.org.ResidentId;
import net.riftbreaker.rifttowny.domain.org.ResidentRepository;
import net.riftbreaker.rifttowny.domain.org.Town;
import net.riftbreaker.rifttowny.domain.org.TownId;
import net.riftbreaker.rifttowny.domain.org.TownProfile;
import net.riftbreaker.rifttowny.domain.justice.Outlaws;
import net.riftbreaker.rifttowny.domain.role.Permission;
import net.riftbreaker.rifttowny.domain.role.Role;
import net.riftbreaker.rifttowny.domain.service.ServiceResult;
import net.riftbreaker.rifttowny.domain.service.TownRoleService;
import net.riftbreaker.rifttowny.domain.service.TownService;
import net.riftbreaker.rifttowny.paper.command.tree.CommandActor;
import net.riftbreaker.rifttowny.paper.command.tree.CommandNode;
import net.riftbreaker.rifttowny.paper.command.tree.Surface;
import net.riftbreaker.rifttowny.paper.message.DenialText;
import net.riftbreaker.rifttowny.paper.message.MessageKey;
import net.riftbreaker.rifttowny.paper.message.MessageService;
import net.riftbreaker.rifttowny.paper.message.Times;
import org.bukkit.Bukkit;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * The {@code /town} tree.
 *
 * <p>Every node here is a thin translation: read the arguments, call a service, render the answer.
 * No rule is decided at this level — authority and invariants are the services' job, and duplicating
 * them here would give two places to disagree the moment one is changed.</p>
 *
 * <p>Actions declare {@link Surface#CHAT} where a clickable component exists and
 * {@link Surface#GUI} once a menu does. Nothing declares {@code GUI} yet, which is exactly what the
 * parity test wants to see: no action is reachable only through a menu.</p>
 */
public final class TownCommands {

    /**
     * The word that turns a preview into a purge.
     *
     * <p>The same word the importer uses, for the same reason: these are the two commands that
     * change many rows on one keystroke, and one word to learn is better than two.</p>
     */
    private static final String CONFIRM = "confirm";

    private final TownService towns;
    private final TownRoleService roles;
    private final net.riftbreaker.rifttowny.domain.service.TerritoryService territory;
    private final net.riftbreaker.rifttowny.domain.service.FlagService flags;
    private final net.riftbreaker.rifttowny.domain.service.RuinService ruins;
    private final net.riftbreaker.rifttowny.domain.service.SpawnService spawns;
    private final net.riftbreaker.rifttowny.paper.spawn.TeleportService teleports;
    private final net.riftbreaker.rifttowny.domain.service.BankService banks;
    private final net.riftbreaker.rifttowny.domain.civic.ResidentNames names;
    private final ResidentRepository residents;
    private final net.riftbreaker.rifttowny.domain.org.TownRepository townRepository;
    private final net.riftbreaker.rifttowny.domain.org.NationRepository nations;
    private final CivicDirectory directory;
    private final TerritoryMap maps;
    private final Listings listings;
    private final net.riftbreaker.rifttowny.domain.service.OutlawService outlaws;
    private final MessageService messages;
    private final DenialText denials;

    public TownCommands(
            final TownService towns,
            final TownRoleService roles,
            final net.riftbreaker.rifttowny.domain.service.TerritoryService territory,
            final net.riftbreaker.rifttowny.domain.service.FlagService flags,
            final net.riftbreaker.rifttowny.domain.service.RuinService ruins,
            final net.riftbreaker.rifttowny.domain.service.SpawnService spawns,
            final net.riftbreaker.rifttowny.paper.spawn.TeleportService teleports,
            final net.riftbreaker.rifttowny.domain.service.BankService banks,
            final net.riftbreaker.rifttowny.domain.service.OutlawService outlaws,
            final net.riftbreaker.rifttowny.domain.civic.ResidentNames names,
            final ResidentRepository residents,
            final net.riftbreaker.rifttowny.domain.org.TownRepository townRepository,
            final net.riftbreaker.rifttowny.domain.org.NationRepository nations,
            final CivicDirectory directory,
            final TerritoryMap maps,
            final MessageService messages,
            final DenialText denials
    ) {
        this.towns = Objects.requireNonNull(towns, "towns");
        this.roles = Objects.requireNonNull(roles, "roles");
        this.territory = Objects.requireNonNull(territory, "territory");
        this.flags = Objects.requireNonNull(flags, "flags");
        this.ruins = Objects.requireNonNull(ruins, "ruins");
        this.spawns = Objects.requireNonNull(spawns, "spawns");
        this.teleports = Objects.requireNonNull(teleports, "teleports");
        this.banks = Objects.requireNonNull(banks, "banks");
        this.outlaws = Objects.requireNonNull(outlaws, "outlaws");
        this.names = Objects.requireNonNull(names, "names");
        this.residents = Objects.requireNonNull(residents, "residents");
        this.townRepository = Objects.requireNonNull(townRepository, "townRepository");
        this.nations = Objects.requireNonNull(nations, "nations");
        this.directory = Objects.requireNonNull(directory, "directory");
        this.maps = Objects.requireNonNull(maps, "maps");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.denials = Objects.requireNonNull(denials, "denials");
        this.listings = new Listings(messages);
    }

    /** Builds the tree. Called once at enable. */
    public CommandNode tree() {
        return CommandNode.group("town")
                .child(CommandNode.action("new")
                        .aliases("create", "found")
                        .permission("rifttowny.town.new")
                        .usage("town new <name>")
                        .describedAs("Found a town")
                        .runs(this::found, Surface.CHAT))
                .child(CommandNode.action("info")
                        .aliases("who")
                        .permission("rifttowny.town.info")
                        .usage("town info [town]")
                        .describedAs("Show a town, or your own")
                        .completer((actor, args) -> args.size() <= 1 ? townNames() : List.of())
                        .runs(this::info, Surface.CHAT))
                .child(CommandNode.action("list")
                        .permission("rifttowny.town.info")
                        .usage("town list [page] [name|residents|land|age]")
                        .describedAs("List every town")
                        .completer((actor, args) -> args.size() <= 1
                                ? List.of("1", "name", "residents", "land", "age")
                                : List.of("name", "residents", "land", "age"))
                        .runs(this::list, Surface.CHAT))
                .child(CommandNode.action("online")
                        .permission("rifttowny.town.info")
                        .usage("town online [town]")
                        .describedAs("Who from a town is here now")
                        .completer((actor, args) -> args.size() <= 1 ? townNames() : List.of())
                        .runs(this::online, Surface.CHAT))
                .child(CommandNode.action("map")
                        .permission("rifttowny.town.info")
                        .usage("town map [size]")
                        .describedAs("Draw the land around you")
                        .completer((actor, args) -> args.size() <= 1 ? List.of("small", "big") : List.of())
                        .runs(this::map, Surface.CHAT))
                .child(CommandNode.action("add")
                        .aliases("invite")
                        .permission("rifttowny.town.add")
                        .usage("town add <player>")
                        .describedAs("Invite a player to your town")
                        .completer((actor, args) -> onlinePlayerNames())
                        .runs(this::add, Surface.CHAT))
                .child(CommandNode.action("uninvite")
                        .aliases("withdraw")
                        .permission("rifttowny.town.add")
                        .usage("town uninvite <player>")
                        .describedAs("Withdraw an invitation")
                        .completer((actor, args) -> onlinePlayerNames())
                        .runs(this::uninvite, Surface.CHAT))
                .child(CommandNode.action("invites")
                        .permission("rifttowny.town.invites")
                        .usage("town invites")
                        .describedAs("Show the towns that have invited you")
                        .runs(this::invites, Surface.CHAT))
                .child(CommandNode.action("accept")
                        .aliases("join")
                        .permission("rifttowny.town.invites")
                        .usage("town accept <town>")
                        .describedAs("Accept an invitation")
                        .runs(this::accept, Surface.CHAT))
                .child(CommandNode.action("deny")
                        .aliases("decline")
                        .permission("rifttowny.town.invites")
                        .usage("town deny <town>")
                        .describedAs("Turn an invitation down")
                        .runs(this::decline, Surface.CHAT))
                .child(CommandNode.action("kick")
                        .aliases("remove")
                        .permission("rifttowny.town.kick")
                        .usage("town kick <player>")
                        .describedAs("Remove a resident")
                        .completer((actor, args) -> onlinePlayerNames())
                        .runs(this::kick, Surface.CHAT))
                .child(CommandNode.action("leave")
                        .permission("rifttowny.town.leave")
                        .usage("town leave")
                        .describedAs("Leave your town")
                        .runs(this::leave, Surface.CHAT))
                .child(CommandNode.action("rename")
                        .permission("rifttowny.town.rename")
                        .usage("town rename <name>")
                        .describedAs("Rename your town")
                        .runs(this::rename, Surface.CHAT))
                .child(CommandNode.action("mayor")
                        .permission("rifttowny.town.mayor")
                        .usage("town mayor <player>")
                        .describedAs("Hand over the mayoralty")
                        .completer((actor, args) -> onlinePlayerNames())
                        .runs(this::mayor, Surface.CHAT))
                .child(CommandNode.action("delete")
                        .aliases("disband")
                        .permission("rifttowny.town.delete")
                        .usage("town delete")
                        .describedAs("Disband your town")
                        .runs(this::disband, Surface.CHAT))
                .child(CommandNode.action("claim")
                        .permission("rifttowny.town.claim")
                        .usage("town claim [outpost]")
                        .describedAs("Claim the chunk you are standing in")
                        .completer((actor, args) -> List.of("outpost"))
                        .runs(this::claim, Surface.CHAT))
                .child(CommandNode.action("unclaim")
                        .permission("rifttowny.town.unclaim")
                        .usage("town unclaim [all]")
                        .describedAs("Release the chunk you are standing in, or all but the homeblock")
                        .completer((actor, args) -> args.size() <= 1 ? List.of("all") : List.of())
                        .runs(this::unclaim, Surface.CHAT))
                .child(CommandNode.action("preview")
                        .permission("rifttowny.town.claim")
                        .usage("town preview [outpost]")
                        .describedAs("See what claiming here would do")
                        .completer((actor, args) -> List.of("outpost"))
                        .runs(this::previewClaim, Surface.CHAT))
                .child(CommandNode.action("homeblock")
                        .permission("rifttowny.town.homeblock")
                        .usage("town homeblock")
                        .describedAs("Move your home chunk here")
                        .runs(this::homeblock, Surface.CHAT))
                .child(CommandNode.action("bank")
                        .permission("rifttowny.town.bank")
                        .usage("town bank")
                        .describedAs("Show the town treasury and its recent movements")
                        .runs(this::bank, Surface.CHAT))
                .child(CommandNode.action("deposit")
                        .permission("rifttowny.town.bank")
                        .usage("town deposit <amount>")
                        .describedAs("Put your own money into the town")
                        .runs(this::deposit, Surface.CHAT))
                .child(CommandNode.action("withdraw")
                        .permission("rifttowny.town.bank")
                        .usage("town withdraw <amount>")
                        .describedAs("Take money out of the town")
                        .runs(this::withdraw, Surface.CHAT))
                .child(CommandNode.action("spawn")
                        .permission("rifttowny.town.spawn")
                        .usage("town spawn [town]")
                        .describedAs("Travel to a town's spawn, or your own")
                        .completer((actor, args) -> args.size() <= 1 ? publicSpawnTownNames() : List.of())
                        .runs(this::spawn, Surface.CHAT))
                .child(CommandNode.action("setspawn")
                        .permission("rifttowny.town.setspawn")
                        .usage("town setspawn")
                        .describedAs("Set your town's spawn to where you stand")
                        .runs(this::setSpawn, Surface.CHAT))
                .child(CommandNode.action("delspawn")
                        .aliases("unsetspawn")
                        .permission("rifttowny.town.setspawn")
                        .usage("town delspawn")
                        .describedAs("Remove your town's spawn")
                        .runs(this::deleteSpawn, Surface.CHAT))
                .child(CommandNode.action("reclaim")
                        .permission("rifttowny.town.reclaim")
                        .usage("town reclaim")
                        .describedAs("Rebuild the fallen town you are standing in")
                        .runs(this::reclaim, Surface.CHAT))
                .child(CommandNode.action("join")
                        .permission("rifttowny.town.invites")
                        .usage("town join <town>")
                        .describedAs("Walk into a town that has declared itself open")
                        .completer((actor, args) -> args.size() <= 1 ? openTownNames() : List.of())
                        .runs(this::joinOpen, Surface.CHAT))
                .child(roleTree())
                .child(flagTree())
                .child(trustTree())
                .child(mergeTree())
                .child(CommandNode.action("purge")
                        .permission("rifttowny.town.kick")
                        .usage("town purge <days> [confirm]")
                        .describedAs("Remove residents nobody has seen for that long")
                        .completer((actor, args) -> args.size() <= 1
                                ? List.of("30", "60", "90") : List.of())
                        .runs(this::purge, Surface.CHAT))
                .child(outlawTree())
                .child(settingsTree())
                .build();
    }

    /**
     * The {@code /town set} tree.
     *
     * <p>Grouped rather than six flat subcommands, because that is how a Towny player already
     * expects to type them and because a flat {@code /town board} sitting beside {@code /town bank}
     * reads as an action rather than as a setting.</p>
     *
     * <p>All six go through one service call taking a transform, so two co-mayors changing two
     * different settings in the same second cannot overwrite each other.</p>
     */
    private CommandNode settingsTree() {
        return CommandNode.group("set")
                .permission("rifttowny.town.set")
                .usage("town set")
                .describedAs("Change what your town says about itself")
                .child(CommandNode.action("board")
                        .permission("rifttowny.town.set")
                        .usage("town set board <text|clear>")
                        .describedAs("A message for your residents")
                        .runs((actor, args) -> setText(actor, args, "town set board <text|clear>",
                                MessageKey.TOWN_SET_BOARD, TownProfile::withBoard), Surface.CHAT))
                .child(CommandNode.action("tag")
                        .permission("rifttowny.town.set")
                        .usage("town set tag <text|clear>")
                        .describedAs("A short abbreviation for your town")
                        .runs((actor, args) -> setText(actor, args, "town set tag <text|clear>",
                                MessageKey.TOWN_SET_TAG, TownProfile::withTag), Surface.CHAT))
                .child(CommandNode.action("colour")
                        .aliases("color", "mapcolor", "mapcolour")
                        .permission("rifttowny.town.set")
                        .usage("town set colour <#a1b2c3|clear>")
                        .describedAs("How your town is drawn on a map")
                        .completer((actor, args) -> List.of("clear"))
                        .runs(this::setColour, Surface.CHAT))
                .child(CommandNode.action("open")
                        .permission("rifttowny.town.set")
                        .usage("town set open <on|off>")
                        .describedAs("Whether anybody may join without an invitation")
                        .completer((actor, args) -> List.of("on", "off"))
                        .runs((actor, args) -> setSwitch(actor, args, "town set open <on|off>",
                                MessageKey.TOWN_SET_OPEN, TownProfile::withOpen), Surface.CHAT))
                .child(CommandNode.action("public")
                        .permission("rifttowny.town.set")
                        .usage("town set public <on|off>")
                        .describedAs("Whether outsiders may travel to your spawn")
                        .completer((actor, args) -> List.of("on", "off"))
                        .runs((actor, args) -> setSwitch(actor, args, "town set public <on|off>",
                                MessageKey.TOWN_SET_PUBLIC, TownProfile::withPublicSpawn), Surface.CHAT))
                .child(CommandNode.action("neutral")
                        .aliases("peaceful")
                        .permission("rifttowny.town.set")
                        .usage("town set neutral <on|off>")
                        .describedAs("Declare your town neutral in war")
                        .completer((actor, args) -> List.of("on", "off"))
                        .runs((actor, args) -> setSwitch(actor, args, "town set neutral <on|off>",
                                MessageKey.TOWN_SET_NEUTRAL, TownProfile::withNeutral), Surface.CHAT))
                .build();
    }





    /**
     * {@code /town purge <days> [confirm]} — clearing out residents nobody has seen.
     *
     * <p>A preview unless the confirmation word is typed, like the importer, and for the same
     * reason: this is the other command in the plugin that changes many rows on one keystroke. The
     * preview lists who would go, so the number a mayor confirms is one they have read names
     * against.</p>
     *
     * <p>The day count is validated here rather than in the service, so a mistyped number is a
     * message about the number instead of a refusal about a town. One day is the floor: a purge with
     * no floor is a way to empty a town by typing a zero.</p>
     */
    private void purge(final CommandActor actor, final List<String> args) {
        if (args.isEmpty()) {
            usage(actor, "town purge <days> [" + CONFIRM + ']');
            return;
        }
        final OptionalInt days = positiveDays(args.getFirst());
        if (days.isEmpty()) {
            messages.send(actor::send, MessageKey.TOWN_PURGE_BAD_PERIOD,
                    MessageService.value("input", args.getFirst()));
            return;
        }
        final boolean apply = args.size() > 1 && CONFIRM.equalsIgnoreCase(args.get(1));
        withTown(actor, (who, town) ->
                reply(actor, towns.purge(who, town.id(), java.time.Duration.ofDays(days.getAsInt()), apply), purged -> {
                    if (purged.count() == 0) {
                        messages.send(actor::send, MessageKey.TOWN_PURGE_NOBODY,
                                MessageService.value("days", days.getAsInt()));
                    } else if (purged.applied()) {
                        messages.send(actor::send, MessageKey.TOWN_PURGED,
                                MessageService.value("count", purged.count()),
                                MessageService.value("town", town.name().display()));
                    } else {
                        messages.send(actor::send, MessageKey.TOWN_PURGE_PREVIEW,
                                MessageService.value("count", purged.count()),
                                MessageService.value("days", days.getAsInt()));
                        messages.sendRaw(actor::send, MessageKey.TOWN_PURGE_PREVIEW_LINE,
                                MessageService.value("residents", nameList(purged.removed())));
                        messages.sendRaw(actor::send, MessageKey.TOWN_PURGE_CONFIRM,
                                MessageService.value("command",
                                        "/town purge " + days.getAsInt() + ' ' + CONFIRM));
                    }
                    if (purged.protectedByRank() > 0) {
                        // Said out loud rather than left as a silent difference between the number
                        // they expected and the number they got.
                        messages.sendRaw(actor::send, MessageKey.TOWN_PURGE_OUTRANKED,
                                MessageService.value("count", purged.protectedByRank()));
                    }
                }));
    }

    /** Names, sorted, for a preview a mayor is meant to read before confirming. */
    private String nameList(final List<ResidentId> who) {
        final List<String> named = new ArrayList<>(who.size());
        who.forEach(one -> named.add(names.describe(one)));
        named.sort(String.CASE_INSENSITIVE_ORDER);
        return String.join(", ", named);
    }

    /** A whole number of days, at least one. Anything else is a typo worth reporting. */
    private static OptionalInt positiveDays(final String raw) {
        try {
            final int days = Integer.parseInt(raw.trim());
            return days >= 1 ? OptionalInt.of(days) : OptionalInt.empty();
        } catch (final NumberFormatException notANumber) {
            return OptionalInt.empty();
        }
    }
    /**
     * {@code /town merge} — two towns becoming one.
     *
     * <p>Two commands and two mayors. The surviving town offers; the town that will cease to exist
     * accepts, naming who it is joining. The irreversible half is therefore typed by the person who
     * loses everything, and the survivor's name is the argument, so there is no separate
     * confirmation word to add — typing the name of the town that is about to absorb yours is the
     * confirmation.</p>
     */
    private CommandNode mergeTree() {
        return CommandNode.group("merge")
                .permission("rifttowny.town.merge")
                .usage("town merge")
                .describedAs("Absorb another town, or accept being absorbed")
                .child(CommandNode.action("offer")
                        .permission("rifttowny.town.merge")
                        .usage("town merge offer <town>")
                        .describedAs("Offer to absorb another town. Mayor only")
                        .completer((actor, args) -> args.size() <= 1 ? townNames() : List.of())
                        .runs(this::mergeOffer, Surface.CHAT))
                .child(CommandNode.action("cancel")
                        .permission("rifttowny.town.merge")
                        .usage("town merge cancel <town>")
                        .describedAs("Withdraw an offer you made")
                        .runs(this::mergeCancel, Surface.CHAT))
                .child(CommandNode.action("accept")
                        .permission("rifttowny.town.merge")
                        .usage("town merge accept <town>")
                        .describedAs("Dissolve your town into theirs. Mayor only, and final")
                        .runs(this::mergeAccept, Surface.CHAT))
                .child(CommandNode.action("offers")
                        .permission("rifttowny.town.merge")
                        .usage("town merge offers")
                        .describedAs("Who has offered to absorb your town")
                        .runs(this::mergeOffers, Surface.CHAT))
                .runs(this::mergeOffers, Surface.CHAT);
    }

    private void mergeOffer(final CommandActor actor, final List<String> args) {
        withTownAndNamedTown(actor, args, "town merge offer <town>", (who, mine, theirs) ->
                reply(actor, towns.offerMerge(who, mine.id(), theirs.id()), ignored ->
                        messages.send(actor::send, MessageKey.TOWN_MERGE_OFFERED,
                                MessageService.value("town", theirs.name().display()))));
    }

    private void mergeCancel(final CommandActor actor, final List<String> args) {
        withTownAndNamedTown(actor, args, "town merge cancel <town>", (who, mine, theirs) ->
                reply(actor, towns.withdrawMergeOffer(who, mine.id(), theirs.id()), ignored ->
                        messages.send(actor::send, MessageKey.TOWN_MERGE_CANCELLED,
                                MessageService.value("town", theirs.name().display()))));
    }

    private void mergeAccept(final CommandActor actor, final List<String> args) {
        withTownAndNamedTown(actor, args, "town merge accept <town>", (who, mine, theirs) ->
                reply(actor, towns.acceptMerge(who, mine.id(), theirs.id()), merged -> {
                    messages.send(actor::send, MessageKey.TOWN_MERGED,
                            MessageService.value("absorbed", merged.absorbedName().display()),
                            MessageService.value("survivor", merged.survivorName().display()),
                            MessageService.value("residents", merged.movedCount()),
                            MessageService.value("chunks", merged.chunksMoved()));
                    if (!merged.rolesLost().isEmpty()) {
                        // Named because nothing else records them: the role book went with the town,
                        // and re-creating them in the survivor should be transcription rather than
                        // trying to remember what a town that no longer exists used to have.
                        messages.sendRaw(actor::send, MessageKey.TOWN_MERGE_ROLES_LOST,
                                MessageService.value("roles",
                                        String.join(", ", merged.rolesLost())));
                    }
                }));
    }

    private void mergeOffers(final CommandActor actor, final List<String> args) {
        withTown(actor, (who, town) -> then(actor, towns.mergeOffersTo(town.id()), offers -> {
            if (offers.isEmpty()) {
                messages.send(actor::send, MessageKey.TOWN_MERGE_NO_OFFERS);
                return;
            }
            messages.send(actor::send, MessageKey.TOWN_MERGE_OFFERS_HEADER);
            for (final var offer : offers) {
                messages.sendRaw(actor::send, MessageKey.TOWN_MERGE_OFFERS_LINE,
                        MessageService.value("town", nameOfTown(offer.inviter())),
                        MessageService.value("expires",
                                net.riftbreaker.rifttowny.paper.message.Times.date(
                                        offer.expiresAt())));
            }
        }));
    }

    /** A town's display name from the cache, or its id when the cache has never seen it. */
    private String nameOfTown(final net.riftbreaker.rifttowny.domain.org.OrganisationId who) {
        if (!(who instanceof net.riftbreaker.rifttowny.domain.org.TownId townId)) {
            return String.valueOf(who);
        }
        return directory.town(townId)
                .map(net.riftbreaker.rifttowny.domain.directory.TownSummary::name)
                .orElseGet(() -> townId.value().toString());
    }

    /**
     * Reads the actor's own town and a named one.
     *
     * <p>Both are needed by every merge command, and the named town is resolved through the
     * repository rather than the cache because a merge must act on the real row.</p>
     */
    private void withTownAndNamedTown(
            final CommandActor actor,
            final List<String> args,
            final String usage,
            final TwoTownWork work
    ) {
        if (args.isEmpty()) {
            usage(actor, usage);
            return;
        }
        withTown(actor, (who, mine) ->
                then(actor, townRepository.findByName(args.getFirst()), found ->
                        found.ifPresentOrElse(
                                theirs -> work.accept(who, mine, theirs),
                                () -> denied(actor, ChangeDenial.TOWN_NOT_FOUND))));
    }

    @FunctionalInterface
    private interface TwoTownWork {
        void accept(ResidentId who, Town mine, Town theirs);
    }
    /**
     * {@code /town trust} — the one grant a town can make to somebody who is not in it.
     *
     * <p>The mirror of {@link #outlawTree()} and gated by the same node, because they are the two
     * ends of one decision. Both were reachable only from the domain until now: the table, the
     * aggregate rules, the events and the {@code TRUSTED} rung have existed since {@code V2}, and
     * nothing could put a name in the list.</p>
     */
    private CommandNode trustTree() {
        return CommandNode.group("trust")
                .permission("rifttowny.town.trust")
                .usage("town trust")
                .describedAs("Trust an outsider, or take it back")
                .child(CommandNode.action("add")
                        .permission("rifttowny.town.trust")
                        .usage("town trust add <player>")
                        .describedAs("Trust an outsider. MANAGE_TRUST decides who can")
                        .completer((actor, args) -> args.size() <= 1
                                ? onlinePlayerNames() : List.of())
                        .runs(this::trustAdd, Surface.CHAT))
                .child(CommandNode.action("remove")
                        .permission("rifttowny.town.trust")
                        .usage("town trust remove <player>")
                        .describedAs("Take trust back")
                        .runs(this::trustRemove, Surface.CHAT))
                .child(CommandNode.action("list")
                        .permission("rifttowny.town.trust")
                        .usage("town trust list")
                        .describedAs("Who this town trusts")
                        .runs(this::trustList, Surface.CHAT))
                .runs(this::trustList, Surface.CHAT);
    }

    private void trustAdd(final CommandActor actor, final List<String> args) {
        withTownAndTarget(actor, args, "town trust add <player>", (who, town, target) ->
                reply(actor, towns.trust(who, town.id(), target), updated ->
                        messages.send(actor::send, MessageKey.TOWN_TRUSTED,
                                MessageService.value("resident", names.describe(target)),
                                MessageService.value("town", updated.name().display()))));
    }

    private void trustRemove(final CommandActor actor, final List<String> args) {
        withTownAndTarget(actor, args, "town trust remove <player>", (who, town, target) ->
                reply(actor, towns.untrust(who, town.id(), target), updated ->
                        messages.send(actor::send, MessageKey.TOWN_UNTRUSTED,
                                MessageService.value("resident", names.describe(target)),
                                MessageService.value("town", updated.name().display()))));
    }

    /**
     * Answered from the town the actor already holds, so it costs no query.
     *
     * <p>Read from the aggregate rather than a cache, unlike the outlaw list: trust lives on the
     * town itself, which the command has in hand by the time it gets here.</p>
     */
    private void trustList(final CommandActor actor, final List<String> args) {
        withTown(actor, (who, town) -> {
            final var listed = town.trustedOutsiders();
            if (listed.isEmpty()) {
                messages.send(actor::send, MessageKey.TOWN_TRUST_LIST_EMPTY,
                        MessageService.value("town", town.name().display()));
                return;
            }
            final List<String> named = new ArrayList<>(listed.size());
            listed.forEach(one -> named.add(names.describe(one)));
            named.sort(String.CASE_INSENSITIVE_ORDER);
            messages.send(actor::send, MessageKey.TOWN_TRUST_LIST_HEADER,
                    MessageService.value("town", town.name().display()),
                    MessageService.value("count", named.size()));
            messages.sendRaw(actor::send, MessageKey.TOWN_TRUST_LIST_LINE,
                    MessageService.value("residents", String.join(", ", named)));
        });
    }
    /**
     * {@code /town outlaw} — who this town will not have.
     *
     * <p>{@code MANAGE_TRUST}, the same node the trust list uses, because the two are one decision
     * from opposite ends: which outsiders this town treats differently from other outsiders.</p>
     */
    private CommandNode outlawTree() {
        return CommandNode.group("outlaw")
                .permission("rifttowny.town.outlaw")
                .usage("town outlaw")
                .describedAs("Declare a player unwelcome, or take it back")
                .child(CommandNode.action("add")
                        .permission("rifttowny.town.outlaw")
                        .usage("town outlaw add <player>")
                        .describedAs("Declare a player unwelcome. MANAGE_TRUST decides who can")
                        .completer((actor, args) -> args.size() <= 1
                                ? onlinePlayerNames() : List.of())
                        .runs(this::outlawAdd, Surface.CHAT))
                .child(CommandNode.action("remove")
                        .aliases("pardon")
                        .permission("rifttowny.town.outlaw")
                        .usage("town outlaw remove <player>")
                        .describedAs("Take an outlawry back")
                        .runs(this::outlawRemove, Surface.CHAT))
                .child(CommandNode.action("list")
                        .permission("rifttowny.town.outlaw")
                        .usage("town outlaw list")
                        .describedAs("Who this town has outlawed")
                        .runs(this::outlawList, Surface.CHAT))
                .runs(this::outlawList, Surface.CHAT);
    }

    private void outlawAdd(final CommandActor actor, final List<String> args) {
        withTownAndTarget(actor, args, "town outlaw add <player>", (who, town, target) ->
                reply(actor, outlaws.declare(who, town.id(), target), ignored ->
                        messages.send(actor::send, MessageKey.TOWN_OUTLAWED,
                                MessageService.value("resident", names.describe(target)),
                                MessageService.value("town", town.name().display()))));
    }

    private void outlawRemove(final CommandActor actor, final List<String> args) {
        withTownAndTarget(actor, args, "town outlaw remove <player>", (who, town, target) ->
                reply(actor, outlaws.pardon(who, town.id(), target), ignored ->
                        messages.send(actor::send, MessageKey.TOWN_OUTLAW_PARDONED,
                                MessageService.value("resident", names.describe(target)),
                                MessageService.value("town", town.name().display()))));
    }

    /** Answered from the cache, so a town's own list costs nothing to look at. */
    private void outlawList(final CommandActor actor, final List<String> args) {
        withTown(actor, (who, town) -> {
            final var listed = outlaws.of(town.id());
            if (listed.isEmpty()) {
                messages.send(actor::send, MessageKey.TOWN_OUTLAW_LIST_EMPTY,
                        MessageService.value("town", town.name().display()));
                return;
            }
            // One line each, carrying who declared it and when. rt_town_outlaw has written both
            // since V14 and nothing read them back, which made the migration's own reason for the
            // columns unreachable: an outlawry is a sanction, and "which of my officers did this"
            // is the first question a mayor faces when a player appeals one.
            final List<Outlaws.Declaration> declarations =
                    new ArrayList<>(outlaws.declarationsOf(town.id()));
            declarations.sort(java.util.Comparator.comparing(
                    one -> names.describe(one.who()), String.CASE_INSENSITIVE_ORDER));
            messages.send(actor::send, MessageKey.TOWN_OUTLAW_LIST_HEADER,
                    MessageService.value("town", town.name().display()),
                    MessageService.value("count", declarations.size()));
            final java.time.Instant now = java.time.Instant.now();
            for (final Outlaws.Declaration one : declarations) {
                messages.sendRaw(actor::send, MessageKey.TOWN_OUTLAW_LIST_LINE,
                        MessageService.value("resident", names.describe(one.who())),
                        MessageService.value("by", one.author()
                                .map(names::describe)
                                .orElse("the server")),
                        MessageService.value("when", Times.ago(one.declaredAt(), now)));
            }
        });
    }
    /**
     * The {@code /town flag} tree.
     *
     * <p>Two scopes, because a town wants both: {@code set} and {@code clear} apply to all of its
     * land, {@code here} to the one chunk the player is standing in. A claim override beats an
     * organisation one, which is what lets a town open a single market square without opening the
     * rest of itself.</p>
     */
    private CommandNode flagTree() {
        return CommandNode.group("flag")
                .permission("rifttowny.town.flag")
                .describedAs("Change what your town allows")
                .usage("town flag")
                .child(CommandNode.action("list")
                        .permission("rifttowny.town.flag")
                        .usage("town flag list [here]")
                        .describedAs("Show what your town has overridden")
                        .completer((actor, args) -> List.of("here"))
                        .runs(this::flagList, Surface.CHAT))
                .child(CommandNode.action("set")
                        .permission("rifttowny.town.flag")
                        .usage("town flag set <flag> <relationship> <allow|deny>")
                        .describedAs("Set a flag for the whole town")
                        .completer(TownCommands::completeFlagArguments)
                        .runs((actor, args) -> flagSet(actor, args, false), Surface.CHAT))
                .child(CommandNode.action("clear")
                        .permission("rifttowny.town.flag")
                        .usage("town flag clear <flag> <relationship>")
                        .describedAs("Remove a town-wide override")
                        .completer(TownCommands::completeFlagArguments)
                        .runs((actor, args) -> flagClear(actor, args, false), Surface.CHAT))
                .child(CommandNode.action("here")
                        .permission("rifttowny.town.flag")
                        .usage("town flag here <flag> <relationship> <allow|deny|clear>")
                        .describedAs("Set a flag on this chunk only")
                        .completer(TownCommands::completeFlagArguments)
                        .runs(this::flagHere, Surface.CHAT))
                .build();
    }

    private CommandNode roleTree() {
        return CommandNode.group("role")
                .permission("rifttowny.role.view")
                .describedAs("Manage town roles")
                .usage("town role")
                .child(CommandNode.action("list")
                        // Repeated from the parent deliberately. The executor tests only the
                        // resolved node, on the assumption that a child is at least as narrow as
                        // its parent; a child with no permission under a gated parent breaks that
                        // assumption and lets /town role list past a gate /town role refuses.
                        .permission("rifttowny.role.view")
                        .usage("town role list")
                        .describedAs("List the town's roles")
                        .runs(this::roleList, Surface.CHAT))
                .child(CommandNode.action("new")
                        .aliases("create")
                        .permission("rifttowny.role.manage")
                        .usage("town role new <name> <priority>")
                        .describedAs("Create a role")
                        .runs(this::roleCreate, Surface.CHAT))
                .child(CommandNode.action("delete")
                        .permission("rifttowny.role.manage")
                        .usage("town role delete <name>")
                        .describedAs("Delete a role")
                        .completer((actor, args) -> List.of())
                        .runs(this::roleDelete, Surface.CHAT))
                .child(CommandNode.action("assign")
                        .aliases("give")
                        .permission("rifttowny.role.assign")
                        .usage("town role assign <player> <role>")
                        .describedAs("Give a resident a role")
                        .completer((actor, args) -> args.size() <= 1 ? onlinePlayerNames() : List.of())
                        .runs(this::roleAssign, Surface.CHAT))
                .child(CommandNode.action("unassign")
                        .aliases("take")
                        .permission("rifttowny.role.assign")
                        .usage("town role unassign <player> <role>")
                        .describedAs("Take a role away")
                        .completer((actor, args) -> args.size() <= 1 ? onlinePlayerNames() : List.of())
                        .runs(this::roleUnassign, Surface.CHAT))
                .child(CommandNode.action("grant")
                        .aliases("allow")
                        .permission("rifttowny.role.manage")
                        .usage("town role grant <role> <permission>")
                        .describedAs("Let a role do something")
                        .completer((actor, args) -> args.size() <= 1
                                ? List.of()
                                : lowerNames(Permission.values()))
                        .runs(this::roleGrant, Surface.CHAT))
                .child(CommandNode.action("revoke")
                        .aliases("deny")
                        .permission("rifttowny.role.manage")
                        .usage("town role revoke <role> <permission>")
                        .describedAs("Stop a role doing something")
                        .completer((actor, args) -> args.size() <= 1
                                ? List.of()
                                : lowerNames(Permission.values()))
                        .runs(this::roleRevoke, Surface.CHAT))
                .child(CommandNode.action("priority")
                        .aliases("rank")
                        .permission("rifttowny.role.manage")
                        .usage("town role priority <role> <number>")
                        .describedAs("Move a role in the ranking")
                        .completer((actor, args) -> List.of())
                        .runs(this::rolePriority, Surface.CHAT))
                .child(CommandNode.action("clone")
                        .aliases("copy")
                        .permission("rifttowny.role.manage")
                        .usage("town role clone <role> <new name> <priority>")
                        .describedAs("Copy a role under a new name")
                        .completer((actor, args) -> List.of())
                        .runs(this::roleClone, Surface.CHAT))
                .child(CommandNode.action("rename")
                        .permission("rifttowny.role.manage")
                        .usage("town role rename <role> <new name>")
                        .describedAs("Rename a role")
                        .completer((actor, args) -> List.of())
                        .runs(this::roleRename, Surface.CHAT))
                .child(CommandNode.action("set")
                        .aliases("label")
                        .permission("rifttowny.role.manage")
                        .usage("town role set <role> <display|icon|prefix> <value|clear>")
                        .describedAs("Set a role's label, icon or chat prefix")
                        .completer((actor, args) -> args.size() == 2
                                ? List.of("display", "icon", "prefix")
                                : List.of())
                        .runs(this::roleSet, Surface.CHAT))
                .build();
    }

    // --- town actions --------------------------------------------------------------------------

    private void found(final CommandActor actor, final List<String> args) {
        player(actor).ifPresent(who -> {
            if (args.isEmpty()) {
                usage(actor, "town new <name>");
                return;
            }
            reply(actor, towns.found(who, actor.name(), args.getFirst()), town ->
                    messages.send(actor::send, MessageKey.TOWN_FOUNDED,
                            MessageService.value("town", town.name().display())));
        });
    }

    /**
     * One town, named or your own.
     *
     * <p>The town itself comes from the caches, so looking at one costs no query. The two lines that
     * cannot — the treasury, and the nation's name rather than its id — are read together and the
     * whole screen is printed in their callback. Printing the cached lines first and letting the
     * read land afterwards would put the treasury below the resident list on a fast database and
     * above it on a slow one, which is the sort of thing players report as a rendering bug.</p>
     */
    private void info(final CommandActor actor, final List<String> args) {
        if (!args.isEmpty()) {
            directory.factsNamed(args.getFirst()).ifPresentOrElse(
                    facts -> showTown(actor, facts),
                    () -> denied(actor, ChangeDenial.TOWN_NOT_FOUND));
            return;
        }
        player(actor).ifPresent(who -> directory.factsOf(who).ifPresentOrElse(
                facts -> showTown(actor, facts),
                () -> messages.send(actor::send, MessageKey.TOWN_NOT_IN_A_TOWN)));
    }

    private void showTown(
            final CommandActor actor,
            final net.riftbreaker.rifttowny.domain.civic.TownFacts facts
    ) {
        final Town town = facts.town();

        // One read, not two. The nation's name used to be a second query here and is now in the
        // nation cache, which leaves the treasury as the only thing on this screen storage owns.
        then(actor, banks.balanceOf(town.id()), balance -> {
            messages.send(actor::send, MessageKey.TOWN_INFO_HEADER,
                    MessageService.value("town", town.name().display()));
            line(actor, "Mayor", names.describe(town.mayor()));
            line(actor, "Founded", Times.date(town.createdAt()));
            line(actor, "Treasury", balance.describe());
            line(actor, "Land", directory.town(town.id())
                    .map(summary -> summary.chunks() + " chunk(s)")
                    .orElse("0 chunk(s)"));
            line(actor, "Nation", town.nation()
                    .flatMap(directory::nationName)
                    .orElse("none"));
            line(actor, "Trusted", String.valueOf(town.trustedOutsiders().size()));

            final TownProfile profile = town.profile();
            if (profile.hasTag()) {
                line(actor, "Tag", profile.tag());
            }
            // Only shown when it is not the default. A row reading "Open: no" on every town on the
            // server is three characters of information spread over a whole line.
            if (profile.open() || profile.publicSpawn() || profile.neutral()) {
                line(actor, "Declared", describeDeclarations(profile));
            }
            if (profile.hasBoard()) {
                messages.sendRaw(actor::send, MessageKey.TOWN_BOARD_LINE,
                        MessageService.value("board", profile.board()));
            }

            messages.sendRaw(actor::send, MessageKey.TOWN_RESIDENTS_HEADER,
                    MessageService.value("town", town.name().display()),
                    MessageService.value("count", town.residentCount()));
            messages.sendRaw(actor::send, MessageKey.TOWN_RESIDENTS_LINE,
                    MessageService.value("residents", residentNames(town.id())));
        });
    }

    /**
     * The settings a town has actually turned on.
     *
     * <p>Only the true ones, joined. A town that has declared nothing gets no line at all, which is
     * why the caller checks before calling.</p>
     */
    private static String describeDeclarations(final TownProfile profile) {
        final List<String> declared = new ArrayList<>(3);
        if (profile.open()) {
            declared.add("open to newcomers");
        }
        if (profile.publicSpawn()) {
            declared.add("public spawn");
        }
        if (profile.neutral()) {
            declared.add("neutral");
        }
        return String.join(", ", declared);
    }

    /** Everybody in a town, mayor first, as one comma-separated line. */
    private String residentNames(final TownId town) {
        final List<String> named = new ArrayList<>();
        directory.residentsOf(town).forEach(resident -> named.add(names.describe(resident)));
        return named.isEmpty() ? "nobody" : String.join(", ", named);
    }

    /**
     * Every town on the server.
     *
     * <p>Sorted and paged from memory. The one read is the nation list, taken once for the whole
     * page rather than once per row — a listing that queried per row would be the reason a curious
     * player could stall a server, and the number of nations is small enough that fetching all of
     * them costs less than fetching the handful shown.</p>
     */
    private void list(final CommandActor actor, final List<String> args) {
        listings.parse(actor, args).ifPresent(request -> {
            final Page<TownSummary> page =
                    directory.towns(request.sort(), request.page(), Listings.PAGE_SIZE);
            if (page.isEmpty()) {
                messages.send(actor::send, MessageKey.TOWN_LIST_EMPTY);
                return;
            }
            messages.sendRaw(actor::send, MessageKey.TOWN_LIST_HEADER,
                    MessageService.value("count", page.total()),
                    MessageService.value("page", page.number()),
                    MessageService.value("pages", page.pages()),
                    MessageService.value("sort", request.sortName()));

            int index = page.firstIndex();
            for (final TownSummary summary : page.items()) {
                messages.sendRaw(actor::send, MessageKey.TOWN_LIST_LINE,
                        MessageService.value("index", index++),
                        MessageService.value("town", summary.name()),
                        MessageService.value("residents", summary.residents()),
                        MessageService.value("chunks", summary.chunks()),
                        nationTag(summary));
            }
            listings.more(actor, page,
                    "/town list " + (page.number() + 1) + ' ' + request.sortName());
        });
    }

    /**
     * The nation part of a listing line.
     *
     * <p>Empty for a town in no nation, which is most of them. A literal "none" on every row would
     * be the widest column on the screen and would say nothing.</p>
     */
    private net.kyori.adventure.text.minimessage.tag.resolver.TagResolver nationTag(
            final TownSummary summary) {
        final String name = summary.nationId().flatMap(directory::nationName).orElse(null);
        return net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.component(
                "nation",
                name == null
                        ? net.kyori.adventure.text.Component.empty()
                        : messages.render(MessageKey.TOWN_LIST_NATION,
                                MessageService.value("nation", name)));
    }

    /** Who from a town is on the server now. */
    private void online(final CommandActor actor, final List<String> args) {
        withNamedOrOwnTown(actor, args, facts -> {
            final List<ResidentId> here = new ArrayList<>();
            for (final ResidentId resident : directory.residentsOf(facts.id())) {
                if (Bukkit.getPlayer(resident.value()) != null) {
                    here.add(resident);
                }
            }
            if (here.isEmpty()) {
                messages.send(actor::send, MessageKey.TOWN_ONLINE_NONE,
                        MessageService.value("town", facts.displayName()));
                return;
            }
            messages.sendRaw(actor::send, MessageKey.TOWN_ONLINE_HEADER,
                    MessageService.value("town", facts.displayName()),
                    MessageService.value("count", here.size()),
                    MessageService.value("residents", facts.town().residentCount()));
            for (final ResidentId resident : here) {
                final List<String> roleNames = directory.roleNamesOf(facts.id(), resident);
                messages.sendRaw(actor::send, MessageKey.TOWN_ONLINE_LINE,
                        MessageService.value("resident", names.describe(resident)),
                        net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.component(
                                "roles",
                                roleNames.isEmpty()
                                        ? net.kyori.adventure.text.Component.empty()
                                        : messages.render(MessageKey.TOWN_ONLINE_ROLES,
                                                MessageService.value("roles",
                                                        String.join(", ", roleNames)))));
            }
        });
    }

    /**
     * The land around the player.
     *
     * <p>Drawn from the caches on the thread the command arrived on, which on Folia is the only
     * thread allowed to know where the player is standing at all.</p>
     */
    private void map(final CommandActor actor, final List<String> args) {
        final Optional<ChunkKey> centre = actor.chunk();
        if (centre.isEmpty()) {
            messages.send(actor::send, MessageKey.TOWN_CONSOLE_HAS_NO_CHUNK);
            return;
        }
        final boolean big = !args.isEmpty() && args.getFirst().toLowerCase(Locale.ROOT).startsWith("b");
        final boolean small = !args.isEmpty() && args.getFirst().toLowerCase(Locale.ROOT).startsWith("s");
        final int radiusX = big ? 11 : small ? 4 : TerritoryMap.DEFAULT_RADIUS_X;
        final int radiusZ = big ? 7 : small ? 2 : TerritoryMap.DEFAULT_RADIUS_Z;

        final TerritoryMap.MapView view =
                maps.around(centre.get(), actor.resident().orElse(null), radiusX, radiusZ);

        final org.bukkit.World world = Bukkit.getWorld(centre.get().worldId());
        messages.sendRaw(actor::send, MessageKey.MAP_HEADER,
                MessageService.value("world", world == null ? "world" : world.getName()),
                MessageService.value("x", centre.get().chunkX()),
                MessageService.value("z", centre.get().chunkZ()));
        MapRenderer.render(view).forEach(actor::send);
        messages.sendRaw(actor::send, MessageKey.MAP_LEGEND);
        messages.sendRaw(actor::send, MessageKey.MAP_LEGEND_SHAPES);
    }

    // --- settings ------------------------------------------------------------------------------

    /**
     * Sets a piece of text, or clears it.
     *
     * <p>The rest of the line, not one word: a board is a sentence, and a player who types one
     * should not have to quote it. {@code clear} is the way to empty it, because typing nothing
     * after {@code /town set board} is far more often a half-finished command than an intention to
     * erase what is there.</p>
     */
    private void setText(
            final CommandActor actor,
            final List<String> args,
            final String usage,
            final MessageKey confirmation,
            final java.util.function.BiFunction<TownProfile, String, TownProfile> change
    ) {
        if (args.isEmpty()) {
            usage(actor, usage);
            return;
        }
        final String joined = String.join(" ", args);
        final boolean clearing = args.size() == 1 && "clear".equalsIgnoreCase(args.getFirst());
        final String value = clearing ? "" : joined;

        withTown(actor, (who, town) -> reply(actor,
                towns.setProfile(who, town.id(), profile -> change.apply(profile, value)),
                updated -> messages.send(actor::send, confirmation,
                        MessageService.value("town", updated.name().display()),
                        MessageService.value("value", value.isEmpty() ? "nothing" : value))));
    }

    /** Sets one of the three on/off settings. */
    private void setSwitch(
            final CommandActor actor,
            final List<String> args,
            final String usage,
            final MessageKey confirmation,
            final java.util.function.BiFunction<TownProfile, Boolean, TownProfile> change
    ) {
        if (args.isEmpty()) {
            usage(actor, usage);
            return;
        }
        final Optional<Boolean> decision = parseSwitch(args.getFirst());
        if (decision.isEmpty()) {
            usage(actor, usage);
            return;
        }
        withTown(actor, (who, town) -> reply(actor,
                towns.setProfile(who, town.id(), profile -> change.apply(profile, decision.get())),
                updated -> messages.send(actor::send, confirmation,
                        MessageService.value("town", updated.name().display()),
                        MessageService.value("state", decision.get() ? "on" : "off"))));
    }

    private void setColour(final CommandActor actor, final List<String> args) {
        if (args.isEmpty()) {
            usage(actor, "town set colour <#a1b2c3|clear>");
            return;
        }
        final String raw = args.getFirst();
        final boolean clearing = "clear".equalsIgnoreCase(raw) || "none".equalsIgnoreCase(raw);
        final Optional<net.riftbreaker.rifttowny.domain.org.MapColour> colour =
                clearing
                        ? Optional.empty()
                        : net.riftbreaker.rifttowny.domain.org.MapColour.parse(raw);
        if (!clearing && colour.isEmpty()) {
            denied(actor, ChangeDenial.NOT_A_COLOUR);
            return;
        }
        withTown(actor, (who, town) -> reply(actor,
                towns.setProfile(who, town.id(), profile -> profile.withColour(colour.orElse(null))),
                updated -> messages.send(actor::send, MessageKey.TOWN_SET_COLOUR,
                        MessageService.value("town", updated.name().display()),
                        MessageService.value("value", updated.profile().mapColour()
                                .map(net.riftbreaker.rifttowny.domain.org.MapColour::hashHex)
                                .orElse("the default")))));
    }

    /** Walks into a town that has declared itself open. */
    private void joinOpen(final CommandActor actor, final List<String> args) {
        if (args.isEmpty()) {
            usage(actor, "town join <town>");
            return;
        }
        player(actor).ifPresent(who -> directory.factsNamed(args.getFirst()).ifPresentOrElse(
                facts -> reply(actor, towns.joinOpenTown(who, facts.id()), updated ->
                        messages.send(actor::send, MessageKey.TOWN_JOINED,
                                MessageService.value("resident", actor.name()),
                                MessageService.value("town", updated.name().display()))),
                () -> denied(actor, ChangeDenial.TOWN_NOT_FOUND)));
    }

    /**
     * Only the towns somebody could actually walk into.
     *
     * <p>Completing every town here would offer a list where all but a handful answer "that town is
     * not open" — a suggestion that is wrong for most of what it suggests is worse than none.</p>
     */
    private List<String> openTownNames() {
        return townNamesWhere(profile -> profile.open());
    }

    /** Only the towns whose spawn an outsider could actually reach. */
    private List<String> publicSpawnTownNames() {
        return townNamesWhere(profile -> profile.publicSpawn());
    }

    private List<String> townNamesWhere(final java.util.function.Predicate<TownProfile> wanted) {
        final List<String> named = new ArrayList<>();
        for (final TownSummary summary : directory.allTowns()) {
            directory.facts(summary.id())
                    .filter(facts -> wanted.test(facts.town().profile()))
                    .ifPresent(facts -> named.add(summary.name()));
        }
        return named;
    }

    /** {@code on} or {@code off}, generously. Anything else is a usage error rather than a guess. */
    private static Optional<Boolean> parseSwitch(final String raw) {
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "on", "true", "yes", "open", "enable", "enabled" -> Optional.of(true);
            case "off", "false", "no", "closed", "disable", "disabled" -> Optional.of(false);
            default -> Optional.empty();
        };
    }

    /** Resolves the town named in the arguments, or the actor's own when there are none. */
    private void withNamedOrOwnTown(
            final CommandActor actor,
            final List<String> args,
            final Consumer<net.riftbreaker.rifttowny.domain.civic.TownFacts> work
    ) {
        if (!args.isEmpty()) {
            directory.factsNamed(args.getFirst())
                    .ifPresentOrElse(work, () -> denied(actor, ChangeDenial.TOWN_NOT_FOUND));
            return;
        }
        player(actor).ifPresent(who -> directory.factsOf(who).ifPresentOrElse(
                work, () -> messages.send(actor::send, MessageKey.TOWN_NOT_IN_A_TOWN)));
    }

    private void add(final CommandActor actor, final List<String> args) {
        withTownAndTarget(actor, args, "town add <player>", (who, town, target) ->
                reply(actor, towns.invite(who, town.id(), target), invitation ->
                        messages.send(actor::send, MessageKey.TOWN_INVITED,
                                MessageService.value("resident", args.getFirst()),
                                MessageService.value("town", town.name().display()))));
    }

    private void uninvite(final CommandActor actor, final List<String> args) {
        withTownAndTarget(actor, args, "town uninvite <player>", (who, town, target) ->
                reply(actor, towns.withdrawInvitation(who, town.id(), target), ignored ->
                        messages.send(actor::send, MessageKey.TOWN_INVITE_WITHDRAWN,
                                MessageService.value("resident", args.getFirst()))));
    }

    /** What the player has been offered. Their own list, so it needs no town. */
    private void invites(final CommandActor actor, final List<String> args) {
        player(actor).ifPresent(who -> then(actor, towns.invitationsFor(who), offers -> {
            if (offers.isEmpty()) {
                messages.send(actor::send, MessageKey.TOWN_NO_INVITES);
                return;
            }
            messages.send(actor::send, MessageKey.TOWN_INVITES_HEADER);
            for (final var offer : offers) {
                then(actor, townRepository.find(TownId.parse(offer.inviter().value().toString())),
                        town -> messages.sendRaw(actor::send, MessageKey.TOWN_INVITES_LINE,
                                MessageService.value("town", town
                                        .map(found -> found.name().display())
                                        .orElse(offer.inviter().value().toString())),
                                MessageService.value("expires", offer.expiresAt())));
            }
        }));
    }

    private void accept(final CommandActor actor, final List<String> args) {
        withInvitingTown(actor, args, "town accept <town>", (who, town) ->
                reply(actor, towns.acceptInvitation(who, town.id()), updated ->
                        messages.send(actor::send, MessageKey.TOWN_JOINED,
                                MessageService.value("resident", actor.name()),
                                MessageService.value("town", updated.name().display()))));
    }

    private void decline(final CommandActor actor, final List<String> args) {
        withInvitingTown(actor, args, "town deny <town>", (who, town) ->
                reply(actor, towns.declineInvitation(who, town.id()), ignored ->
                        messages.send(actor::send, MessageKey.TOWN_INVITE_DECLINED,
                                MessageService.value("town", town.name().display()))));
    }

    /** Resolves a town by name for a player answering an offer, who is in no town of their own. */
    private void withInvitingTown(
            final CommandActor actor,
            final List<String> args,
            final String usage,
            final BiConsumer<ResidentId, Town> work
    ) {
        if (args.isEmpty()) {
            usage(actor, usage);
            return;
        }
        player(actor).ifPresent(who ->
                then(actor, townRepository.findByName(args.getFirst()), found ->
                        found.ifPresentOrElse(
                                town -> work.accept(who, town),
                                () -> denied(actor, ChangeDenial.TOWN_NOT_FOUND))));
    }

    private void kick(final CommandActor actor, final List<String> args) {
        withTownAndTarget(actor, args, "town kick <player>", (who, town, target) ->
                reply(actor, towns.kick(who, target, town.id()), updated ->
                        messages.send(actor::send, MessageKey.TOWN_KICKED,
                                MessageService.value("resident", args.getFirst()),
                                MessageService.value("town", updated.name().display()))));
    }

    private void leave(final CommandActor actor, final List<String> args) {
        withTown(actor, (who, town) ->
                reply(actor, towns.leave(who, town.id()), updated ->
                        messages.send(actor::send, MessageKey.TOWN_LEFT,
                                MessageService.value("town", updated.name().display()))));
    }

    private void rename(final CommandActor actor, final List<String> args) {
        if (args.isEmpty()) {
            usage(actor, "town rename <name>");
            return;
        }
        withTown(actor, (who, town) ->
                reply(actor, towns.rename(who, town.id(), args.getFirst()), updated ->
                        messages.send(actor::send, MessageKey.TOWN_RENAMED,
                                MessageService.value("town", updated.name().display()))));
    }

    private void mayor(final CommandActor actor, final List<String> args) {
        withTownAndTarget(actor, args, "town mayor <player>", (who, town, target) ->
                reply(actor, towns.transferMayoralty(who, town.id(), target), updated ->
                        messages.send(actor::send, MessageKey.TOWN_MAYOR_TRANSFERRED,
                                MessageService.value("resident", args.getFirst()),
                                MessageService.value("town", updated.name().display()))));
    }

    private void disband(final CommandActor actor, final List<String> args) {
        withTown(actor, (who, town) ->
                reply(actor, towns.disband(who, town.id()), ignored ->
                        messages.send(actor::send, MessageKey.TOWN_DISBANDED,
                                MessageService.value("town", town.name().display()))));
    }

    // --- territory actions ---------------------------------------------------------------------

    private void claim(final CommandActor actor, final List<String> args) {
        whereTheyStand(actor, chunk -> withTown(actor, (who, town) -> {
            final ClaimKind kind = kindFrom(args, town);
            reply(actor, territory.claim(who, town.id(), chunk, kind), created ->
                    then(actor, territory.territoryOf(town.id()), all ->
                            messages.send(actor::send, MessageKey.TOWN_CLAIMED,
                                    MessageService.value("chunk", describe(chunk)),
                                    MessageService.value("kind", created.kind()),
                                    MessageService.value("total", all.size()))));
        }));
    }

    /**
     * Releasing everything but the homeblock.
     *
     * <p>No confirmation word, unlike the import. This is reversible - the land can be claimed
     * again, and the refund is what was paid for it - so a word to type would be ceremony rather
     * than a safeguard. The message says how much went, which is what tells somebody who typed it
     * by accident that they did.</p>
     */
    private void unclaimAll(final CommandActor actor) {
        withTown(actor, (who, town) ->
                reply(actor, territory.unclaimAll(who, town.id()), released -> {
                    messages.send(actor::send, MessageKey.TOWN_UNCLAIMED_ALL,
                            MessageService.value("chunks", released.count()),
                            MessageService.value("town", town.name().display()));
                    then(actor, spawns.clearIfOutsideTerritory(town.id()), lost -> {
                        if (Boolean.TRUE.equals(lost)) {
                            messages.send(actor::send, MessageKey.TOWN_SPAWN_LOST_WITH_LAND);
                        }
                    });
                }));
    }

    private void unclaim(final CommandActor actor, final List<String> args) {
        if (!args.isEmpty() && "all".equalsIgnoreCase(args.getFirst())) {
            unclaimAll(actor);
            return;
        }
        whereTheyStand(actor, chunk -> withTown(actor, (who, town) ->
                reply(actor, territory.unclaim(who, town.id(), chunk), released -> {
                    messages.send(actor::send, MessageKey.TOWN_UNCLAIMED,
                            MessageService.value("chunk", describe(released)));
                    // Releasing the chunk a spawn stood in takes the spawn with it, and the person
                    // who did it is the one who should hear about it - not the next resident to
                    // find the command broken.
                    then(actor, spawns.clearIfOutsideTerritory(town.id()), lost -> {
                        if (Boolean.TRUE.equals(lost)) {
                            messages.send(actor::send, MessageKey.TOWN_SPAWN_LOST_WITH_LAND);
                        }
                    });
                })));
    }

    private void homeblock(final CommandActor actor, final List<String> args) {
        whereTheyStand(actor, chunk -> withTown(actor, (who, town) ->
                reply(actor, territory.moveHomeblock(who, town.id(), chunk), moved ->
                        messages.send(actor::send, MessageKey.TOWN_HOMEBLOCK_MOVED,
                                MessageService.value("chunk", describe(moved.newChunk()))))));
    }

    private void previewClaim(final CommandActor actor, final List<String> args) {
        whereTheyStand(actor, chunk -> withTown(actor, (who, town) ->
                then(actor, territory.previewClaim(town.id(), chunk, kindFrom(args, town)),
                        preview -> {
                            if (preview.permitted()) {
                                messages.send(actor::send, MessageKey.TOWN_CLAIM_PREVIEW_OK,
                                        MessageService.value("chunk", describe(chunk)),
                                        MessageService.value("before", preview.claimsBefore()),
                                        MessageService.value("after", preview.claimsAfter()));
                            } else {
                                messages.send(actor::send, MessageKey.TOWN_CLAIM_PREVIEW_REFUSED,
                                        MessageService.value("chunk", describe(chunk)),
                                        MessageService.value("reason",
                                                denials.of(preview.refusal().orElseThrow())));
                            }
                        })));
    }

    /**
     * Which kind the player meant.
     *
     * <p>A town with no land at all is founding its homeblock, so the word is ignored there:
     * {@code TownClaims} refuses anything else as the first claim, and offering a choice that will
     * always be refused is worse than making it for them.</p>
     */
    private ClaimKind kindFrom(final List<String> args, final Town town) {
        if (!args.isEmpty() && args.getFirst().equalsIgnoreCase("outpost")) {
            return ClaimKind.OUTPOST;
        }
        return ClaimKind.ORDINARY;
    }

    /** Runs work with the chunk the actor is standing in, or says the console is nowhere. */
    private void whereTheyStand(final CommandActor actor, final Consumer<ChunkKey> work) {
        actor.chunk().ifPresentOrElse(
                work,
                () -> messages.send(actor::send, MessageKey.TOWN_CONSOLE_HAS_NO_CHUNK));
    }

    private static String describe(final ChunkKey chunk) {
        return chunk.chunkX() + ", " + chunk.chunkZ();
    }

    // --- role actions --------------------------------------------------------------------------

    private void roleList(final CommandActor actor, final List<String> args) {
        withTown(actor, (who, town) -> then(actor, roles.list(town.id()), found -> {
            messages.send(actor::send, MessageKey.ROLE_LIST_HEADER,
                    MessageService.value("town", town.name().display()));
            for (final Role role : found) {
                messages.sendRaw(actor::send, MessageKey.ROLE_LIST_LINE,
                        MessageService.value("role", role.name()),
                        MessageService.value("priority", role.priority()),
                        MessageService.value("label", label(role)),
                        MessageService.value("permissions", role.permissions().size()));
            }
        }));
    }


    /**
     * How a role is labelled, when that differs from what it is called.
     *
     * <p>Empty for a role nobody has decorated, which is every role until somebody runs
     * {@code role set} — so the listing stays exactly as it was for a town that does not use this.
     * A decoration nobody can see is the reason these three columns sat unread for so long.</p>
     */
    private static String label(final Role role) {
        final List<String> parts = new ArrayList<>(3);
        role.icon().ifPresent(parts::add);
        if (!role.displayName().equals(role.name())) {
            parts.add('"' + role.displayName() + '"');
        }
        role.chatPrefix().ifPresent(prefix -> parts.add("prefix " + prefix));
        return parts.isEmpty() ? "" : " " + String.join(" ", parts);
    }
    private void roleCreate(final CommandActor actor, final List<String> args) {
        if (args.size() < 2) {
            usage(actor, "town role new <name> <priority>");
            return;
        }
        final Optional<Integer> priority = parsePriority(args.get(1));
        if (priority.isEmpty()) {
            usage(actor, "town role new <name> <priority>");
            return;
        }
        withTown(actor, (who, town) -> reply(actor,
                roles.create(who, town.id(), args.getFirst(), priority.get(), java.util.Set.of()),
                role -> messages.send(actor::send, MessageKey.ROLE_CREATED,
                        MessageService.value("role", role.name()))));
    }

    private void roleDelete(final CommandActor actor, final List<String> args) {
        if (args.isEmpty()) {
            usage(actor, "town role delete <name>");
            return;
        }
        withTown(actor, (who, town) -> then(actor, roles.list(town.id()), found -> {
            final Optional<Role> role = byName(found, args.getFirst());
            if (role.isEmpty()) {
                denied(actor, ChangeDenial.ROLE_NOT_FOUND);
                return;
            }
            reply(actor, roles.delete(who, town.id(), role.get().id()), ignored ->
                    messages.send(actor::send, MessageKey.ROLE_DELETED,
                            MessageService.value("role", role.get().name())));
        }));
    }

    private void roleAssign(final CommandActor actor, final List<String> args) {
        withRoleAndTarget(actor, args, "town role assign <player> <role>",
                (who, town, target, role) -> reply(actor,
                        roles.assign(who, town.id(), target, role.id()), ignored ->
                                messages.send(actor::send, MessageKey.ROLE_ASSIGNED,
                                        MessageService.value("resident", args.getFirst()),
                                        MessageService.value("role", role.name()))));
    }

    private void roleUnassign(final CommandActor actor, final List<String> args) {
        withRoleAndTarget(actor, args, "town role unassign <player> <role>",
                (who, town, target, role) -> reply(actor,
                        roles.unassign(who, town.id(), target, role.id()), ignored ->
                                messages.send(actor::send, MessageKey.ROLE_UNASSIGNED,
                                        MessageService.value("resident", args.getFirst()),
                                        MessageService.value("role", role.name()))));
    }

    /**
     * Giving a role a permission, and taking it back.
     *
     * <p>Without these a custom role is inert for ever. {@code /town role new} creates one with an
     * empty permission set, and nothing else could add to it, so a town could name a rank, hand it
     * to people, and have it grant nothing, permanently. The service methods behind this existed
     * the whole time with no caller, which is why the gap stayed invisible: the domain looked
     * complete, and only the command tree knew otherwise.</p>
     *
     * <p>{@code MANAGE_ROLES} gates both, and the editor refuses to grant a permission the actor
     * does not hold themselves, so this cannot be used to climb.</p>
     */
    private void roleGrant(final CommandActor actor, final List<String> args) {
        withRoleAndPermission(actor, args, "town role grant <role> <permission>",
                (who, town, role, permission) -> reply(actor,
                        roles.grant(who, town.id(), role.id(), permission), ignored ->
                                messages.send(actor::send, MessageKey.ROLE_PERMISSION_GRANTED,
                                        MessageService.value("permission",
                                                permission.name().toLowerCase(Locale.ROOT)),
                                        MessageService.value("role", role.name()))));
    }

    private void roleRevoke(final CommandActor actor, final List<String> args) {
        withRoleAndPermission(actor, args, "town role revoke <role> <permission>",
                (who, town, role, permission) -> reply(actor,
                        roles.revoke(who, town.id(), role.id(), permission), ignored ->
                                messages.send(actor::send, MessageKey.ROLE_PERMISSION_REVOKED,
                                        MessageService.value("permission",
                                                permission.name().toLowerCase(Locale.ROOT)),
                                        MessageService.value("role", role.name()))));
    }


    /** Copies a role's whole permission set under a new name, which is how a variant rank is made. */
    private void roleClone(final CommandActor actor, final List<String> args) {
        if (args.size() < 3) {
            usage(actor, "town role clone <role> <new name> <priority>");
            return;
        }
        final Optional<Integer> priority = parsePriority(args.get(2));
        if (priority.isEmpty()) {
            usage(actor, "town role clone <role> <new name> <priority>");
            return;
        }
        withRole(actor, args, (who, town, role) -> reply(actor,
                roles.clone(who, town.id(), role.id(), args.get(1), priority.get()),
                made -> messages.send(actor::send, MessageKey.ROLE_CREATED,
                        MessageService.value("role", made.name()))));
    }


    /**
     * Setting a role's label, icon and chat prefix.
     *
     * <p>{@code SPECIFICATION.md} says a role has "a display name, icon, chat prefix, integer
     * priority and a permission set". Three of those five were stored on every role, written and
     * read back by the role store, and settable by nothing: only {@code Role.decorate} could change
     * them and its one caller was a storage test. So in production a display name always equalled
     * the name and the other two were always null.</p>
     *
     * <p>{@code clear} is the way back, and it is a real argument rather than an empty string,
     * because a player cannot type "nothing" into a command that splits on spaces.</p>
     */
    private void roleSet(final CommandActor actor, final List<String> args) {
        if (args.size() < 2) {
            usage(actor, "town role set <role> <display|icon|prefix> <value|clear>");
            return;
        }
        final String field = args.get(1).toLowerCase(Locale.ROOT);
        if (!List.of("display", "icon", "prefix").contains(field)) {
            usage(actor, "town role set <role> <display|icon|prefix> <value|clear>");
            return;
        }
        final String value = args.size() < 3
                ? null
                : String.join(" ", args.subList(2, args.size()));
        final String wanted = value == null || "clear".equalsIgnoreCase(value) ? null : value;

        withRole(actor, args, (who, town, role) -> reply(actor,
                roles.decorate(who, town.id(), role.id(),
                        "display".equals(field) ? wanted : role.displayName(),
                        "icon".equals(field) ? wanted : role.icon().orElse(null),
                        "prefix".equals(field) ? wanted : role.chatPrefix().orElse(null)),
                ignored -> messages.send(actor::send, MessageKey.ROLE_DECORATED,
                        MessageService.value("role", role.name()),
                        MessageService.value("field", field),
                        MessageService.value("value", wanted == null ? "cleared" : wanted))));
    }
    private void roleRename(final CommandActor actor, final List<String> args) {
        if (args.size() < 2) {
            usage(actor, "town role rename <role> <new name>");
            return;
        }
        withRole(actor, args, (who, town, role) -> reply(actor,
                roles.rename(who, town.id(), role.id(), args.get(1)), ignored ->
                        messages.send(actor::send, MessageKey.ROLE_RENAMED,
                                MessageService.value("role", role.name()),
                                MessageService.value("name", args.get(1)))));
    }
    /** Moves a role up or down the ranking, which is what decides who may act on whom. */
    private void rolePriority(final CommandActor actor, final List<String> args) {
        if (args.size() < 2) {
            usage(actor, "town role priority <role> <number>");
            return;
        }
        final Optional<Integer> priority = parsePriority(args.get(1));
        if (priority.isEmpty()) {
            usage(actor, "town role priority <role> <number>");
            return;
        }
        withRole(actor, args, (who, town, role) -> reply(actor,
                roles.reprioritise(who, town.id(), role.id(), priority.get()), ignored ->
                        messages.send(actor::send, MessageKey.ROLE_REPRIORITISED,
                                MessageService.value("role", role.name()),
                                MessageService.value("priority",
                                        String.valueOf(priority.get())))));
    }

    /** Reads a role by name and a permission by name, reporting whichever one failed. */
    private void withRoleAndPermission(
            final CommandActor actor,
            final List<String> args,
            final String usage,
            final RolePermissionWork work
    ) {
        if (args.size() < 2) {
            usage(actor, usage);
            return;
        }
        final Optional<Permission> permission = parsePermission(actor, args.get(1));
        if (permission.isEmpty()) {
            return;
        }
        withRole(actor, args, (who, town, role) -> work.accept(who, town, role, permission.get()));
    }

    /** Resolves the first argument as one of the acting town's roles. */
    private void withRole(final CommandActor actor, final List<String> args, final NamedRoleWork work) {
        withTown(actor, (who, town) ->
                then(actor, roles.list(town.id()), found -> byName(found, args.getFirst())
                        .ifPresentOrElse(
                                role -> work.accept(who, town, role),
                                () -> denied(actor, ChangeDenial.ROLE_NOT_FOUND))));
    }

    private Optional<Permission> parsePermission(final CommandActor actor, final String raw) {
        final Optional<Permission> permission = Permission.parse(raw);
        if (permission.isEmpty()) {
            messages.send(actor::send, MessageKey.ROLE_UNKNOWN_PERMISSION,
                    MessageService.value("input", raw),
                    MessageService.value("options", names(Permission.values())));
        }
        return permission;
    }

    @FunctionalInterface
    private interface RolePermissionWork {
        void accept(ResidentId who, Town town, Role role, Permission permission);
    }

    @FunctionalInterface
    private interface NamedRoleWork {
        void accept(ResidentId who, Town town, Role role);
    }

    // --- bank actions --------------------------------------------------------------------------

    /**
     * The treasury and how it got that way.
     *
     * <p>Works with no economy plugin: the balance and the ledger are civic state. Only the two
     * commands that move money between a player and the town need a wallet, and they say so.</p>
     */
    private void bank(final CommandActor actor, final List<String> args) {
        withTown(actor, (who, town) -> then(actor, banks.balanceOf(town.id()), balance -> {
            messages.send(actor::send, MessageKey.TOWN_BANK_HEADER,
                    MessageService.value("town", town.name().display()),
                    MessageService.value("balance", balance.describe()));
            if (!banks.economyAvailable()) {
                messages.sendRaw(actor::send, MessageKey.TOWN_BANK_NO_ECONOMY);
            }
            then(actor, banks.historyOf(town.id(),
                    net.riftbreaker.rifttowny.domain.service.BankService.DEFAULT_HISTORY),
                    history -> {
                        if (history.isEmpty()) {
                            messages.sendRaw(actor::send, MessageKey.TOWN_BANK_NO_HISTORY);
                            return;
                        }
                        for (final var entry : history) {
                            messages.sendRaw(actor::send, MessageKey.TOWN_BANK_LINE,
                                    MessageService.value("movement", entry.describe()),
                                    MessageService.value("detail", entry.note().map(note -> " " + note).orElse("")),
                                    MessageService.value("by",
                                            entry.author().map(names::describe).orElse("the server")));
                        }
                    });
        }));
    }

    private void deposit(final CommandActor actor, final List<String> args) {
        withAmount(actor, args, "town deposit <amount>", (who, town, amount) ->
                reply(actor, banks.deposit(who, town.id(), amount), balance ->
                        messages.send(actor::send, MessageKey.TOWN_BANK_DEPOSITED,
                                MessageService.value("amount", amount.describe()),
                                MessageService.value("balance", balance.describe()))));
    }

    private void withdraw(final CommandActor actor, final List<String> args) {
        withAmount(actor, args, "town withdraw <amount>", (who, town, amount) ->
                reply(actor, banks.withdraw(who, town.id(), amount), balance ->
                        messages.send(actor::send, MessageKey.TOWN_BANK_WITHDREW,
                                MessageService.value("amount", amount.describe()),
                                MessageService.value("balance", balance.describe()))));
    }

    /** Reads an amount, refusing anything that is not one rather than guessing at it. */
    private void withAmount(
            final CommandActor actor,
            final List<String> args,
            final String usage,
            final AmountWork work
    ) {
        if (args.isEmpty()) {
            usage(actor, usage);
            return;
        }
        final var amount = net.riftbreaker.rifttowny.domain.bank.Money.parse(
                args.getFirst(), banks.currency());
        if (amount.isEmpty()) {
            messages.send(actor::send, MessageKey.TOWN_BANK_BAD_AMOUNT,
                    MessageService.value("input", args.getFirst()));
            return;
        }
        withTown(actor, (who, town) -> work.accept(who, town, amount.get()));
    }

    @FunctionalInterface
    private interface AmountWork {
        void accept(ResidentId actor, Town town, net.riftbreaker.rifttowny.domain.bank.Money amount);
    }

    // --- spawn actions -------------------------------------------------------------------------

    /**
     * Travels to the town's spawn.
     *
     * <p>The destination is resolved and authorised in one go before anybody moves — the service
     * checks the role permission and that the town still owns the land, because a teleport costs a
     * chunk load and a stale spawn drops the player in somebody else's territory.</p>
     */
    private void spawn(final CommandActor actor, final List<String> args) {
        player(actor).ifPresent(who -> {
            // Checked before the lookup: a player on cooldown does not need a database round trip
            // to be told to wait.
            final var wait = teleports.cooldownRemaining(who);
            if (wait.isPresent()) {
                messages.send(actor::send, MessageKey.TOWN_SPAWN_COOLDOWN,
                        MessageService.value("remaining",
                                net.riftbreaker.rifttowny.paper.spawn.SpawnCooldown
                                        .describe(wait.get())));
                return;
            }
            // A named town travels to that town's spawn, which needs it to be public unless the
            // traveller lives there. No name means their own, which is the common case and stays
            // one command.
            if (!args.isEmpty()) {
                directory.factsNamed(args.getFirst()).ifPresentOrElse(
                        facts -> travelTo(actor, who, facts.town(),
                                spawns.travelToPublicSpawn(who, facts.id())),
                        () -> denied(actor, ChangeDenial.TOWN_NOT_FOUND));
                return;
            }
            withTown(actor, (ignored, town) ->
                    travelTo(actor, who, town, spawns.travelTo(who, town.id())));
        });
    }

    /**
     * The journey itself, once somewhere to go has been resolved.
     *
     * <p>Shared by the two spawn paths so the warmup, the arrival notice and the fare cannot be
     * applied to one and forgotten for the other.</p>
     */
    private void travelTo(
            final CommandActor actor,
            final ResidentId who,
            final Town town,
            final CompletableFuture<ServiceResult<
                    net.riftbreaker.rifttowny.domain.territory.SpawnPoint>> pending
    ) {
        reply(actor, pending, destination -> {
            if (!teleports.warmup().isZero()) {
                messages.send(actor::send, MessageKey.TOWN_SPAWN_WARMUP,
                        MessageService.value("seconds", teleports.warmup().toSeconds()));
            }
            then(actor, teleports.travel(who, destination), outcome -> {
                announceArrival(actor, town, outcome);
                if (outcome == net.riftbreaker.rifttowny.paper.spawn.TeleportService
                        .Outcome.ARRIVED) {
                    // The fare is taken for a journey that happened. A warmup cancelled by a punch
                    // costs nothing, exactly as it costs no cooldown.
                    then(actor, spawns.chargeForTravel(who, town.id()), charged ->
                            charged.value()
                                    .filter(fare -> !fare.isZero())
                                    .ifPresent(fare -> messages.send(actor::send,
                                            MessageKey.TOWN_SPAWN_FARE,
                                            MessageService.value("amount", fare.describe()))));
                }
            });
        });
    }

    private void announceArrival(
            final CommandActor actor,
            final Town town,
            final net.riftbreaker.rifttowny.paper.spawn.TeleportService.Outcome outcome
    ) {
        switch (outcome) {
            case ARRIVED -> messages.send(actor::send, MessageKey.TOWN_SPAWN_ARRIVED,
                    MessageService.value("town", town.name().display()));
            case CANCELLED_MOVED ->
                    messages.send(actor::send, MessageKey.TOWN_SPAWN_CANCELLED_MOVED);
            case CANCELLED_DAMAGED ->
                    messages.send(actor::send, MessageKey.TOWN_SPAWN_CANCELLED_DAMAGED);
            // Superseded and gone-offline both mean the player is no longer waiting on this one,
            // and saying so would be a message about a teleport they have already replaced or left.
            case SUPERSEDED, NOT_ONLINE -> { }
            case NO_DESTINATION -> messages.send(actor::send, MessageKey.TOWN_SPAWN_FAILED);
        }
    }

    private void setSpawn(final CommandActor actor, final List<String> args) {
        final var here = actor.position();
        if (here.isEmpty()) {
            messages.send(actor::send, MessageKey.TOWN_CONSOLE_HAS_NO_CHUNK);
            return;
        }
        withTown(actor, (who, town) ->
                reply(actor, spawns.set(who, town.id(), here.get()), set ->
                        messages.send(actor::send, MessageKey.TOWN_SPAWN_SET,
                                MessageService.value("town", town.name().display()),
                                MessageService.value("position", set.describe()))));
    }

    private void deleteSpawn(final CommandActor actor, final List<String> args) {
        withTown(actor, (who, town) ->
                reply(actor, spawns.clear(who, town.id()), ignored ->
                        messages.send(actor::send, MessageKey.TOWN_SPAWN_CLEARED,
                                MessageService.value("town", town.name().display()))));
    }

    /**
     * Rebuilds the fallen town the player is standing in.
     *
     * <p>Takes no name: the town comes back as itself. What does not come back is its population —
     * the residents, roles and treasury belonged to people who have moved on.</p>
     */
    private void reclaim(final CommandActor actor, final List<String> args) {
        player(actor).ifPresent(who -> whereTheyStand(actor, chunk -> {
            final var ruin = ruins.at(chunk);
            if (ruin.isEmpty()) {
                denied(actor, ChangeDenial.NOT_A_RUIN);
                return;
            }
            reply(actor, ruins.reclaim(who, actor.name(), chunk), town ->
                    messages.send(actor::send, MessageKey.TOWN_RECLAIMED,
                            MessageService.value("town", town.name().display())));
        }));
    }

    // --- flag actions --------------------------------------------------------------------------

    /** What this town, or this chunk, has been told. Not what resolves — that is a different sum. */
    private void flagList(final CommandActor actor, final List<String> args) {
        final boolean here = !args.isEmpty() && args.getFirst().equalsIgnoreCase("here");
        if (!here) {
            withTown(actor, (who, town) ->
                    showOverrides(actor, FlagTarget.organisation(town.id()), town.name().display()));
            return;
        }
        whereTheyStand(actor, chunk ->
                showOverrides(actor, FlagTarget.claim(chunk), "chunk " + describe(chunk)));
    }

    private void showOverrides(
            final CommandActor actor, final FlagTarget target, final String label) {
        then(actor, flags.of(target), found -> {
            if (found.isEmpty()) {
                messages.send(actor::send, MessageKey.FLAG_LIST_EMPTY,
                        MessageService.value("target", label));
                return;
            }
            messages.send(actor::send, MessageKey.FLAG_LIST_HEADER,
                    MessageService.value("target", label));
            for (final FlagOverride override : found) {
                messages.sendRaw(actor::send, MessageKey.FLAG_LIST_LINE,
                        MessageService.value("flag", override.flag()),
                        MessageService.value("relationship", override.relationship()),
                        MessageService.value("state", override.allowed() ? "allowed" : "denied"));
            }
        });
    }

    private void flagSet(final CommandActor actor, final List<String> args, final boolean here) {
        final String usage = here
                ? "town flag here <flag> <relationship> <allow|deny|clear>"
                : "town flag set <flag> <relationship> <allow|deny>";
        if (args.size() < 3) {
            usage(actor, usage);
            return;
        }
        parseFlag(actor, args.getFirst()).ifPresent(flag ->
                parseRelationship(actor, args.get(1)).ifPresent(relationship -> {
                    final Optional<Boolean> allowed = parseDecision(args.get(2));
                    if (allowed.isEmpty()) {
                        usage(actor, usage);
                        return;
                    }
                    if (here) {
                        whereTheyStand(actor, chunk -> withTown(actor, (who, town) -> reply(actor,
                                flags.setForClaim(who, town.id(), chunk, flag, relationship,
                                        allowed.get()),
                                stored -> announce(actor, stored))));
                        return;
                    }
                    withTown(actor, (who, town) -> reply(actor,
                            flags.setForTown(who, town.id(), flag, relationship, allowed.get()),
                            stored -> announce(actor, stored)));
                }));
    }

    private void flagClear(final CommandActor actor, final List<String> args, final boolean here) {
        final String usage = here
                ? "town flag here <flag> <relationship> clear"
                : "town flag clear <flag> <relationship>";
        if (args.size() < 2) {
            usage(actor, usage);
            return;
        }
        parseFlag(actor, args.getFirst()).ifPresent(flag ->
                parseRelationship(actor, args.get(1)).ifPresent(relationship -> {
                    if (here) {
                        whereTheyStand(actor, chunk -> withTown(actor, (who, town) -> reply(actor,
                                flags.clearForClaim(who, town.id(), chunk, flag, relationship),
                                target -> announceCleared(actor, target, flag, relationship))));
                        return;
                    }
                    withTown(actor, (who, town) -> reply(actor,
                            flags.clearForTown(who, town.id(), flag, relationship),
                            target -> announceCleared(actor, target, flag, relationship)));
                }));
    }

    /** {@code here} takes the same arguments as {@code set}, plus {@code clear} as a decision. */
    private void flagHere(final CommandActor actor, final List<String> args) {
        if (args.size() >= 3 && args.get(2).equalsIgnoreCase("clear")) {
            flagClear(actor, args, true);
            return;
        }
        flagSet(actor, args, true);
    }

    private void announce(final CommandActor actor, final FlagOverride stored) {
        messages.send(actor::send, MessageKey.FLAG_SET,
                MessageService.value("flag", stored.flag()),
                MessageService.value("relationship", stored.relationship()),
                MessageService.value("state", stored.allowed() ? "allowed" : "denied"),
                MessageService.value("scope", scopeLabel(stored.source())));

        // Said here because here is where somebody can act on it. The ladder runs from outlaw up
        // to plot holder, and a setting that allows something at a lower rung than it denies it at
        // a higher one is almost always a typo - the two arguments transposed, usually. Reported
        // rather than refused: it is a legitimate thing to want, just rarely.
        flags.firstInconsistent(stored.target()).ifPresent(inverted ->
                messages.sendRaw(actor::send, MessageKey.FLAG_LADDER_INVERTED,
                        MessageService.value("flag", inverted)));
    }

    private void announceCleared(
            final CommandActor actor,
            final FlagTarget target,
            final ProtectionFlag flag,
            final Relationship relationship
    ) {
        messages.send(actor::send, MessageKey.FLAG_CLEARED,
                MessageService.value("flag", flag),
                MessageService.value("relationship", relationship),
                MessageService.value("scope", scopeLabel(target.source())));
    }

    private static String scopeLabel(final FlagSource source) {
        return source == FlagSource.CLAIM ? "this chunk" : "town-wide";
    }

    /**
     * Reads a flag name, refusing the ones a town does not get to set.
     *
     * <p>A {@code SYSTEM} flag is reported as unknown rather than as refused, because from the
     * town's side it is: those are granted by the subsystem that owns them, and naming one in the
     * refusal would advertise a lever that does not exist for the person reading it.</p>
     */
    private Optional<ProtectionFlag> parseFlag(final CommandActor actor, final String raw) {
        final Optional<ProtectionFlag> flag =
                ProtectionFlag.parse(raw).filter(ProtectionFlag::configurable);
        if (flag.isEmpty()) {
            messages.send(actor::send, MessageKey.FLAG_UNKNOWN,
                    MessageService.value("input", raw),
                    MessageService.value("options", String.join(", ", settableFlagNames())));
        }
        return flag;
    }

    private static List<String> settableFlagNames() {
        return ProtectionFlag.settable().stream()
                .map(flag -> flag.name().toLowerCase(Locale.ROOT))
                .toList();
    }

    private Optional<Relationship> parseRelationship(final CommandActor actor, final String raw) {
        final Optional<Relationship> relationship = Relationship.parse(raw);
        if (relationship.isEmpty()) {
            messages.send(actor::send, MessageKey.FLAG_UNKNOWN_RELATIONSHIP,
                    MessageService.value("input", raw),
                    MessageService.value("options", names(Relationship.values())));
        }
        return relationship;
    }

    /** {@code allow} or {@code deny}. Anything else is a usage error rather than a guess. */
    private static Optional<Boolean> parseDecision(final String raw) {
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "allow", "allowed", "true", "on", "yes" -> Optional.of(true);
            case "deny", "denied", "false", "off", "no" -> Optional.of(false);
            default -> Optional.empty();
        };
    }

    private static String names(final Enum<?>[] values) {
        return String.join(", ", lowerNames(values));
    }

    private static List<String> completeFlagArguments(
            final CommandActor actor, final List<String> args) {
        return switch (args.size()) {
            case 0, 1 -> settableFlagNames();
            case 2 -> lowerNames(Relationship.values());
            case 3 -> List.of("allow", "deny", "clear");
            default -> List.of();
        };
    }

    private static List<String> lowerNames(final Enum<?>[] values) {
        final List<String> names = new ArrayList<>(values.length);
        for (final Enum<?> value : values) {
            names.add(value.name().toLowerCase(Locale.ROOT));
        }
        return List.copyOf(names);
    }

    // --- plumbing ------------------------------------------------------------------------------

    /** The actor as a player, complaining if they are the console. */
    private Optional<ResidentId> player(final CommandActor actor) {
        final Optional<ResidentId> who = actor.resident();
        if (who.isEmpty()) {
            messages.send(actor::send, MessageKey.COMMAND_PLAYER_ONLY);
        }
        return who;
    }

    /** Loads the actor's town, or says they have none. */
    private void withTown(final CommandActor actor, final BiConsumer<ResidentId, Town> work) {
        player(actor).ifPresent(who -> then(actor, residents.find(who), resident -> {
            final Optional<TownId> townId = resident.flatMap(Resident::town);
            if (townId.isEmpty()) {
                messages.send(actor::send, MessageKey.TOWN_NOT_IN_A_TOWN);
                return;
            }
            then(actor, townRepository.find(townId.get()), town ->
                    town.ifPresentOrElse(
                            found -> work.accept(who, found),
                            () -> denied(actor, ChangeDenial.TOWN_NOT_FOUND)));
        }));
    }

    private void withTownAndTarget(
            final CommandActor actor,
            final List<String> args,
            final String usage,
            final TargetWork work
    ) {
        if (args.isEmpty()) {
            usage(actor, usage);
            return;
        }
        withTown(actor, (who, town) -> then(actor, residents.findByName(args.getFirst()), target ->
                target.ifPresentOrElse(
                        found -> work.accept(who, town, found.id()),
                        () -> denied(actor, ChangeDenial.RESIDENT_NOT_FOUND))));
    }

    private void withRoleAndTarget(
            final CommandActor actor,
            final List<String> args,
            final String usage,
            final RoleWork work
    ) {
        if (args.size() < 2) {
            usage(actor, usage);
            return;
        }
        withTownAndTarget(actor, args, usage, (who, town, target) ->
                then(actor, roles.list(town.id()), found -> byName(found, args.get(1))
                        .ifPresentOrElse(
                                role -> work.accept(who, town, target, role),
                                () -> denied(actor, ChangeDenial.ROLE_NOT_FOUND))));
    }

    /**
     * Consumes a lookup future, reporting a failure instead of losing it.
     *
     * <p>A bare {@code thenAccept} discards the future it returns, so a storage failure completes
     * that future exceptionally and nothing ever looks at it: the player sees no reply at all and
     * the console logs nothing. Every lookup in this class goes through here, and so does every
     * exception thrown inside the consumer body, which would otherwise vanish the same way.</p>
     */
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

    private void fail(final CommandActor actor, final Throwable failure) {
        net.riftbreaker.rifttowny.paper.RiftTownyPlugin.getInstance().getLogger()
                .log(java.util.logging.Level.WARNING, "Command failed for " + actor.name(), failure);
        messages.send(actor::send, MessageKey.COMMAND_FAILED);
    }

    /**
     * Renders a service result.
     *
     * <p>Also the only place a failed future is reported. Without it a database error would complete
     * the future exceptionally and the player would simply see nothing happen.</p>
     */
    private <T> void reply(
            final CommandActor actor,
            final CompletableFuture<ServiceResult<T>> pending,
            final Consumer<T> onSuccess
    ) {
        pending.whenComplete((result, failure) -> {
            if (failure != null) {
                // Reported as a failure rather than a refusal: "you may not do that" would be a lie
                // when the database is down, and silence would look like the command did nothing.
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

    private void denied(final CommandActor actor, final ChangeDenial denial) {
        messages.send(actor::send, MessageKey.COMMAND_DENIED,
                MessageService.value("reason", denials.of(denial)));
    }

    private void usage(final CommandActor actor, final String usage) {
        messages.send(actor::send, MessageKey.COMMAND_USAGE, MessageService.value("usage", '/' + usage));
    }

    private void line(final CommandActor actor, final String label, final String value) {
        messages.sendRaw(actor::send, MessageKey.TOWN_INFO_LINE,
                MessageService.value("label", label), MessageService.value("value", value));
    }

    private static Optional<Role> byName(final List<Role> roles, final String name) {
        final String normalised = name.toLowerCase(Locale.ROOT);
        return roles.stream().filter(role -> role.nameNormalised().equals(normalised)).findFirst();
    }

    private static Optional<Integer> parsePriority(final String raw) {
        try {
            return Optional.of(Integer.parseInt(raw));
        } catch (final NumberFormatException notANumber) {
            return Optional.empty();
        }
    }

    /** Every town's name, answered from the cache for the same reason as {@link #onlinePlayerNames()}. */
    private List<String> townNames() {
        return directory.townNames();
    }

    /**
     * Online players only.
     *
     * <p>Tab completion runs on the server thread while the player is still typing, so it must not
     * touch storage. Offline residents are reachable by typing the name in full, which the service
     * resolves properly.</p>
     */
    private static List<String> onlinePlayerNames() {
        final List<String> names = new ArrayList<>();
        Bukkit.getOnlinePlayers().forEach(player -> names.add(player.getName()));
        return names;
    }

    @FunctionalInterface
    private interface TargetWork {
        void accept(ResidentId actor, Town town, ResidentId target);
    }

    @FunctionalInterface
    private interface RoleWork {
        void accept(ResidentId actor, Town town, ResidentId target, Role role);
    }
}
