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

    STARTUP_TOWNY_CONFLICT("startup.towny-conflict",
            "<red>RiftTowny cannot run alongside Towny: the command tree and the "
                    + "<white>%townyadvanced_*%</white> placeholder namespace both collide. "
                    + "Remove one of the two plugins.</red>"),

    STARTUP_STORAGE_PROBLEM("startup.storage-problem",
            "<red><setting></red> <gray><problem></gray> <yellow>-></yellow> <white><remedy></white>"),

    STARTUP_STORAGE_ABORT("startup.storage-abort",
            "<red>RiftTowny did not start: the storage configuration is unsafe. "
                    + "Fix the problems above and restart.</red>"),

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
     * Shown on entering a ruin.
     *
     * <p>Says both timers, because they are two different decisions: how long there is to loot the
     * place, and how long until anybody can rebuild it.</p>
     */
    RUIN_ENTERED("town.ruin-entered",
            "<gray>The ruins of <white><ruin></white> <dark_gray>-</dark_gray> "
                    + "<gray>unprotected, crumbling in <white><remaining></white>.</gray>"),

    RUIN_ENTERED_RECLAIMABLE("town.ruin-entered-reclaimable",
            "<gray>The ruins of <white><ruin></white> <dark_gray>-</dark_gray> "
                    + "<gray>unprotected, crumbling in <white><remaining></white>. "
                    + "<white>/town reclaim</white> to rebuild it.</gray>"),

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

    ROLE_CREATED("role.created",
            "<green>Created the role <white><role></white>.</green>"),

    ROLE_DELETED("role.deleted",
            "<yellow>Deleted the role <white><role></white>.</yellow>"),

    ROLE_ASSIGNED("role.assigned",
            "<green><white><resident></white> now holds <white><role></white>.</green>"),

    ROLE_UNASSIGNED("role.unassigned",
            "<yellow><white><resident></white> no longer holds <white><role></white>.</yellow>"),

    ROLE_LIST_HEADER("role.list-header",
            "<gray>Roles in <white><town></white>, highest first:</gray>"),

    ROLE_LIST_LINE("role.list-line",
            "  <aqua><role></aqua> <dark_gray>@</dark_gray><gray><priority></gray> "
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

    FLAG_UNKNOWN("flag.unknown",
            "<red>Unknown flag <white><input></white>. One of: <white><options></white></red>"),

    FLAG_UNKNOWN_RELATIONSHIP("flag.unknown-relationship",
            "<red>Unknown relationship <white><input></white>. "
                    + "One of: <white><options></white></red>"),

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
