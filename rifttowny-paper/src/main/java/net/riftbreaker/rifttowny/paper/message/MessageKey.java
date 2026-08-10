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

    COMMAND_UNKNOWN_SUBCOMMAND("command.unknown-subcommand",
            "<red>Unknown subcommand <white><input></white>. Try <white>/rifttowny help</white>.</red>"),

    COMMAND_HELP_HEADER("command.help-header",
            "<gray>RiftTowny administration:</gray>"),

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

    TOWN_NOT_IN_A_TOWN("town.not-in-a-town",
            "<red>You are not in a town.</red>"),

    TOWN_INFO_HEADER("town.info-header",
            "<gray>Town <white><town></white></gray>"),

    TOWN_INFO_LINE("town.info-line",
            "  <gray><label></gray> <white><value></white>"),

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
