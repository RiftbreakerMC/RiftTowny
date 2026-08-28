package net.riftbreaker.rifttowny.paper.message;

/**
 * Every user-facing string RiftTowny can print.
 *
 * <p>Each key carries its own default, so a missing or hand-edited {@code messages.yml} degrades to
 * the shipped wording rather than to a blank line or a raw key. The bundled file contains the same
 * defaults; a test keeps the two in step, and a second test proves every default is valid
 * MiniMessage — an unclosed tag would otherwise reach a player as literal markup.</p>
 */
public enum MessageKey {

    PREFIX("prefix", "<gradient:#4ea8de:#80ffdb><bold>RiftTowny</bold></gradient> <dark_gray>|</dark_gray> "),

    COMMAND_NO_PERMISSION("command.no-permission",
            "<red>You do not have permission to do that.</red>"),

    /** {@code <command>} is the tree the player was actually in, not a hard-coded one. */
    COMMAND_UNKNOWN_SUBCOMMAND("command.unknown-subcommand",
            "<red>Unknown subcommand <white><input></white>. Try <white><command></white>.</red>"),

    COMMAND_HELP_HEADER("command.help-header",
            "<gray><command></gray>"),

    COMMAND_HELP_LINE("command.help-line",
            "  <aqua><usage></aqua> <dark_gray>-</dark_gray> <gray><description></gray>"),

    COMMAND_PLAYER_ONLY("command.player-only",
            "<red>Only a player can do that.</red>"),

    COMMAND_USAGE("command.usage",
            "<red>Usage: <white><usage></white></red>"),

    /** A refusal. {@code <reason>} is filled from the {@code denial.*} section. */
    COMMAND_DENIED("command.denied",
            "<red><reason></red>"),

    COMMAND_NAME_REJECTED("command.name-rejected",
            "<red>That name will not work: <white><problems></white></red>"),

    /** Something broke rather than being refused. The cause goes to the server log, not to chat. */
    COMMAND_FAILED("command.failed",
            "<red>That did not work. The problem has been logged for an administrator.</red>"),

    TOWN_FOUNDED("town.founded",
            "<green>Founded <white><town></white>. You are its mayor.</green>"),

    TOWN_JOINED("town.joined",
            "<green><white><resident></white> joined <white><town></white>.</green>"),

    /** An offer, not a change. The wording says so, because a mayor will assume otherwise. */
    TOWN_INVITED("town.invited",
            "<green>Invited <white><resident></white> to <white><town></white>. "
                    + "They must accept before anything changes.</green>"),

    TOWN_INVITE_WITHDRAWN("town.invite-withdrawn",
            "<yellow>Withdrew the invitation to <white><resident></white>.</yellow>"),

    TOWN_INVITE_DECLINED("town.invite-declined",
            "<yellow>Turned down the invitation from <white><town></white>.</yellow>"),

    TOWN_INVITES_HEADER("town.invites-header",
            "<gray>Towns that have invited you:</gray>"),

    TOWN_INVITES_LINE("town.invites-line",
            "  <aqua><town></aqua> <dark_gray>-</dark_gray> <gray>until <expires></gray>"),

    TOWN_NO_INVITES("town.no-invites",
            "<gray>No town has invited you.</gray>"),

    TOWN_LEFT("town.left",
            "<yellow>You left <white><town></white>.</yellow>"),

    TOWN_PURGE_BAD_PERIOD("town.purge-bad-period",
            "<red><white><input></white> is not a number of days. One or more, and a purge with "
                    + "no floor is a way to empty a town by typing a zero.</red>"),

    TOWN_PURGE_NOBODY("town.purge-nobody",
            "<gray>Nobody in your town has been away for <white><days></white> day(s).</gray>"),

    /** A look, not a change. The wording has to make that unmistakable. */
    TOWN_PURGE_PREVIEW("town.purge-preview",
            "<yellow><white><count></white> resident(s) have not been seen for "
                    + "<white><days></white> day(s). Nothing has changed yet.</yellow>"),

    TOWN_PURGE_PREVIEW_LINE("town.purge-preview-line",
            "  <gray><residents></gray>"),

    TOWN_PURGE_CONFIRM("town.purge-confirm",
            "  <gray>Run <white><command></white> to remove them.</gray>"),

    TOWN_PURGED("town.purged",
            "<yellow>Removed <white><count></white> resident(s) from "
                    + "<white><town></white>.</yellow>"),

    /** Said out loud rather than left as a silent gap between the expected and actual count. */
    TOWN_PURGE_OUTRANKED("town.purge-outranked",
            "  <gray><white><count></white> were left alone because you do not outrank "
                    + "them.</gray>"),

    TOWN_KICKED("town.kicked",
            "<yellow><white><resident></white> was removed from <white><town></white>.</yellow>"),

    TOWN_RENAMED("town.renamed",
            "<green>Renamed to <white><town></white>.</green>"),

    TOWN_MAYOR_TRANSFERRED("town.mayor-transferred",
            "<green><white><resident></white> is now the mayor of <white><town></white>.</green>"),

    TOWN_DISBANDED("town.disbanded",
            "<yellow>Disbanded <white><town></white>.</yellow>"),

    TOWN_RECLAIMED("town.reclaimed",
            "<green><white><town></white> stands again. You are its mayor.</green>"),

    /**
     * Crossing into a town.
     *
     * <p>Short on purpose. These are shown above the hotbar on every border crossing, and a sentence
     * there is read once and resented afterwards.</p>
     */
    NOTICE_TOWN("notice.town",
            "<gold>« <white><town></white> »</gold>"),

    /** The same, on a plot somebody holds — which is what changes what you may do there. */
    NOTICE_TOWN_PLOT("notice.town-plot",
            "<gold>« <white><town></white> »</gold> <dark_gray>-</dark_gray> "
                    + "<gray>plot: <white><holder></white></gray>"),

    NOTICE_WILDERNESS("notice.wilderness",
            "<dark_gray>« <gray>Wilderness</gray> »</dark_gray>"),

    NOTICE_RUIN("notice.ruin",
            "<dark_red>« <white><ruin></white> in ruins »</dark_red> "
                    + "<dark_gray>-</dark_gray> <gray>unprotected, <remaining> left</gray>"),

    TOWN_NOT_IN_A_TOWN("town.not-in-a-town",
            "<red>You are not in a town.</red>"),

    TOWN_INFO_HEADER("town.info-header",
            "<gray>Town <white><town></white></gray>"),

    TOWN_INFO_LINE("town.info-line",
            "  <gray><label></gray> <white><value></white>"),

    TOWN_CLAIMED("town.claimed",
            "<green>Claimed <white><chunk></white> as <white><kind></white>. "
                    + "Your town now holds <white><total></white> chunk(s).</green>"),

    TOWN_UNCLAIMED("town.unclaimed",
            "<yellow>Released <white><chunk></white>.</yellow>"),

    TOWN_UNCLAIMED_ALL("town.unclaimed-all",
            "<yellow>Released <white><chunks></white> chunk(s). <white><town></white> now holds "
                    + "only its home chunk.</yellow>"),

    TOWN_BANK_HEADER("town.bank-header",
            "<gray>Treasury of <white><town></white> <dark_gray>-</dark_gray> "
                    + "<white><balance></white></gray>"),

    TOWN_BANK_LINE("town.bank-line",
            "  <white><movement></white><gray><detail></gray> <dark_gray>-</dark_gray> <gray><by></gray>"),

    TOWN_BANK_NO_HISTORY("town.bank-no-history",
            "  <gray>Nothing has moved yet.</gray>"),

    /** Said once, on the bank screen, rather than only when somebody tries and is refused. */
    TOWN_BANK_NO_ECONOMY("town.bank-no-economy",
            "  <yellow>No economy plugin is installed, so money cannot move between "
                    + "players and the town.</yellow>"),

    TOWN_BANK_DEPOSITED("town.bank-deposited",
            "<green>Deposited <white><amount></white>. The treasury holds "
                    + "<white><balance></white>.</green>"),

    TOWN_BANK_WITHDREW("town.bank-withdrew",
            "<green>Withdrew <white><amount></white>. The treasury holds "
                    + "<white><balance></white>.</green>"),

    TOWN_BANK_BAD_AMOUNT("town.bank-bad-amount",
            "<red><white><input></white> is not an amount.</red>"),

    TOWN_SPAWN_ARRIVED("town.spawn-arrived",
            "<green>Welcome to <white><town></white>.</green>"),

    /** Only shown when a fare was actually taken, so an unpriced server never sees it. */
    TOWN_SPAWN_FARE("town.spawn-fare",
            "<gray>The journey cost <white><amount></white>.</gray>"),

    TOWN_SPAWN_WARMUP("town.spawn-warmup",
            "<gray>Travelling in <white><seconds></white>s. Stand still.</gray>"),

    TOWN_SPAWN_CANCELLED_MOVED("town.spawn-cancelled-moved",
            "<yellow>You moved, so the journey was called off.</yellow>"),

    /** The reason the warmup exists, so the message says what happened rather than "cancelled". */
    TOWN_SPAWN_CANCELLED_DAMAGED("town.spawn-cancelled-damaged",
            "<red>You were hit, so the journey was called off.</red>"),

    TOWN_SPAWN_COOLDOWN("town.spawn-cooldown",
            "<red>You can travel again in <white><remaining></white>.</red>"),

    TOWN_SPAWN_SET("town.spawn-set",
            "<green>Your town's spawn is now <white><position></white>.</green>"),

    TOWN_SPAWN_CLEARED("town.spawn-cleared",
            "<yellow><white><town></white> no longer has a spawn.</yellow>"),

    /** The destination went away, not the command. Worded so nobody reports it as a bug. */
    TOWN_SPAWN_FAILED("town.spawn-failed",
            "<red>Could not travel there. The world may not be loaded.</red>"),

    TOWN_SPAWN_LOST_WITH_LAND("town.spawn-lost-with-land",
            "<yellow>That chunk held your town's spawn, so the spawn went with it.</yellow>"),

    TOWN_HOMEBLOCK_MOVED("town.homeblock-moved",
            "<green>Your home chunk is now <white><chunk></white>.</green>"),

    TOWN_CLAIM_PREVIEW_OK("town.claim-preview-ok",
            "<gray>Claiming <white><chunk></white> would take you from "
                    + "<white><before></white> to <white><after></white> chunk(s).</gray>"),

    TOWN_CLAIM_PREVIEW_REFUSED("town.claim-preview-refused",
            "<red>Claiming <white><chunk></white> would fail: <white><reason></white></red>"),

    TOWN_CONSOLE_HAS_NO_CHUNK("town.console-has-no-chunk",
            "<red>The console is not standing anywhere.</red>"),

    NATION_FOUNDED("nation.founded",
            "<green>Founded <white><nation></white>. Your town is its capital.</green>"),

    NATION_INFO_HEADER("nation.info-header",
            "<gray>Nation <white><nation></white></gray>"),

    /** An offer, not a change: the town has to accept it. The wording says so on purpose. */
    NATION_INVITED("nation.invited",
            "<green>Invited <white><town></white> to <white><nation></white>. "
                    + "They must accept before anything changes.</green>"),

    NATION_INVITE_WITHDRAWN("nation.invite-withdrawn",
            "<yellow>Withdrew the invitation to <white><town></white>.</yellow>"),

    NATION_INVITES_HEADER("nation.invites-header",
            "<gray>Nations that have invited <white><town></white>:</gray>"),

    NATION_INVITES_LINE("nation.invites-line",
            "  <aqua><nation></aqua> <dark_gray>-</dark_gray> <gray>until <expires></gray>"),

    NATION_NO_INVITES("nation.no-invites",
            "<gray>No nation has invited your town.</gray>"),

    NATION_JOINED("nation.joined",
            "<green><white><town></white> joined <white><nation></white>.</green>"),

    NATION_LEFT("nation.left",
            "<yellow><white><town></white> left <white><nation></white>.</yellow>"),

    NATION_EXPELLED("nation.expelled",
            "<yellow><white><town></white> was removed from <white><nation></white>.</yellow>"),

    NATION_CAPITAL_MOVED("nation.capital-moved",
            "<green>The capital is now <white><town></white>.</green>"),

    NATION_KING_TRANSFERRED("nation.king-transferred",
            "<green><white><resident></white> now leads <white><nation></white>.</green>"),

    NATION_RENAMED("nation.renamed",
            "<green>Renamed to <white><nation></white>.</green>"),

    NATION_DISBANDED("nation.disbanded",
            "<yellow>Disbanded <white><nation></white>.</yellow>"),

    PLOT_INFO_HEADER("plot.info-header",
            "<gray>Plot <white><chunk></white></gray>"),

    PLOT_TAKEN("plot.taken",
            "<green>You now hold the plot at <white><chunk></white>.</green>"),

    PLOT_RELEASED("plot.released",
            "<yellow>The plot at <white><chunk></white> is back with the town.</yellow>"),

    PLOT_TYPE_SET("plot.type-set",
            "<green>The plot at <white><chunk></white> is now a "
                    + "<white><type></white> plot.</green>"),

    PLOT_LIST_HEADER("plot.list-header",
            "<gray>You hold <white><count></white> plot(s):</gray>"),

    PLOT_LIST_LINE("plot.list-line",
            "  <aqua><chunk></aqua> <dark_gray>-</dark_gray> <gray><type></gray>"),

    PLOT_NONE_HELD("plot.none-held",
            "<gray>You hold no plots.</gray>"),

    PLOT_UNKNOWN_TYPE("plot.unknown-type",
            "<red>Unknown plot type <white><input></white>. "
                    + "One of: <white><options></white></red>"),

    ROLE_CREATED("role.created",
            "<green>Created the role <white><role></white>.</green>"),

    ROLE_DELETED("role.deleted",
            "<yellow>Deleted the role <white><role></white>.</yellow>"),

    ROLE_ASSIGNED("role.assigned",
            "<green><white><resident></white> now holds <white><role></white>.</green>"),

    ROLE_UNASSIGNED("role.unassigned",
            "<yellow><white><resident></white> no longer holds <white><role></white>.</yellow>"),

    ROLE_PERMISSION_GRANTED("role.permission-granted",
            "<green><white><role></white> may now <white><permission></white>.</green>"),

    ROLE_PERMISSION_REVOKED("role.permission-revoked",
            "<yellow><white><role></white> may no longer <white><permission></white>.</yellow>"),

    ROLE_REPRIORITISED("role.reprioritised",
            "<green><white><role></white> now ranks <white><priority></white>.</green>"),

    ROLE_RENAMED("role.renamed",
            "<green><white><role></white> is now called <white><name></white>.</green>"),

    ROLE_DECORATED("role.decorated",
            "<green><white><role></white> <white><field></white> is now "
                    + "<white><value></white>.</green>"),

    ROLE_UNKNOWN_PERMISSION("role.unknown-permission",
            "<red>Unknown permission <white><input></white>. "
                    + "One of: <white><options></white></red>"),

    ROLE_LIST_HEADER("role.list-header",
            "<gray>Roles in <white><town></white>, highest first:</gray>"),

    ROLE_LIST_LINE("role.list-line",
            "  <aqua><role></aqua><gray><label></gray> <dark_gray>@</dark_gray><gray><priority></gray> "
                    + "<dark_gray>-</dark_gray> <gray><permissions> permission(s)</gray>"),

    /** The land refused it. {@code <town>} is empty in wilderness, which no default message uses. */
    PROTECTION_DENIED("protection.denied",
            "<red>You cannot do that in <white><town></white>.</red>"),

    /** The land allowed it and the player's own role did not. A different sentence on purpose. */
    PROTECTION_DENIED_BY_ROLE("protection.denied-by-role",
            "<red>Your role in <white><town></white> does not allow that "
                    + "<gray>(<permission>)</gray>.</red>"),

    /** A cache fault, not a rule. The player is told it is a fault so they report it. */
    PROTECTION_TOWN_NOT_LOADED("protection.town-not-loaded",
            "<red>This land is protected, but its town could not be loaded. "
                    + "Please tell an administrator.</red>"),

    FLAG_SET("flag.set",
            "<green><white><flag></white> for <white><relationship></white> is now "
                    + "<white><state></white> <gray>(<scope>)</gray>.</green>"),

    /**
     * A ladder that lets a lower rung do what a higher one may not.
     *
     * <p>A warning rather than a refusal: it is legitimate to want, and refusing would make the
     * command lie about what it accepted.</p>
     */
    FLAG_LADDER_INVERTED("flag.ladder-inverted",
            "  <yellow><white><flag></white> is now allowed to somebody who ranks lower than "
                    + "somebody it is denied to. Usually that means two arguments swapped.</yellow>"),

    /** Cleared, not denied. The distinction matters: the layer below answers again. */
    FLAG_CLEARED("flag.cleared",
            "<yellow><white><flag></white> for <white><relationship></white> is no longer set "
                    + "<gray>(<scope>)</gray>; the layer below decides again.</yellow>"),

    FLAG_LIST_HEADER("flag.list-header",
            "<gray>Overrides for <white><target></white>:</gray>"),

    FLAG_LIST_LINE("flag.list-line",
            "  <aqua><flag></aqua> <gray>for</gray> <white><relationship></white> "
                    + "<dark_gray>-</dark_gray> <white><state></white>"),

    FLAG_LIST_EMPTY("flag.list-empty",
            "<gray>Nothing is overridden for <white><target></white>, "
                    + "so the built-in defaults apply.</gray>"),

    /** Clearing something that was never set. Distinct from a clear that removed a rule. */
    FLAG_NOTHING_TO_CLEAR("flag.nothing-to-clear",
            "<gray>Nothing was set for <white><flag></white> / <white><relationship></white> at "
                    + "<white><scope></white>, so nothing changed.</gray>"),

    FLAG_UNKNOWN_WORLD("flag.unknown-world",
            "<red>No world called <white><input></white> is loaded.</red>"),

    FLAG_UNKNOWN("flag.unknown",
            "<red>Unknown flag <white><input></white>. One of: <white><options></white></red>"),

    FLAG_UNKNOWN_RELATIONSHIP("flag.unknown-relationship",
            "<red>Unknown relationship <white><input></white>. "
                    + "One of: <white><options></white></red>"),

    // --- what a town says about itself -----------------------------------------------------------

    TOWN_SET_BOARD("town.set-board",
            "<green>The board of <white><town></white> is now: <white><value></white></green>"),

    TOWN_SET_TAG("town.set-tag",
            "<green>The tag of <white><town></white> is now <white><value></white>.</green>"),

    TOWN_SET_COLOUR("town.set-colour",
            "<green><white><town></white> is drawn in <white><value></white> on maps.</green>"),

    /**
     * Openness, with the consequence spelled out.
     *
     * <p>A mayor who turns this on has removed the only thing standing between their town and
     * anybody who types the command. Saying so at the moment they do it is worth the extra line.</p>
     */
    TOWN_SET_OPEN("town.set-open",
            "<green><white><town></white> is now <white><state></white> to anybody joining without "
                    + "an invitation.</green>"),

    TOWN_SET_PUBLIC("town.set-public",
            "<green>Travel to <white><town></white>'s spawn by outsiders is now "
                    + "<white><state></white>.</green>"),

    TOWN_SET_NEUTRAL("town.set-neutral",
            "<green><white><town></white> has declared neutrality <white><state></white>.</green>"),

    NATION_SET_BOARD("nation.set-board",
            "<green>The board of <white><nation></white> is now: <white><value></white></green>"),

    NATION_SET_TAG("nation.set-tag",
            "<green>The tag of <white><nation></white> is now <white><value></white>.</green>"),

    NATION_SET_COLOUR("nation.set-colour",
            "<green><white><nation></white> is drawn in <white><value></white> on maps.</green>"),

    NATION_SET_NEUTRAL("nation.set-neutral",
            "<green><white><nation></white> has declared neutrality <white><state></white>.</green>"),

    /** The board itself, shown to a resident on arrival and on {@code /town info}. */
    TOWN_BOARD_LINE("town.board-line",
            "<gray>« <white><board></white> »</gray>"),

    // --- diplomacy -------------------------------------------------------------------------------

    /**
     * An alliance offered but not yet returned.
     *
     * <p>Says plainly that nothing has changed yet. A message that reported an offer as an alliance
     * would be the command lying: the other nation has agreed to nothing and its land is not
     * open.</p>
     */
    NATION_ALLIANCE_OFFERED("nation.alliance-offered",
            "<yellow>Offered an alliance to <white><nation></white>. Nothing changes until they "
                    + "declare it too.</yellow>"),

    NATION_ALLIANCE_SEALED("nation.alliance-sealed",
            "<green>Allied with <white><nation></white>. Their people may now build on your "
                    + "land.</green>"),

    NATION_ENEMY_DECLARED("nation.enemy-declared",
            "<red><white><nation></white> is now an enemy of your nation.</red>"),

    NATION_RELATION_WITHDRAWN("nation.relation-withdrawn",
            "<yellow>Your nation no longer declares anything about "
                    + "<white><nation></white>.</yellow>"),

    NATION_RELATIONS_HEADER("nation.relations-header",
            "<gray>Where <white><nation></white> stands</gray>"),

    // --- chat channels ---------------------------------------------------------------------------
    // The two format keys are only used when RiftChat is absent. With it installed, RiftChat renders
    // and these are never reached - which is the point of not building a second chat formatter.

    CHAT_TOWN_FORMAT("chat.town-format",
            "<gray><prefix></gray><dark_green>[Town]</dark_green> <white><sender></white><gray>:</gray> <message>"),

    CHAT_NATION_FORMAT("chat.nation-format",
            "<gray><prefix></gray><dark_aqua>[Nation]</dark_aqua> <white><sender></white><gray>:</gray> <message>"),

    CHAT_CHANNEL_ON("chat.channel-on",
            "<green>You are now speaking to your <white><channel></white>. "
                    + "Run the command again to stop.</green>"),

    CHAT_CHANNEL_OFF("chat.channel-off",
            "<yellow>You are back in ordinary chat.</yellow>"),

    /** Said when somebody's town or nation went away while their channel was still on. */
    CHAT_CHANNEL_LOST("chat.channel-lost",
            "<red>You are no longer in a <white><channel></white>, so that was not sent.</red>"),

    CHAT_NO_CHANNEL("chat.no-channel",
            "<red>You have no <white><channel></white> to speak to.</red>"),

    CHAT_NOT_ALLOWED("chat.not-allowed",
            "<red>Your role does not let you speak in <white><channel></white> chat.</red>"),

    CHAT_NOBODY_HEARD("chat.nobody-heard",
            "<gray>Nobody else from your <white><channel></white> is online.</gray>"),

    // --- listings ------------------------------------------------------------------------------
    // Every listing that can run to more than one screen shares this footer. It names the command to
    // type rather than only being clickable: a click event is invisible to anybody reading on
    // Bedrock through Geyser, and a page two nobody can reach is the same as no page two.

    LISTING_MORE("listing.more",
            "<gray>More: <white><command></white></gray>"),

    LISTING_UNKNOWN_SORT("listing.unknown-sort",
            "<red>Unknown order <white><input></white>. One of: <white><options></white></red>"),

    TOWN_LIST_HEADER("town.list-header",
            "<dark_gray>---</dark_gray> <white>Towns</white> <gray>(<count>)</gray> "
                    + "<dark_gray>-</dark_gray> <gray>page <page> of <pages>, by <sort></gray>"),

    TOWN_LIST_LINE("town.list-line",
            "  <dark_gray><index>.</dark_gray> <aqua><town></aqua> <dark_gray>-</dark_gray> "
                    + "<gray><residents> resident(s), <chunks> chunk(s)</gray><nation>"),

    /** Appended to a listing line, and empty for a town in no nation rather than saying "none". */
    TOWN_LIST_NATION("town.list-nation",
            " <dark_gray>|</dark_gray> <gray><nation></gray>"),

    TOWN_LIST_EMPTY("town.list-empty",
            "<gray>No towns have been founded yet.</gray>"),

    TOWN_RESIDENTS_HEADER("town.residents-header",
            "<gray>Residents of <white><town></white> <dark_gray>(<count>)</dark_gray></gray>"),

    TOWN_RESIDENTS_LINE("town.residents-line",
            "  <gray><residents></gray>"),

    /** An offer, not a merge. The wording says so, because a mayor will assume otherwise. */
    TOWN_MERGE_OFFERED("town.merge-offered",
            "<green>Offered to absorb <white><town></white>. Nothing changes until their "
                    + "mayor accepts.</green>"),

    TOWN_MERGE_CANCELLED("town.merge-cancelled",
            "<yellow>Withdrew the offer to absorb <white><town></white>.</yellow>"),

    TOWN_MERGED("town.merged",
            "<green><white><absorbed></white> is now part of <white><survivor></white> "
                    + "<gray>(<residents> resident(s), <chunks> chunk(s))</gray>.</green>"),

    /** Named because the role book went with the town and nothing else records what it held. */
    TOWN_MERGE_ROLES_LOST("town.merge-roles-lost",
            "  <yellow>Roles that did not survive: <white><roles></white></yellow>"),

    TOWN_MERGE_OFFERS_HEADER("town.merge-offers-header",
            "<gray>Towns offering to absorb yours:</gray>"),

    TOWN_MERGE_OFFERS_LINE("town.merge-offers-line",
            "  <aqua><town></aqua> <dark_gray>-</dark_gray> <gray>until <expires></gray>"),

    TOWN_MERGE_NO_OFFERS("town.merge-no-offers",
            "<gray>No town has offered to absorb yours.</gray>"),

    TOWN_TRUSTED("town.trusted",
            "<green><white><resident></white> is now trusted in "
                    + "<white><town></white>.</green>"),

    TOWN_UNTRUSTED("town.untrusted",
            "<yellow><white><resident></white> is no longer trusted in "
                    + "<white><town></white>.</yellow>"),

    TOWN_TRUST_LIST_HEADER("town.trust-list-header",
            "<gray>Trusted by <white><town></white> <dark_gray>(<count>)</dark_gray></gray>"),

    TOWN_TRUST_LIST_LINE("town.trust-list-line",
            "  <gray><residents></gray>"),

    TOWN_TRUST_LIST_EMPTY("town.trust-list-empty",
            "<gray><white><town></white> trusts nobody outside it.</gray>"),

    TOWN_OUTLAWED("town.outlawed",
            "<yellow><white><resident></white> is no longer welcome in "
                    + "<white><town></white>.</yellow>"),

    TOWN_OUTLAW_PARDONED("town.outlaw-pardoned",
            "<green><white><resident></white> is welcome in <white><town></white> "
                    + "again.</green>"),

    TOWN_OUTLAW_LIST_HEADER("town.outlaw-list-header",
            "<gray>Outlawed by <white><town></white> <dark_gray>(<count>)</dark_gray></gray>"),

    TOWN_OUTLAW_LIST_LINE("town.outlaw-list-line",
            "  <white><resident></white> <dark_gray>-</dark_gray> <gray>by <white><by></white>, "
                    + "<when></gray>"),

    TOWN_OUTLAW_LIST_EMPTY("town.outlaw-list-empty",
            "<gray><white><town></white> has outlawed nobody.</gray>"),

    TOWN_ONLINE_HEADER("town.online-header",
            "<gray>Online in <white><town></white>: <white><count></white> of "
                    + "<white><residents></white></gray>"),

    TOWN_ONLINE_LINE("town.online-line",
            "  <green><resident></green><roles>"),

    /** Appended to an online line when the town has given them roles. */
    TOWN_ONLINE_ROLES("town.online-roles",
            " <dark_gray>-</dark_gray> <gray><roles></gray>"),

    TOWN_ONLINE_NONE("town.online-none",
            "<gray>Nobody from <white><town></white> is online.</gray>"),

    NATION_LIST_HEADER("nation.list-header",
            "<dark_gray>---</dark_gray> <white>Nations</white> <gray>(<count>)</gray> "
                    + "<dark_gray>-</dark_gray> <gray>page <page> of <pages>, by <sort></gray>"),

    NATION_LIST_LINE("nation.list-line",
            "  <dark_gray><index>.</dark_gray> <aqua><nation></aqua> <dark_gray>-</dark_gray> "
                    + "<gray><towns> town(s), <residents> resident(s), <chunks> chunk(s)</gray>"),

    NATION_LIST_EMPTY("nation.list-empty",
            "<gray>No nations have been founded yet.</gray>"),

    // --- one player ----------------------------------------------------------------------------

    /** The directory of everybody who belongs to a town. */
    RESIDENT_LIST_HEADER("resident.list-header",
            "<dark_gray>---</dark_gray> <white>Residents</white> <gray>(<count>)</gray> "
                    + "<dark_gray>-</dark_gray> <gray>page <page> of <pages></gray>"),

    RESIDENT_LIST_LINE("resident.list-line",
            "  <dark_gray><index>.</dark_gray> <green><resident></green> "
                    + "<dark_gray>-</dark_gray> <gray><town></gray>"),

    RESIDENT_LIST_EMPTY("resident.list-empty",
            "<gray>Nobody has joined a town yet.</gray>"),

    NATION_BANK_HEADER("nation.bank-header",
            "<gray>Treasury of <white><nation></white> <dark_gray>-</dark_gray> "
                    + "<white><balance></white></gray>"),

    NATION_BANK_DEPOSITED("nation.bank-deposited",
            "<green>Deposited <white><amount></white>. The nation holds "
                    + "<white><balance></white>.</green>"),

    NATION_BANK_WITHDREW("nation.bank-withdrew",
            "<green>Withdrew <white><amount></white>. The nation holds "
                    + "<white><balance></white>.</green>"),

    NATION_ONLINE_HEADER("nation.online-header",
            "<gray>Online in <white><nation></white>: <white><count></white> of "
                    + "<white><residents></white></gray>"),

    NATION_ONLINE_LINE("nation.online-line",
            "  <green><resident></green> <dark_gray>-</dark_gray> <gray><town></gray>"),

    NATION_ONLINE_NONE("nation.online-none",
            "<gray>Nobody from <white><nation></white> is online.</gray>"),

    RESIDENT_NOTICES_SET("resident.notices-set",
            "<green>Territory notices: <white><value></white>.</green>"),

    RESIDENT_NOTICES_DEFAULT("resident.notices-default",
            "<green>Territory notices follow the server again.</green>"),

    RESIDENT_NOTICES_NOW("resident.notices-now",
            "<gray>Territory notices: <white><value></white>.</gray>"),

    RESIDENT_NOTICES_UNKNOWN("resident.notices-unknown",
            "<red><white><input></white> is not one of: <white><options></white></red>"),

    /** Stored, and then told the truth: the setting cannot do anything on this server. */
    RESIDENT_NOTICES_DISABLED_HERE("resident.notices-disabled-here",
            "  <yellow>This server does not send territory notices, so this will not "
                    + "change anything here.</yellow>"),

    RESIDENT_HEADER("resident.header",
            "<dark_gray>---</dark_gray> <white><resident></white> <dark_gray>---</dark_gray>"),

    RESIDENT_LINE("resident.line",
            "  <gray><label></gray> <white><value></white>"),

    RESIDENT_UNKNOWN("resident.unknown",
            "<red>RiftTowny has never seen a player called <white><name></white>.</red>"),

    /** What {@code Last seen} says for somebody who is here now. */
    RESIDENT_ONLINE_NOW("resident.online-now",
            "online now"),

    RESIDENT_TOWNLESS("resident.townless",
            "no town"),

    // --- the map -------------------------------------------------------------------------------

    MAP_HEADER("map.header",
            "<dark_gray>---</dark_gray> <white><world></white> <gray><x>, <z></gray> "
                    + "<dark_gray>-</dark_gray> <gray>north is up</gray>"),

    /**
     * The legend.
     *
     * <p>Long, and shown every time on purpose: a map whose symbols have to be remembered is a map
     * that gets read wrongly, and being told which square is you is the whole point of the screen.</p>
     */
    MAP_LEGEND("map.legend",
            "<gray><gold>gold</gold> you <dark_gray>|</dark_gray> <green>your town</green> "
                    + "<dark_gray>|</dark_gray> <aqua>your nation</aqua> <dark_gray>|</dark_gray> "
                    + "<red>elsewhere</red> <dark_gray>|</dark_gray> <dark_red>ruins</dark_red> "
                    + "<dark_gray>|</dark_gray> <dark_gray>wilderness</dark_gray></gray>"),

    MAP_LEGEND_SHAPES("map.legend-shapes",
            "<gray><white>{}</white> home <dark_gray>|</dark_gray> <white>()</white> outpost "
                    + "<dark_gray>|</dark_gray> <white>##</white> your plot "
                    + "<dark_gray>|</dark_gray> <white>[]</white> claimed "
                    + "<dark_gray>|</dark_gray> <white>--</white> wilderness</gray>"),

    // --- migration -------------------------------------------------------------------------------

    MIGRATE_NOT_CONFIGURED("migrate.not-configured",
            "<red>No Towny database is configured. Set <white>migration.towny.jdbc-url</white> in "
                    + "config.yml — the credentials go there rather than in this command, so they "
                    + "do not end up in the log.</red>"),

    MIGRATE_AMBIGUOUS("migrate.ambiguous",
            "<red>Both <white>jdbc-url</white> and <white>data-folder</white> are set, and they may "
                    + "hold different data — a server that moved to MySQL still has its old files. "
                    + "Clear whichever one is not the live database.</red>"),

    MIGRATE_STARTED("migrate.started",
            "<gray>Reading <white><source></white> (<white><mode></white>)…</gray>"),

    MIGRATE_READ("migrate.read",
            "<gray>Read <white><summary></white></gray>"),

    MIGRATE_UNREADABLE("migrate.unreadable",
            "<red>That database could not be read: <white><reason></white></red>"),

    MIGRATE_DONE("migrate.done",
            "<green><summary></green>"),

    MIGRATE_PROBLEM("migrate.problem",
            "  <yellow><problem></yellow>"),

    MIGRATE_DRY_RUN("migrate.dry-run",
            "<gray>Nothing was written. Run <white><command></white> to do it for real — take a "
                    + "backup first, because there is no undo.</gray>"),

    MIGRATE_FAILED("migrate.failed",
            "<red>The import stopped part-way. Whatever had already been written is kept, and "
                    + "running it again will resume — the details are in the server log.</red>"),

    STATUS_HEADER("status.header",
            "<gray>RiftTowny <white><version></white></gray>"),

    STATUS_PLATFORM("status.platform",
            "  <gray>Platform</gray> <white><platform></white> <dark_gray>|</dark_gray> "
                    + "<gray>API</gray> <white><api></white>"),

    STATUS_STORAGE("status.storage",
            "  <gray>Storage</gray> <white><backend></white> <dark_gray>|</dark_gray> "
                    + "<gray>schema</gray> <white><schema></white> <dark_gray>|</dark_gray> "
                    + "<gray>topology</gray> <white><topology></white>"),

    STATUS_OUTBOX("status.outbox",
            "  <gray>Outbox</gray> <white><pending></white> pending, <white><claimed></white> claimed, "
                    + "<white><failed></white> failed"),

    STATUS_TAX_RUN("status.tax-run",
            "  <gray>Last tax run</gray> <white><period></white> <dark_gray>-</dark_gray> "
                    + "<white><towns></white> town(s), <white><residents></white> resident(s), "
                    + "<white><fallen></white> fallen <gray>(<server>)</gray>"),

    STATUS_TAX_RUN_UNFINISHED("status.tax-run-unfinished",
            "  <yellow>Last tax run <white><period></white> did not finish</yellow> "
                    + "<gray>(<server>); the next run will resume it</gray>"),

    STATUS_OUTBOX_UNAVAILABLE("status.outbox-unavailable",
            "  <gray>Outbox</gray> <red>unavailable: <reason></red>"),

    STATUS_INTEGRATIONS_HEADER("status.integrations-header",
            "  <gray>Integrations</gray>"),

    STATUS_INTEGRATION_ACTIVE("status.integration-active",
            "    <green><capability></green> <dark_gray>-</dark_gray> <gray><detail></gray>"),

    STATUS_INTEGRATION_PROBLEM("status.integration-problem",
            "    <red><capability></red> <white><state></white> <dark_gray>-</dark_gray> <gray><detail></gray>"),

    STATUS_INTEGRATION_ABSENT("status.integration-absent",
            "    <dark_gray><capability> - not installed</dark_gray>");

    private final String path;
    private final String fallback;

    MessageKey(final String path, final String fallback) {
        this.path = path;
        this.fallback = fallback;
    }

    /** The key's path in {@code messages.yml}. */
    public String path() {
        return path;
    }

    /** The shipped default, used when the file has no entry for this key. */
    public String fallback() {
        return fallback;
    }
}
