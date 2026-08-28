package net.riftbreaker.rifttowny.paper.command;

import net.riftbreaker.rifttowny.domain.civic.TownFacts;
import net.riftbreaker.rifttowny.domain.diplomacy.Relation;
import net.riftbreaker.rifttowny.domain.directory.CivicDirectory;
import net.riftbreaker.rifttowny.domain.directory.NationSummary;
import net.riftbreaker.rifttowny.domain.directory.Page;
import net.riftbreaker.rifttowny.domain.org.ChangeDenial;
import net.riftbreaker.rifttowny.domain.org.Invitation;
import net.riftbreaker.rifttowny.domain.org.Nation;
import net.riftbreaker.rifttowny.domain.org.NationId;
import net.riftbreaker.rifttowny.domain.org.NationProfile;
import net.riftbreaker.rifttowny.domain.org.NationRepository;
import net.riftbreaker.rifttowny.domain.org.Resident;
import net.riftbreaker.rifttowny.domain.org.ResidentId;
import net.riftbreaker.rifttowny.domain.org.ResidentRepository;
import net.riftbreaker.rifttowny.domain.org.Town;
import net.riftbreaker.rifttowny.domain.org.TownId;
import net.riftbreaker.rifttowny.domain.org.TownRepository;
import net.riftbreaker.rifttowny.domain.role.Permission;
import net.riftbreaker.rifttowny.domain.role.Role;
import net.riftbreaker.rifttowny.domain.service.NationService;
import net.riftbreaker.rifttowny.domain.service.ServiceResult;
import net.riftbreaker.rifttowny.paper.command.tree.CommandActor;
import net.riftbreaker.rifttowny.paper.command.tree.CommandNode;
import net.riftbreaker.rifttowny.paper.command.tree.Surface;
import net.riftbreaker.rifttowny.paper.message.DenialText;
import net.riftbreaker.rifttowny.paper.message.MessageKey;
import net.riftbreaker.rifttowny.paper.message.MessageService;
import org.bukkit.Bukkit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * The {@code /nation} tree.
 *
 * <p>Thin, like {@code /town}: read the arguments, call the service, render the answer. Every rule —
 * who may invite, who may accept, what happens to the capital — belongs to {@link NationService},
 * because the public API reaches the same methods and a check that lived here would be one anybody
 * could walk around.</p>
 *
 * <p>The join flow is two commands on purpose, one per side. A nation runs {@code /nation invite},
 * and the town's own leadership runs {@code /nation join}. Neither alone moves a town, which is what
 * stops a nation conscripting one and stops a town letting itself into somebody else's territory.</p>
 */
public final class NationCommands {

    private final NationService nations;
    private final net.riftbreaker.rifttowny.domain.service.NationRoleService roles;
    private final net.riftbreaker.rifttowny.domain.civic.ResidentNames names;
    private final ResidentRepository residents;
    private final TownRepository towns;
    private final NationRepository nationRepository;
    private final CivicDirectory directory;
    private final net.riftbreaker.rifttowny.domain.service.DiplomacyService diplomacy;
    private final net.riftbreaker.rifttowny.domain.service.BankService banks;
    private final Listings listings;
    private final MessageService messages;
    private final DenialText denials;

    public NationCommands(
            final NationService nations,
            final net.riftbreaker.rifttowny.domain.service.NationRoleService roles,
            final net.riftbreaker.rifttowny.domain.civic.ResidentNames names,
            final ResidentRepository residents,
            final TownRepository towns,
            final NationRepository nationRepository,
            final CivicDirectory directory,
            final net.riftbreaker.rifttowny.domain.service.DiplomacyService diplomacy,
            final net.riftbreaker.rifttowny.domain.service.BankService banks,
            final MessageService messages,
            final DenialText denials
    ) {
        this.nations = Objects.requireNonNull(nations, "nations");
        this.roles = Objects.requireNonNull(roles, "roles");
        this.names = Objects.requireNonNull(names, "names");
        this.residents = Objects.requireNonNull(residents, "residents");
        this.towns = Objects.requireNonNull(towns, "towns");
        this.nationRepository = Objects.requireNonNull(nationRepository, "nationRepository");
        this.directory = Objects.requireNonNull(directory, "directory");
        this.diplomacy = Objects.requireNonNull(diplomacy, "diplomacy");
        this.banks = Objects.requireNonNull(banks, "banks");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.denials = Objects.requireNonNull(denials, "denials");
        this.listings = new Listings(messages);
    }

    /** Builds the tree. Called once at enable. */
    public CommandNode tree() {
        return CommandNode.group("nation")
                .child(CommandNode.action("new")
                        .aliases("create", "found")
                        .permission("rifttowny.nation.new")
                        .usage("nation new <name>")
                        .describedAs("Found a nation around your town")
                        .runs(this::found, Surface.CHAT))
                .child(CommandNode.action("info")
                        .permission("rifttowny.nation.info")
                        .usage("nation info [nation]")
                        .describedAs("Show a nation, or your own")
                        .runs(this::info, Surface.CHAT))
                .child(CommandNode.action("list")
                        .permission("rifttowny.nation.info")
                        .usage("nation list [page] [name|residents|land|age]")
                        .describedAs("List every nation")
                        .completer((actor, args) -> args.size() <= 1
                                ? List.of("1", "name", "residents", "land", "age")
                                : List.of("name", "residents", "land", "age"))
                        .runs(this::list, Surface.CHAT))
                .child(CommandNode.action("online")
                        .permission("rifttowny.nation.info")
                        .usage("nation online [nation]")
                        .describedAs("Who from a nation is on the server")
                        .runs(this::online, Surface.CHAT))
                .child(CommandNode.group("bank")
                        .permission("rifttowny.nation.bank")
                        .usage("nation bank")
                        .describedAs("See the nation treasury and move its money")
                        // Nested rather than flat, because /nation withdraw already means
                        // withdrawing an invitation. Two meanings on one word is how somebody
                        // aiming to cancel an offer empties the treasury instead.
                        .child(CommandNode.action("deposit")
                                .permission("rifttowny.nation.bank")
                                .usage("nation bank deposit <amount>")
                                .describedAs("Put your own money into the nation")
                                .runs(this::bankDeposit, Surface.CHAT))
                        .child(CommandNode.action("withdraw")
                                .permission("rifttowny.nation.bank")
                                .usage("nation bank withdraw <amount>")
                                .describedAs("Take money out. BANK_WITHDRAW decides who really can")
                                .runs(this::bankWithdraw, Surface.CHAT))
                        .runs(this::bank, Surface.CHAT))
                .child(CommandNode.action("invite")
                        .permission("rifttowny.nation.invite")
                        .usage("nation invite <town>")
                        .describedAs("Offer membership to a town")
                        .runs(this::invite, Surface.CHAT))
                .child(CommandNode.action("withdraw")
                        .aliases("uninvite")
                        .permission("rifttowny.nation.invite")
                        .usage("nation withdraw <town>")
                        .describedAs("Withdraw an offer")
                        .runs(this::withdraw, Surface.CHAT))
                .child(CommandNode.action("invites")
                        .permission("rifttowny.nation.info")
                        .usage("nation invites")
                        .describedAs("Show who has invited your town")
                        .runs(this::invitations, Surface.CHAT))
                .child(CommandNode.action("join")
                        .aliases("accept")
                        .permission("rifttowny.nation.join")
                        .usage("nation join <nation>")
                        .describedAs("Accept an invitation for your town")
                        .runs(this::join, Surface.CHAT))
                .child(CommandNode.action("leave")
                        .permission("rifttowny.nation.leave")
                        .usage("nation leave")
                        .describedAs("Take your town out of its nation")
                        .runs(this::leave, Surface.CHAT))
                .child(CommandNode.action("expel")
                        .aliases("kick")
                        .permission("rifttowny.nation.expel")
                        .usage("nation expel <town>")
                        .describedAs("Remove a member town")
                        .runs(this::expel, Surface.CHAT))
                .child(CommandNode.action("capital")
                        .permission("rifttowny.nation.capital")
                        .usage("nation capital <town>")
                        .describedAs("Move the capital")
                        .runs(this::capital, Surface.CHAT))
                .child(CommandNode.action("king")
                        .aliases("leader")
                        .permission("rifttowny.nation.king")
                        .usage("nation king <player>")
                        .describedAs("Hand over the crown")
                        .completer((actor, args) -> onlinePlayerNames())
                        .runs(this::king, Surface.CHAT))
                .child(CommandNode.action("rename")
                        .permission("rifttowny.nation.rename")
                        .usage("nation rename <name>")
                        .describedAs("Rename your nation")
                        .runs(this::rename, Surface.CHAT))
                .child(CommandNode.action("delete")
                        .aliases("disband")
                        .permission("rifttowny.nation.delete")
                        .usage("nation delete")
                        .describedAs("Disband your nation")
                        .runs(this::disband, Surface.CHAT))
                .child(roleTree())
                .child(settingsTree())
                .child(diplomacyTree())
                .build();
    }

    /**
     * The {@code /nation ally} and {@code /nation enemy} trees.
     *
     * <p>Two commands rather than one {@code /nation relation <kind>}, because they are not the
     * same act. Offering an alliance asks; declaring an enemy tells. The messages say so, and the
     * reply to an offer names what still has to happen.</p>
     */
    private CommandNode diplomacyTree() {
        return CommandNode.group("relations")
                .aliases("diplomacy")
                .permission("rifttowny.nation.diplomacy")
                .usage("nation relations")
                .describedAs("See and change who your nation stands with")
                .child(CommandNode.action("list")
                        .permission("rifttowny.nation.diplomacy")
                        .usage("nation relations list")
                        .describedAs("Who your nation stands with, and who stands against it")
                        .runs(this::relations, Surface.CHAT))
                .child(CommandNode.action("ally")
                        .permission("rifttowny.nation.diplomacy")
                        .usage("nation relations ally <nation>")
                        .describedAs("Offer an alliance, or complete one you were offered")
                        .completer((actor, args) -> args.size() <= 1 ? nationNames() : List.of())
                        .runs((actor, args) -> declare(actor, args, Relation.ALLY), Surface.CHAT))
                .child(CommandNode.action("enemy")
                        .permission("rifttowny.nation.diplomacy")
                        .usage("nation relations enemy <nation>")
                        .describedAs("Declare a nation an enemy")
                        .completer((actor, args) -> args.size() <= 1 ? nationNames() : List.of())
                        .runs((actor, args) -> declare(actor, args, Relation.ENEMY), Surface.CHAT))
                .child(CommandNode.action("neutral")
                        .aliases("clear")
                        .permission("rifttowny.nation.diplomacy")
                        .usage("nation relations neutral <nation>")
                        .describedAs("Take back whatever you declared about a nation")
                        .completer((actor, args) -> args.size() <= 1 ? nationNames() : List.of())
                        .runs(this::withdrawBoth, Surface.CHAT))
                .build();
    }

    // --- diplomacy -------------------------------------------------------------------------------

    private void declare(
            final CommandActor actor, final List<String> args, final Relation relation) {
        withNationAndOther(actor, args, "nation relations " + relation.name().toLowerCase(Locale.ROOT)
                + " <nation>", (who, mine, theirs) ->
                reply(actor, diplomacy.declare(who, mine.id(), relation, theirs.id()), declared ->
                        announce(actor, relation, mine, theirs)));
    }

    /**
     * Says what happened, and — for an alliance — what has not happened yet.
     *
     * <p>An offer that reported itself as an alliance would be the command lying: the other nation
     * has agreed to nothing, and its territory is not open.</p>
     */
    private void announce(
            final CommandActor actor,
            final Relation relation,
            final Nation mine,
            final Nation theirs
    ) {
        if (relation == Relation.ENEMY) {
            messages.send(actor::send, MessageKey.NATION_ENEMY_DECLARED,
                    MessageService.value("nation", theirs.name().display()));
            return;
        }
        final boolean mutual = diplomacy.book().areAllied(mine.id(), theirs.id());
        messages.send(actor::send,
                mutual ? MessageKey.NATION_ALLIANCE_SEALED : MessageKey.NATION_ALLIANCE_OFFERED,
                MessageService.value("nation", theirs.name().display()));
    }

    /** Takes back whichever declaration stands, so one command undoes either. */
    private void withdrawBoth(final CommandActor actor, final List<String> args) {
        withNationAndOther(actor, args, "nation relations neutral <nation>", (who, mine, theirs) -> {
            final Relation standing = diplomacy.book()
                    .hasDeclared(mine.id(), Relation.ENEMY, theirs.id())
                    ? Relation.ENEMY
                    : Relation.ALLY;
            reply(actor, diplomacy.withdraw(who, mine.id(), standing, theirs.id()), withdrawn ->
                    messages.send(actor::send, MessageKey.NATION_RELATION_WITHDRAWN,
                            MessageService.value("nation", theirs.name().display())));
        });
    }

    private void relations(final CommandActor actor, final List<String> args) {
        withNation(actor, (who, nation) -> then(actor, diplomacy.involving(nation.id()), all -> {
            messages.send(actor::send, MessageKey.NATION_RELATIONS_HEADER,
                    MessageService.value("nation", nation.name().display()));

            line(actor, "Allies", namesOf(diplomacy.book().allies(nation.id())));
            line(actor, "Offered", namesOf(diplomacy.book().offeredAlliances(nation.id())));
            line(actor, "Enemies", namesOf(diplomacy.book().declared(nation.id(), Relation.ENEMY)));

            // Who has declared something about US, which is the half a nation cannot see from its
            // own declarations - and the half that matters when somebody declares war.
            final java.util.Set<NationId> against = new java.util.LinkedHashSet<>();
            all.forEach(declaration -> {
                if (declaration.target().equals(nation.id())
                        && declaration.relation() == Relation.ENEMY) {
                    against.add(declaration.declarer());
                }
            });
            line(actor, "Declared against you", namesOf(against));
        }));
    }

    private String namesOf(final java.util.Collection<NationId> ids) {
        final List<String> named = new java.util.ArrayList<>(ids.size());
        ids.forEach(id -> directory.nationName(id).ifPresent(named::add));
        return named.isEmpty() ? "none" : String.join(", ", named);
    }

    /** Resolves the actor's own nation and the one they named. */
    private void withNationAndOther(
            final CommandActor actor,
            final List<String> args,
            final String usage,
            final OtherNationWork work
    ) {
        if (args.isEmpty()) {
            usage(actor, usage);
            return;
        }
        withNation(actor, (who, mine) ->
                then(actor, nationRepository.findByName(args.getFirst()), found ->
                        found.ifPresentOrElse(
                                theirs -> work.accept(who, mine, theirs),
                                () -> denied(actor, ChangeDenial.NATION_NOT_FOUND))));
    }

    /** Every nation's name, from the cache, for completion. */
    private List<String> nationNames() {
        final List<String> named = new java.util.ArrayList<>();
        directory.cachedNations().forEach(nation -> named.add(nation.name().display()));
        return named;
    }

    @FunctionalInterface
    private interface OtherNationWork {
        void accept(ResidentId actor, Nation mine, Nation theirs);
    }

    /**
     * The {@code /nation set} tree.
     *
     * <p>The same four settings a town has minus the two that mean nothing here. A nation cannot be
     * open — towns join by invitation on both sides, so there is no door to leave unlocked — and it
     * has no spawn to make public.</p>
     */
    private CommandNode settingsTree() {
        return CommandNode.group("set")
                .permission("rifttowny.nation.set")
                .usage("nation set")
                .describedAs("Change what your nation says about itself")
                .child(CommandNode.action("board")
                        .permission("rifttowny.nation.set")
                        .usage("nation set board <text|clear>")
                        .describedAs("A message for your member towns")
                        .runs((actor, args) -> setText(actor, args, "nation set board <text|clear>",
                                MessageKey.NATION_SET_BOARD, NationProfile::withBoard), Surface.CHAT))
                .child(CommandNode.action("tag")
                        .permission("rifttowny.nation.set")
                        .usage("nation set tag <text|clear>")
                        .describedAs("A short abbreviation for your nation")
                        .runs((actor, args) -> setText(actor, args, "nation set tag <text|clear>",
                                MessageKey.NATION_SET_TAG, NationProfile::withTag), Surface.CHAT))
                .child(CommandNode.action("colour")
                        .aliases("color", "mapcolor", "mapcolour")
                        .permission("rifttowny.nation.set")
                        .usage("nation set colour <#a1b2c3|clear>")
                        .describedAs("How your nation is drawn on a map")
                        .completer((actor, args) -> List.of("clear"))
                        .runs(this::setColour, Surface.CHAT))
                .child(CommandNode.action("neutral")
                        .aliases("peaceful")
                        .permission("rifttowny.nation.set")
                        .usage("nation set neutral <on|off>")
                        .describedAs("Declare your nation neutral in war")
                        .completer((actor, args) -> List.of("on", "off"))
                        .runs(this::setNeutral, Surface.CHAT))
                .build();
    }

    // --- settings ------------------------------------------------------------------------------

    private void setText(
            final CommandActor actor,
            final List<String> args,
            final String usage,
            final MessageKey confirmation,
            final java.util.function.BiFunction<NationProfile, String, NationProfile> change
    ) {
        if (args.isEmpty()) {
            usage(actor, usage);
            return;
        }
        final boolean clearing = args.size() == 1 && "clear".equalsIgnoreCase(args.getFirst());
        final String value = clearing ? "" : String.join(" ", args);

        withNation(actor, (who, nation) -> reply(actor,
                nations.setProfile(who, nation.id(), profile -> change.apply(profile, value)),
                updated -> messages.send(actor::send, confirmation,
                        MessageService.value("nation", updated.name().display()),
                        MessageService.value("value", value.isEmpty() ? "nothing" : value))));
    }

    private void setColour(final CommandActor actor, final List<String> args) {
        if (args.isEmpty()) {
            usage(actor, "nation set colour <#a1b2c3|clear>");
            return;
        }
        final String raw = args.getFirst();
        final boolean clearing = "clear".equalsIgnoreCase(raw) || "none".equalsIgnoreCase(raw);
        final Optional<net.riftbreaker.rifttowny.domain.org.MapColour> colour = clearing
                ? Optional.empty()
                : net.riftbreaker.rifttowny.domain.org.MapColour.parse(raw);
        if (!clearing && colour.isEmpty()) {
            denied(actor, ChangeDenial.NOT_A_COLOUR);
            return;
        }
        withNation(actor, (who, nation) -> reply(actor,
                nations.setProfile(who, nation.id(), profile -> profile.withColour(colour.orElse(null))),
                updated -> messages.send(actor::send, MessageKey.NATION_SET_COLOUR,
                        MessageService.value("nation", updated.name().display()),
                        MessageService.value("value", updated.profile().mapColour()
                                .map(net.riftbreaker.rifttowny.domain.org.MapColour::hashHex)
                                .orElse("the default")))));
    }

    private void setNeutral(final CommandActor actor, final List<String> args) {
        if (args.isEmpty()) {
            usage(actor, "nation set neutral <on|off>");
            return;
        }
        final Optional<Boolean> decision = parseSwitch(args.getFirst());
        if (decision.isEmpty()) {
            usage(actor, "nation set neutral <on|off>");
            return;
        }
        withNation(actor, (who, nation) -> reply(actor,
                nations.setProfile(who, nation.id(), profile -> profile.withNeutral(decision.get())),
                updated -> messages.send(actor::send, MessageKey.NATION_SET_NEUTRAL,
                        MessageService.value("nation", updated.name().display()),
                        MessageService.value("state", decision.get() ? "on" : "off"))));
    }

    /** {@code on} or {@code off}, generously. Anything else is a usage error rather than a guess. */
    private static Optional<Boolean> parseSwitch(final String raw) {
        return switch (raw.toLowerCase(java.util.Locale.ROOT)) {
            case "on", "true", "yes", "enable", "enabled" -> Optional.of(true);
            case "off", "false", "no", "disable", "disabled" -> Optional.of(false);
            default -> Optional.empty();
        };
    }

    /**
     * The {@code /nation role} tree.
     *
     * <p>The same shape as {@code /town role}, because the rules behind it are the same ones. The
     * one difference a player can see is who a role may be given to: a nation's roles go to citizens
     * of its member towns, not to its own residents, because it has none.</p>
     */
    private CommandNode roleTree() {
        return CommandNode.group("role")
                .permission("rifttowny.nation.role.view")
                .describedAs("Manage nation roles")
                .usage("nation role")
                .child(CommandNode.action("list")
                        // Repeated from the parent deliberately; see TownCommands.roleTree.
                        .permission("rifttowny.nation.role.view")
                        .usage("nation role list")
                        .describedAs("List the nation's roles")
                        .runs(this::roleList, Surface.CHAT))
                .child(CommandNode.action("new")
                        .aliases("create")
                        .permission("rifttowny.nation.role.manage")
                        .usage("nation role new <name> <priority>")
                        .describedAs("Create a role")
                        .runs(this::roleCreate, Surface.CHAT))
                .child(CommandNode.action("delete")
                        .permission("rifttowny.nation.role.manage")
                        .usage("nation role delete <name>")
                        .describedAs("Delete a role")
                        .runs(this::roleDelete, Surface.CHAT))
                .child(CommandNode.action("assign")
                        .aliases("give")
                        .permission("rifttowny.nation.role.assign")
                        .usage("nation role assign <player> <role>")
                        .describedAs("Give a citizen a role")
                        .completer((actor, args) -> args.size() <= 1 ? onlinePlayerNames() : List.of())
                        .runs(this::roleAssign, Surface.CHAT))
                .child(CommandNode.action("unassign")
                        .aliases("take")
                        .permission("rifttowny.nation.role.assign")
                        .usage("nation role unassign <player> <role>")
                        .describedAs("Take a role away")
                        .completer((actor, args) -> args.size() <= 1 ? onlinePlayerNames() : List.of())
                        .runs(this::roleUnassign, Surface.CHAT))
                .child(CommandNode.action("grant")
                        .aliases("allow")
                        .permission("rifttowny.nation.role.manage")
                        .usage("nation role grant <role> <permission>")
                        .describedAs("Let a role do something")
                        .completer((actor, args) -> args.size() <= 1
                                ? List.of()
                                : permissionNameList())
                        .runs(this::roleGrant, Surface.CHAT))
                .child(CommandNode.action("revoke")
                        .aliases("deny")
                        .permission("rifttowny.nation.role.manage")
                        .usage("nation role revoke <role> <permission>")
                        .describedAs("Stop a role doing something")
                        .completer((actor, args) -> args.size() <= 1
                                ? List.of()
                                : permissionNameList())
                        .runs(this::roleRevoke, Surface.CHAT))
                .child(CommandNode.action("priority")
                        .aliases("rank")
                        .permission("rifttowny.nation.role.manage")
                        .usage("nation role priority <role> <number>")
                        .describedAs("Move a role in the ranking")
                        .completer((actor, args) -> List.of())
                        .runs(this::rolePriority, Surface.CHAT))
                .child(CommandNode.action("clone")
                        .aliases("copy")
                        .permission("rifttowny.nation.role.manage")
                        .usage("nation role clone <role> <new name> <priority>")
                        .describedAs("Copy a role under a new name")
                        .completer((actor, args) -> List.of())
                        .runs(this::roleClone, Surface.CHAT))
                .child(CommandNode.action("rename")
                        .permission("rifttowny.nation.role.manage")
                        .usage("nation role rename <role> <new name>")
                        .describedAs("Rename a role")
                        .completer((actor, args) -> List.of())
                        .runs(this::roleRename, Surface.CHAT))
                .child(CommandNode.action("set")
                        .aliases("label")
                        .permission("rifttowny.nation.role.manage")
                        .usage("nation role set <role> <display|icon|prefix> <value|clear>")
                        .describedAs("Set a role's label, icon or chat prefix")
                        .completer((actor, args) -> args.size() == 2
                                ? List.of("display", "icon", "prefix")
                                : List.of())
                        .runs(this::roleSet, Surface.CHAT))
                .build();
    }

    // --- actions -------------------------------------------------------------------------------

    private void found(final CommandActor actor, final List<String> args) {
        if (args.isEmpty()) {
            usage(actor, "nation new <name>");
            return;
        }
        withTown(actor, (who, town) ->
                reply(actor, nations.found(who, town.id(), args.getFirst()), nation ->
                        messages.send(actor::send, MessageKey.NATION_FOUNDED,
                                MessageService.value("nation", nation.name().display()))));
    }


    /**
     * Who from a nation is on the server, named or your own.
     *
     * <p>Grouped by nothing and labelled by town, because a nation is towns rather than people: the
     * useful question when a king types this is not only how many are on, but which member towns
     * they are on <em>from</em>. A nation with forty online and all of them in one town is a
     * different situation from forty spread across eight.</p>
     *
     * <p>Read entirely from the caches, like the rest of the listing surface, so a nation of a
     * thousand costs the same as a nation of ten and nobody can stall the server by asking.</p>
     */
    private void online(final CommandActor actor, final List<String> args) {
        if (!args.isEmpty()) {
            then(actor, nationRepository.findByName(args.getFirst()), found -> found.ifPresentOrElse(
                    nation -> showOnline(actor, nation),
                    () -> denied(actor, ChangeDenial.NATION_NOT_FOUND)));
            return;
        }
        withNation(actor, (who, nation) -> showOnline(actor, nation));
    }

    private void showOnline(final CommandActor actor, final Nation nation) {
        final List<ResidentId> here = new ArrayList<>();
        final Map<ResidentId, String> homes = new HashMap<>();
        int residents = 0;
        for (final TownId town : nation.towns()) {
            final Optional<TownFacts> facts = directory.facts(town);
            if (facts.isEmpty()) {
                continue;
            }
            residents += facts.get().town().residentCount();
            for (final ResidentId resident : directory.residentsOf(town)) {
                if (Bukkit.getPlayer(resident.value()) != null) {
                    here.add(resident);
                    homes.put(resident, facts.get().displayName());
                }
            }
        }
        if (here.isEmpty()) {
            messages.send(actor::send, MessageKey.NATION_ONLINE_NONE,
                    MessageService.value("nation", nation.name().display()));
            return;
        }
        messages.sendRaw(actor::send, MessageKey.NATION_ONLINE_HEADER,
                MessageService.value("nation", nation.name().display()),
                MessageService.value("count", here.size()),
                MessageService.value("residents", residents));
        here.sort(java.util.Comparator
                .comparing((ResidentId who) -> homes.get(who).toLowerCase(java.util.Locale.ROOT))
                .thenComparing(who -> names.describe(who).toLowerCase(java.util.Locale.ROOT)));
        for (final ResidentId resident : here) {
            messages.sendRaw(actor::send, MessageKey.NATION_ONLINE_LINE,
                    MessageService.value("resident", names.describe(resident)),
                    MessageService.value("town", homes.get(resident)));
        }
    }

    /**
     * The nation treasury.
     *
     * <p>The same screen as a town's, deliberately: a king who has run a town already knows how to
     * read this one. What differs is where the money comes from — a town's treasury is mostly
     * deposits, and a nation's is mostly the nation share of its member towns' tax.</p>
     */
    private void bank(final CommandActor actor, final List<String> args) {
        withNation(actor, (who, nation) -> then(actor, banks.balanceOf(nation.id()), balance -> {
            messages.send(actor::send, MessageKey.NATION_BANK_HEADER,
                    MessageService.value("nation", nation.name().display()),
                    MessageService.value("balance", balance.describe()));
            if (!banks.economyAvailable()) {
                messages.sendRaw(actor::send, MessageKey.TOWN_BANK_NO_ECONOMY);
            }
            then(actor, banks.historyOf(nation.id(),
                    net.riftbreaker.rifttowny.domain.service.BankService.DEFAULT_HISTORY),
                    history -> {
                        if (history.isEmpty()) {
                            messages.sendRaw(actor::send, MessageKey.TOWN_BANK_NO_HISTORY);
                            return;
                        }
                        for (final var entry : history) {
                            messages.sendRaw(actor::send, MessageKey.TOWN_BANK_LINE,
                                    MessageService.value("movement", entry.describe()),
                                    MessageService.value("by",
                                            entry.author().map(names::describe)
                                                    .orElse("the server")));
                        }
                    });
        }));
    }

    private void bankDeposit(final CommandActor actor, final List<String> args) {
        withAmount(actor, args, "nation bank deposit <amount>", (who, nation, amount) ->
                reply(actor, banks.deposit(who, nation.id(), amount), balance ->
                        messages.send(actor::send, MessageKey.NATION_BANK_DEPOSITED,
                                MessageService.value("amount", amount.describe()),
                                MessageService.value("balance", balance.describe()))));
    }

    private void bankWithdraw(final CommandActor actor, final List<String> args) {
        withAmount(actor, args, "nation bank withdraw <amount>", (who, nation, amount) ->
                reply(actor, banks.withdraw(who, nation.id(), amount), balance ->
                        messages.send(actor::send, MessageKey.NATION_BANK_WITHDREW,
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
        withNation(actor, (who, nation) -> work.accept(who, nation, amount.get()));
    }

    @FunctionalInterface
    private interface AmountWork {
        void accept(ResidentId who, Nation nation, net.riftbreaker.rifttowny.domain.bank.Money amount);
    }
    private void info(final CommandActor actor, final List<String> args) {
        if (!args.isEmpty()) {
            then(actor, nationRepository.findByName(args.getFirst()), found -> found.ifPresentOrElse(
                    nation -> show(actor, nation),
                    () -> denied(actor, ChangeDenial.NATION_NOT_FOUND)));
            return;
        }
        withNation(actor, (who, nation) -> show(actor, nation));
    }

    /**
     * One nation.
     *
     * <p>Its people and land are summed through its member towns from the caches, because the nation
     * itself owns neither — a stored total would be a second copy of a number that changes every
     * time anybody in it claims a chunk.</p>
     */
    private void show(final CommandActor actor, final Nation nation) {
        messages.send(actor::send, MessageKey.NATION_INFO_HEADER,
                MessageService.value("nation", nation.name().display()));
        line(actor, "Leader", names.describe(nation.leader()));
        line(actor, "Founded", net.riftbreaker.rifttowny.paper.message.Times.date(nation.createdAt()));
        line(actor, "Towns", String.valueOf(nation.townCount()));

        final List<NationSummary> summarised = directory.nations(List.of(nation));
        if (!summarised.isEmpty()) {
            final NationSummary summary = summarised.getFirst();
            line(actor, "Residents", String.valueOf(summary.residents()));
            line(actor, "Land", summary.chunks() + " chunk(s)");
        }

        // Read once for the screen and printed in its callback, like the town treasury: a balance
        // is the one line here a cache cannot answer.
        then(actor, banks.balanceOf(nation.id()), balance ->
                line(actor, "Treasury", balance.describe()));
        line(actor, "Capital", directory.facts(nation.capital())
                .map(facts -> facts.displayName())
                .orElse("unknown"));
        line(actor, "Members", memberNames(nation));

        final NationProfile profile = nation.profile();
        if (profile.hasTag()) {
            line(actor, "Tag", profile.tag());
        }
        if (profile.neutral()) {
            line(actor, "Declared", "neutral");
        }
        if (profile.hasBoard()) {
            messages.sendRaw(actor::send, MessageKey.TOWN_BOARD_LINE,
                    MessageService.value("board", profile.board()));
        }
    }

    /** Every member town, named from the cache. */
    private String memberNames(final Nation nation) {
        final List<String> named = new java.util.ArrayList<>();
        nation.towns().forEach(town ->
                named.add(directory.facts(town).map(facts -> facts.displayName()).orElse("a town")));
        return named.isEmpty() ? "none" : String.join(", ", named);
    }

    /**
     * Every nation on the server.
     *
     * <p>One read for the whole listing rather than one per row. Nations are not cached — nothing on
     * a movement path asks about one — so this is the query that pays for the whole screen.</p>
     */
    private void list(final CommandActor actor, final List<String> args) {
        listings.parse(actor, args).ifPresent(request -> {
            // From memory now that nations are cached. This used to be the one listing that still
            // cost a query, and it was the query a curious player ran most often.
            final Page<NationSummary> page = directory.nations(
                    directory.cachedNations(), request.sort(), request.page(), Listings.PAGE_SIZE);
            if (page.isEmpty()) {
                messages.send(actor::send, MessageKey.NATION_LIST_EMPTY);
                return;
            }
            messages.sendRaw(actor::send, MessageKey.NATION_LIST_HEADER,
                    MessageService.value("count", page.total()),
                    MessageService.value("page", page.number()),
                    MessageService.value("pages", page.pages()),
                    MessageService.value("sort", request.sortName()));

            int index = page.firstIndex();
            for (final NationSummary summary : page.items()) {
                messages.sendRaw(actor::send, MessageKey.NATION_LIST_LINE,
                        MessageService.value("index", index++),
                        MessageService.value("nation", summary.name()),
                        MessageService.value("towns", summary.towns()),
                        MessageService.value("residents", summary.residents()),
                        MessageService.value("chunks", summary.chunks()));
            }
            listings.more(actor, page,
                    "/nation list " + (page.number() + 1) + ' ' + request.sortName());
        });
    }

    private void invite(final CommandActor actor, final List<String> args) {
        withNationAndTown(actor, args, "nation invite <town>", (who, nation, target) ->
                reply(actor, nations.invite(who, nation.id(), target.id()), invitation ->
                        messages.send(actor::send, MessageKey.NATION_INVITED,
                                MessageService.value("town", target.name().display()),
                                MessageService.value("nation", nation.name().display()))));
    }

    private void withdraw(final CommandActor actor, final List<String> args) {
        withNationAndTown(actor, args, "nation withdraw <town>", (who, nation, target) ->
                reply(actor, nations.withdraw(who, nation.id(), target.id()), ignored ->
                        messages.send(actor::send, MessageKey.NATION_INVITE_WITHDRAWN,
                                MessageService.value("town", target.name().display()))));
    }

    private void invitations(final CommandActor actor, final List<String> args) {
        withTown(actor, (who, town) -> then(actor, nations.invitationsFor(town.id()), offers -> {
            if (offers.isEmpty()) {
                messages.send(actor::send, MessageKey.NATION_NO_INVITES);
                return;
            }
            messages.send(actor::send, MessageKey.NATION_INVITES_HEADER,
                    MessageService.value("town", town.name().display()));
            for (final Invitation offer : offers) {
                then(actor, nationRepository.find(NationId.parse(offer.inviter().value().toString())),
                        nation -> messages.sendRaw(actor::send, MessageKey.NATION_INVITES_LINE,
                                MessageService.value("nation", nation
                                        .map(found -> found.name().display())
                                        .orElse(offer.inviter().value().toString())),
                                MessageService.value("expires", offer.expiresAt())));
            }
        }));
    }

    private void join(final CommandActor actor, final List<String> args) {
        if (args.isEmpty()) {
            usage(actor, "nation join <nation>");
            return;
        }
        withTown(actor, (who, town) -> then(actor, nationRepository.findByName(args.getFirst()),
                found -> found.ifPresentOrElse(
                        nation -> reply(actor, nations.accept(who, town.id(), nation.id()),
                                joined -> messages.send(actor::send, MessageKey.NATION_JOINED,
                                        MessageService.value("town", town.name().display()),
                                        MessageService.value("nation", joined.name().display()))),
                        () -> denied(actor, ChangeDenial.NATION_NOT_FOUND))));
    }

    private void leave(final CommandActor actor, final List<String> args) {
        withTown(actor, (who, town) ->
                reply(actor, nations.leave(who, town.id()), nation ->
                        messages.send(actor::send, MessageKey.NATION_LEFT,
                                MessageService.value("town", town.name().display()),
                                MessageService.value("nation", nation.name().display()))));
    }

    private void expel(final CommandActor actor, final List<String> args) {
        withNationAndTown(actor, args, "nation expel <town>", (who, nation, target) ->
                reply(actor, nations.expel(who, nation.id(), target.id()), ignored ->
                        messages.send(actor::send, MessageKey.NATION_EXPELLED,
                                MessageService.value("town", target.name().display()),
                                MessageService.value("nation", nation.name().display()))));
    }

    private void capital(final CommandActor actor, final List<String> args) {
        withNationAndTown(actor, args, "nation capital <town>", (who, nation, target) ->
                reply(actor, nations.moveCapital(who, nation.id(), target.id()), updated ->
                        messages.send(actor::send, MessageKey.NATION_CAPITAL_MOVED,
                                MessageService.value("town", target.name().display()))));
    }

    private void king(final CommandActor actor, final List<String> args) {
        if (args.isEmpty()) {
            usage(actor, "nation king <player>");
            return;
        }
        withNation(actor, (who, nation) -> then(actor, residents.findByName(args.getFirst()),
                target -> target.ifPresentOrElse(
                        found -> reply(actor,
                                nations.transferLeadership(who, nation.id(), found.id()),
                                updated -> messages.send(actor::send, MessageKey.NATION_KING_TRANSFERRED,
                                        MessageService.value("resident", args.getFirst()),
                                        MessageService.value("nation", updated.name().display()))),
                        () -> denied(actor, ChangeDenial.RESIDENT_NOT_FOUND))));
    }

    private void rename(final CommandActor actor, final List<String> args) {
        if (args.isEmpty()) {
            usage(actor, "nation rename <name>");
            return;
        }
        withNation(actor, (who, nation) ->
                reply(actor, nations.rename(who, nation.id(), args.getFirst()), updated ->
                        messages.send(actor::send, MessageKey.NATION_RENAMED,
                                MessageService.value("nation", updated.name().display()))));
    }

    private void disband(final CommandActor actor, final List<String> args) {
        withNation(actor, (who, nation) ->
                reply(actor, nations.disband(who, nation.id()), ignored ->
                        messages.send(actor::send, MessageKey.NATION_DISBANDED,
                                MessageService.value("nation", nation.name().display()))));
    }

    // --- role actions --------------------------------------------------------------------------

    private void roleList(final CommandActor actor, final List<String> args) {
        withNation(actor, (who, nation) -> then(actor, roles.list(nation.id()), found -> {
            messages.send(actor::send, MessageKey.ROLE_LIST_HEADER,
                    MessageService.value("town", nation.name().display()));
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
            usage(actor, "nation role new <name> <priority>");
            return;
        }
        final Optional<Integer> priority = parsePriority(args.get(1));
        if (priority.isEmpty()) {
            usage(actor, "nation role new <name> <priority>");
            return;
        }
        withNation(actor, (who, nation) -> reply(actor,
                roles.create(who, nation.id(), args.getFirst(), priority.get(), java.util.Set.of()),
                role -> messages.send(actor::send, MessageKey.ROLE_CREATED,
                        MessageService.value("role", role.name()))));
    }

    private void roleDelete(final CommandActor actor, final List<String> args) {
        if (args.isEmpty()) {
            usage(actor, "nation role delete <name>");
            return;
        }
        withNation(actor, (who, nation) -> then(actor, roles.list(nation.id()), found -> {
            final Optional<Role> role = byName(found, args.getFirst());
            if (role.isEmpty()) {
                denied(actor, ChangeDenial.ROLE_NOT_FOUND);
                return;
            }
            reply(actor, roles.delete(who, nation.id(), role.get().id()), ignored ->
                    messages.send(actor::send, MessageKey.ROLE_DELETED,
                            MessageService.value("role", role.get().name())));
        }));
    }

    private void roleAssign(final CommandActor actor, final List<String> args) {
        withRoleAndTarget(actor, args, "nation role assign <player> <role>",
                (who, nation, target, role) -> reply(actor,
                        roles.assign(who, nation.id(), target, role.id()), ignored ->
                                messages.send(actor::send, MessageKey.ROLE_ASSIGNED,
                                        MessageService.value("resident", args.getFirst()),
                                        MessageService.value("role", role.name()))));
    }

    private void roleUnassign(final CommandActor actor, final List<String> args) {
        withRoleAndTarget(actor, args, "nation role unassign <player> <role>",
                (who, nation, target, role) -> reply(actor,
                        roles.unassign(who, nation.id(), target, role.id()), ignored ->
                                messages.send(actor::send, MessageKey.ROLE_UNASSIGNED,
                                        MessageService.value("resident", args.getFirst()),
                                        MessageService.value("role", role.name()))));
    }


    /**
     * The five that had no command.
     *
     * <p>{@code NationRoleService} carried {@code clone}, {@code rename}, {@code reprioritise},
     * {@code grant} and {@code revoke} with no caller anywhere, so a nation could create a rank —
     * with an empty permission set, since that is what {@code new} passes — and then had no way to
     * give it a single permission, change its name, or move it in the ranking. {@code MANAGE_ROLES}
     * describes itself as "create, clone, rename, reorder and delete"; only two of those five could
     * actually be reached.</p>
     */
    private void roleGrant(final CommandActor actor, final List<String> args) {
        withRoleAndPermission(actor, args, "nation role grant <role> <permission>",
                (who, nation, role, permission) -> reply(actor,
                        roles.grant(who, nation.id(), role.id(), permission), ignored ->
                                messages.send(actor::send, MessageKey.ROLE_PERMISSION_GRANTED,
                                        MessageService.value("permission",
                                                permission.name().toLowerCase(java.util.Locale.ROOT)),
                                        MessageService.value("role", role.name()))));
    }

    private void roleRevoke(final CommandActor actor, final List<String> args) {
        withRoleAndPermission(actor, args, "nation role revoke <role> <permission>",
                (who, nation, role, permission) -> reply(actor,
                        roles.revoke(who, nation.id(), role.id(), permission), ignored ->
                                messages.send(actor::send, MessageKey.ROLE_PERMISSION_REVOKED,
                                        MessageService.value("permission",
                                                permission.name().toLowerCase(java.util.Locale.ROOT)),
                                        MessageService.value("role", role.name()))));
    }

    private void rolePriority(final CommandActor actor, final List<String> args) {
        if (args.size() < 2) {
            usage(actor, "nation role priority <role> <number>");
            return;
        }
        final Optional<Integer> priority = parsePriority(args.get(1));
        if (priority.isEmpty()) {
            usage(actor, "nation role priority <role> <number>");
            return;
        }
        withRole(actor, args, (who, nation, role) -> reply(actor,
                roles.reprioritise(who, nation.id(), role.id(), priority.get()), ignored ->
                        messages.send(actor::send, MessageKey.ROLE_REPRIORITISED,
                                MessageService.value("role", role.name()),
                                MessageService.value("priority", String.valueOf(priority.get())))));
    }

    private void roleClone(final CommandActor actor, final List<String> args) {
        if (args.size() < 3) {
            usage(actor, "nation role clone <role> <new name> <priority>");
            return;
        }
        final Optional<Integer> priority = parsePriority(args.get(2));
        if (priority.isEmpty()) {
            usage(actor, "nation role clone <role> <new name> <priority>");
            return;
        }
        withRole(actor, args, (who, nation, role) -> reply(actor,
                roles.clone(who, nation.id(), role.id(), args.get(1), priority.get()),
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
            usage(actor, "nation role set <role> <display|icon|prefix> <value|clear>");
            return;
        }
        final String field = args.get(1).toLowerCase(java.util.Locale.ROOT);
        if (!List.of("display", "icon", "prefix").contains(field)) {
            usage(actor, "nation role set <role> <display|icon|prefix> <value|clear>");
            return;
        }
        final String value = args.size() < 3
                ? null
                : String.join(" ", args.subList(2, args.size()));
        final String wanted = value == null || "clear".equalsIgnoreCase(value) ? null : value;

        withRole(actor, args, (who, nation, role) -> reply(actor,
                roles.decorate(who, nation.id(), role.id(),
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
            usage(actor, "nation role rename <role> <new name>");
            return;
        }
        withRole(actor, args, (who, nation, role) -> reply(actor,
                roles.rename(who, nation.id(), role.id(), args.get(1)), ignored ->
                        messages.send(actor::send, MessageKey.ROLE_RENAMED,
                                MessageService.value("role", role.name()),
                                MessageService.value("name", args.get(1)))));
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
        final Optional<Permission> permission = Permission.parse(args.get(1));
        if (permission.isEmpty()) {
            messages.send(actor::send, MessageKey.ROLE_UNKNOWN_PERMISSION,
                    MessageService.value("input", args.get(1)),
                    MessageService.value("options", permissionNames()));
            return;
        }
        withRole(actor, args, (who, nation, role) -> work.accept(who, nation, role, permission.get()));
    }

    /** Resolves the first argument as one of the acting nation's roles. */
    private void withRole(
            final CommandActor actor, final List<String> args, final NamedRoleWork work) {
        if (args.isEmpty()) {
            usage(actor, "nation role <action> <role> ...");
            return;
        }
        withNation(actor, (who, nation) ->
                then(actor, roles.list(nation.id()), found -> byName(found, args.getFirst())
                        .ifPresentOrElse(
                                role -> work.accept(who, nation, role),
                                () -> denied(actor, ChangeDenial.ROLE_NOT_FOUND))));
    }

    private static String permissionNames() {
        return java.util.Arrays.stream(Permission.values())
                .map(permission -> permission.name().toLowerCase(java.util.Locale.ROOT))
                .collect(java.util.stream.Collectors.joining(", "));
    }

    private static List<String> permissionNameList() {
        return java.util.Arrays.stream(Permission.values())
                .map(permission -> permission.name().toLowerCase(java.util.Locale.ROOT))
                .toList();
    }

    @FunctionalInterface
    private interface RolePermissionWork {
        void accept(ResidentId who, Nation nation, Role role, Permission permission);
    }

    @FunctionalInterface
    private interface NamedRoleWork {
        void accept(ResidentId who, Nation nation, Role role);
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
        withNation(actor, (who, nation) -> then(actor, residents.findByName(args.getFirst()),
                target -> target.ifPresentOrElse(
                        found -> then(actor, roles.list(nation.id()), all -> byName(all, args.get(1))
                                .ifPresentOrElse(
                                        role -> work.accept(who, nation, found.id(), role),
                                        () -> denied(actor, ChangeDenial.ROLE_NOT_FOUND))),
                        () -> denied(actor, ChangeDenial.RESIDENT_NOT_FOUND))));
    }

    private static Optional<Role> byName(final List<Role> roles, final String name) {
        final String normalised = name.toLowerCase(java.util.Locale.ROOT);
        return roles.stream().filter(role -> role.nameNormalised().equals(normalised)).findFirst();
    }

    private static Optional<Integer> parsePriority(final String raw) {
        try {
            return Optional.of(Integer.parseInt(raw));
        } catch (final NumberFormatException notANumber) {
            return Optional.empty();
        }
    }

    // --- plumbing ------------------------------------------------------------------------------

    private Optional<ResidentId> player(final CommandActor actor) {
        final Optional<ResidentId> who = actor.resident();
        if (who.isEmpty()) {
            messages.send(actor::send, MessageKey.COMMAND_PLAYER_ONLY);
        }
        return who;
    }

    /** The actor's town, or a complaint. Nations are reached through a town, never directly. */
    private void withTown(final CommandActor actor, final BiConsumer<ResidentId, Town> work) {
        player(actor).ifPresent(who -> then(actor, residents.find(who), resident -> {
            final Optional<TownId> townId = resident.flatMap(Resident::town);
            if (townId.isEmpty()) {
                messages.send(actor::send, MessageKey.TOWN_NOT_IN_A_TOWN);
                return;
            }
            then(actor, towns.find(townId.get()), town -> town.ifPresentOrElse(
                    found -> work.accept(who, found),
                    () -> denied(actor, ChangeDenial.TOWN_NOT_FOUND)));
        }));
    }

    /** The nation the actor's town belongs to. */
    private void withNation(final CommandActor actor, final BiConsumer<ResidentId, Nation> work) {
        withTown(actor, (who, town) -> {
            final Optional<NationId> nationId = town.nation();
            if (nationId.isEmpty()) {
                denied(actor, ChangeDenial.TOWN_NOT_IN_A_NATION);
                return;
            }
            then(actor, nationRepository.find(nationId.get()), nation -> nation.ifPresentOrElse(
                    found -> work.accept(who, found),
                    () -> denied(actor, ChangeDenial.NATION_NOT_FOUND)));
        });
    }

    private void withNationAndTown(
            final CommandActor actor,
            final List<String> args,
            final String usage,
            final TownWork work
    ) {
        if (args.isEmpty()) {
            usage(actor, usage);
            return;
        }
        withNation(actor, (who, nation) -> then(actor, towns.findByName(args.getFirst()),
                target -> target.ifPresentOrElse(
                        found -> work.accept(who, nation, found),
                        () -> denied(actor, ChangeDenial.TOWN_NOT_FOUND))));
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

    private static List<String> onlinePlayerNames() {
        final List<String> names = new java.util.ArrayList<>();
        org.bukkit.Bukkit.getOnlinePlayers().forEach(player -> names.add(player.getName()));
        return names;
    }

    @FunctionalInterface
    private interface TownWork {
        void accept(ResidentId actor, Nation nation, Town target);
    }

    @FunctionalInterface
    private interface RoleWork {
        void accept(ResidentId actor, Nation nation, ResidentId target, Role role);
    }
}
