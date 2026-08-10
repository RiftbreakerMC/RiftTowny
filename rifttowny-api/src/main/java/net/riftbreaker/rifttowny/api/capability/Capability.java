package net.riftbreaker.rifttowny.api.capability;

/**
 * Every optional thing RiftTowny can do, named so a consumer can ask before assuming.
 *
 * <p>A capability is not the same as a plugin being installed. It is present only when the adapter
 * for it actually resolved against the real API — see {@link CapabilityState}. This distinction is
 * the whole point: a server owner who sees "RiftEco: ACTIVE" should be able to trust it.</p>
 */
public enum Capability {

    /** Civic bank accounts, taxes and costs, backed by RiftEco. */
    ECONOMY_RIFTECO("RiftEco"),
    /** Single-currency economy fallback used only when RiftEco is absent. */
    ECONOMY_VAULT("VaultUnlocked"),
    /**
     * The audit log, for civic events and for land regeneration alike.
     *
     * <p>RiftLogger is the only audit integration. CoreProtect was dropped in favour of it, so
     * there is one place an operator looks and one place a retention policy applies.</p>
     */
    AUDIT_RIFTLOGGER("RiftLogger"),
    /** Combat tags, bounce-back and valid-kill determination. */
    COMBAT_RIFTPVP("RiftPvP"),
    /** Community flight boosters and their authoritative timers. */
    BOOSTERS_RIFTBOOSTERS("RiftBoosters"),
    /** Town, nation and ally chat channels. */
    CHAT_RIFTCHAT("RiftChat"),
    /** Cross-server transport and Discord announcements. */
    NETWORK_VELOCITYSRV("VelocitySrv"),
    /**
     * Per-organisation Discord channel provisioning.
     *
     * <p>Reported {@link CapabilityState#BLOCKED}: VelocitySrv has no channel-creation API. The
     * contract RiftTowny needs is written down in {@code INTEGRATION_CONTRACTS.md}.</p>
     */
    DISCORD_CHANNEL_PROVISIONING("VelocitySrv"),
    /** Shop listings and transactions. */
    SHOPS_RIFTSHOP("RiftShop"),
    /** Spawner ownership and upgrades. */
    SPAWNERS_RIFTSPAWNERS("RiftSpawners"),
    /** Events, bounties and supply drops. */
    EVENTS_RIFTEVENTS("RiftEvents"),
    /** Random teleport, whose command is owned by RiftEssentials. */
    RTP_RIFTESSENTIALS("RiftEssentials"),
    /** Shared menu framework and player snapshots. */
    CORE_RIFTCORE("RiftCore"),
    /** Skill experience and ability modifiers inside territory. */
    SKILLS_MCMMO("mcMMO"),
    /** Placeholder expansion. */
    PLACEHOLDERS_PAPI("PlaceholderAPI"),
    /** Permission contexts for town, nation, role and war state. */
    PERMISSIONS_LUCKPERMS("LuckPerms"),
    /** Native Bedrock forms. */
    BEDROCK_FLOODGATE("Floodgate"),
    /** Map markers. */
    MAP_BLUEMAP("BlueMap"),
    /** Map markers. */
    MAP_DYNMAP("dynmap"),
    /** Map markers. */
    MAP_SQUAREMAP("squaremap");

    private final String pluginName;

    Capability(final String pluginName) {
        this.pluginName = pluginName;
    }

    /** The Bukkit plugin name this capability depends on, as it appears in {@code plugin.yml}. */
    public String pluginName() {
        return pluginName;
    }
}
