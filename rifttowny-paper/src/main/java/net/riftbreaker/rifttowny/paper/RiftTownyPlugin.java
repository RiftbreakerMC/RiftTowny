package net.riftbreaker.rifttowny.paper;

import net.riftbreaker.rifttowny.api.RiftTownyProvider;
import net.riftbreaker.rifttowny.api.capability.Capability;
import net.riftbreaker.rifttowny.api.scheduler.RiftScheduler;
import net.riftbreaker.rifttowny.domain.config.StartupProblem;
import net.riftbreaker.rifttowny.domain.config.StorageTopologyRule;
import net.riftbreaker.rifttowny.integrations.DefaultCapabilityRegistry;
import net.riftbreaker.rifttowny.paper.command.RiftTownyCommand;
import net.riftbreaker.rifttowny.paper.config.RiftTownySettings;
import net.riftbreaker.rifttowny.paper.message.MessageService;
import net.riftbreaker.rifttowny.paper.scheduler.SchedulerFactory;
import net.riftbreaker.rifttowny.storage.JdbcIdempotencyStore;
import net.riftbreaker.rifttowny.storage.JdbcOutboxRepository;
import net.riftbreaker.rifttowny.storage.RiftTownyDatabase;
import net.riftbreaker.rifttowny.storage.SchemaMigrator;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Bootstrap only.
 *
 * <p>Deliberately thin: it wires services together and owns their lifecycle, and holds no domain
 * logic of its own. Anything that could be a service is one, so it can be tested without a server.</p>
 */
public final class RiftTownyPlugin extends JavaPlugin {

    private static final String TOWNY_PLUGIN_NAME = "Towny";

    /**
     * The running plugin.
     *
     * <p>Volatile because it is written on the enabling thread and read from command handlers,
     * listeners and — on Folia — region threads that never synchronised with that write. Without it
     * a reader could observe a stale null well after enable finished.</p>
     */
    private static volatile RiftTownyPlugin instance;

    private RiftTownySettings settings;
    private MessageService messages;
    private RiftScheduler scheduler;
    private RiftTownyDatabase database;
    private SchemaMigrator.MigrationSummary schema;
    private JdbcOutboxRepository outbox;
    private JdbcIdempotencyStore idempotencyKeys;
    private DefaultCapabilityRegistry capabilities;
    private net.riftbreaker.rifttowny.storage.JdbcResidentRepository residentRepository;
    private net.riftbreaker.rifttowny.storage.JdbcTownRepository townRepository;
    private net.riftbreaker.rifttowny.storage.JdbcCivicStore civicStore;
    private net.riftbreaker.rifttowny.storage.JdbcNationRepository nationRepository;
    private net.riftbreaker.rifttowny.domain.service.TownService townService;
    private net.riftbreaker.rifttowny.domain.service.NationService nationService;
    private net.riftbreaker.rifttowny.domain.service.NationRoleService nationRoleService;
    private net.riftbreaker.rifttowny.domain.service.TownRoleService townRoleService;
    private net.riftbreaker.rifttowny.domain.service.TerritoryService territoryService;
    private net.riftbreaker.rifttowny.domain.territory.TerritoryIndex territoryIndex;
    private net.riftbreaker.rifttowny.domain.civic.CivicCache civicCache;
    private net.riftbreaker.rifttowny.domain.civic.NationCache nationCache;
    private net.riftbreaker.rifttowny.domain.service.CivicCacheService civicCacheService;
    private net.riftbreaker.rifttowny.domain.flag.FlagOverrides flagOverrides;
    private net.riftbreaker.rifttowny.domain.service.FlagService flagService;
    private net.riftbreaker.rifttowny.domain.territory.RuinIndex ruinIndex;
    private net.riftbreaker.rifttowny.domain.service.RuinService ruinService;
    private net.riftbreaker.rifttowny.domain.service.SpawnService spawnService;
    private net.riftbreaker.rifttowny.domain.service.PlotService plotService;
    private net.riftbreaker.rifttowny.integrations.economy.RiftEcoAdapter economyAdapter;
    private net.riftbreaker.rifttowny.domain.service.BankService bankService;
    private net.riftbreaker.rifttowny.domain.service.TaxService taxService;
    private net.riftbreaker.rifttowny.domain.civic.ResidentNames residentNames;
    private net.riftbreaker.rifttowny.domain.service.ResidentNameService residentNameService;
    private net.riftbreaker.rifttowny.paper.spawn.TeleportService teleportService;
    private net.riftbreaker.rifttowny.paper.protection.ProtectionService protection;
    private net.riftbreaker.rifttowny.paper.message.DenialText denialText;
    private net.riftbreaker.rifttowny.domain.directory.CivicDirectory directory;
    private net.riftbreaker.rifttowny.domain.directory.TerritoryMap territoryMap;
    private net.riftbreaker.rifttowny.domain.directory.LastKnownChunk positions;
    private net.riftbreaker.rifttowny.domain.directory.TownyPlaceholders placeholders;
    private net.riftbreaker.rifttowny.domain.service.CivicImporter importer;
    private net.riftbreaker.rifttowny.domain.diplomacy.DiplomacyBook diplomacyBook;
    private net.riftbreaker.rifttowny.domain.service.DiplomacyService diplomacy;
    private net.riftbreaker.rifttowny.domain.justice.Outlaws outlawBook;
    private net.riftbreaker.rifttowny.domain.service.OutlawService outlawService;
    private net.riftbreaker.rifttowny.domain.resident.ResidentPreferences residentPreferences;
    private net.riftbreaker.rifttowny.domain.service.PreferenceService preferenceService;
    private net.riftbreaker.rifttowny.domain.chat.ActiveChannels activeChannels;
    private net.riftbreaker.rifttowny.domain.chat.ChannelAudience channelAudience;
    private net.riftbreaker.rifttowny.integrations.chat.RiftChatAdapter chatAdapter;
    private java.time.Clock clock;

    /**
     * Published here rather than in the constructor.
     *
     * <p>{@code onLoad} runs before any other plugin can enable, so nothing can obtain the instance
     * earlier; and assigning {@code this} from a constructor is a {@code this-escape}, which this
     * build treats as an error.</p>
     */
    @Override
    public void onLoad() {
        instance = this;
    }

    /**
     * The running plugin.
     *
     * <p>The standard way anything in this jar reaches plugin services — the logger, the scheduler,
     * the message service — rather than threading a reference through every constructor.</p>
     *
     * @throws IllegalStateException if RiftTowny is not loaded, naming the likely cause instead of
     *         letting a null travel into unrelated code
     */
    public static RiftTownyPlugin getInstance() {
        final RiftTownyPlugin current = instance;
        if (current == null) {
            throw new IllegalStateException(
                    "RiftTowny is not loaded. Declare it as a depend or softdepend, and do not "
                            + "reach for the instance before your own onEnable.");
        }
        return current;
    }

    @Override
    public void onEnable() {
        if (getServer().getPluginManager().getPlugin(TOWNY_PLUGIN_NAME) != null) {
            // Refused rather than degraded. Half-registering the command tree would leave a server
            // where /town sometimes reaches one plugin and sometimes the other.
            getLogger().severe("RiftTowny cannot run alongside Towny: the command tree and the "
                    + "%townyadvanced_*% placeholder namespace both collide. Remove one of them.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        saveDefaultConfig();
        saveResourceIfAbsent("messages.yml");

        final YamlConfiguration messageFile =
                YamlConfiguration.loadConfiguration(new File(getDataFolder(), "messages.yml"));
        this.messages = new MessageService(messageFile, getLogger()::warning);
        this.denialText =
                new net.riftbreaker.rifttowny.paper.message.DenialText(messageFile::getString);
        this.settings = RiftTownySettings.from(getConfig(), getDataFolder().toPath());

        final List<StartupProblem> problems =
                StorageTopologyRule.evaluate(settings.storage(), settings.topology());
        for (final StartupProblem problem : problems) {
            if (problem.fatal()) {
                getLogger().severe(problem.describe());
            } else {
                getLogger().warning(problem.describe());
            }
        }
        if (StorageTopologyRule.blocksStartup(problems)) {
            getLogger().severe("RiftTowny did not start: the storage configuration is unsafe.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.scheduler = SchedulerFactory.create(this);

        if (!openStorage()) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.capabilities = new DefaultCapabilityRegistry(getLogger()::info);
        // No adapter can be written for this: VelocitySrv has no channel-creation API. Recorded as
        // blocked rather than absent, so /rifttowny status names the reason instead of implying the
        // plugin is simply missing. See INTEGRATION_CONTRACTS.md section 2.6.
        capabilities.markBlocked(Capability.DISCORD_CHANNEL_PROVISIONING,
                "VelocitySrv has no channel-provisioning API; see INTEGRATION_CONTRACTS.md 2.6");
        // Same shape: RiftLogger records events, not block changes, and has no rollback. Reported
        // blocked so an operator is told there is no rollback tool rather than assuming there is.
        // RiftLogger gained block history in 4e91300, so the upstream gap is closed. Still
        // reported blocked because RiftTowny has not written the adapter or the listeners yet -
        // an operator should be told there is no history being recorded, not that there is.
        // Through the registry, which is what turns a version mismatch into a recorded FAILED
        // rather than an exception during enable. Absent, present-but-unavailable and bound are all
        // outcomes it records; none of them stops the plugin, and none of them is claimed as
        // working when it is not.
        capabilities.register(
                economyAdapter,
                pluginName -> getServer().getPluginManager().getPlugin(pluginName) != null);
        // The %townyadvanced_*% surface, through the same guard. An absent PlaceholderAPI, or one
        // whose PlaceholderExpansion has moved between versions, costs the server its placeholders
        // and nothing else - the LinkageError a moved superclass throws at class-load time is
        // exactly what the registry is there to catch.
        capabilities.register(
                new net.riftbreaker.rifttowny.integrations.placeholder.PlaceholderAdapter(
                        placeholders, getPluginMeta().getVersion()),
                pluginName -> getServer().getPluginManager().getPlugin(pluginName) != null);
        // RiftChat, for rendering the town and nation channels. Absent, it costs those channels
        // their formatting and nothing else: RiftTowny still decides who hears them and renders a
        // plain line itself, because a channel that disappeared with an optional dependency would
        // be worse than a plain one.
        this.chatAdapter =
                new net.riftbreaker.rifttowny.integrations.chat.RiftChatAdapter(getServer());
        capabilities.register(
                chatAdapter,
                pluginName -> getServer().getPluginManager().getPlugin(pluginName) != null);
        capabilities.markBlocked(Capability.AUDIT_BLOCK_HISTORY,
                "RiftLogger supports block history; RiftTowny's adapter is not written yet. "
                        + "See INTEGRATION_CONTRACTS.md 2.2.1");

        RiftTownyProvider.register(new RiftTownyApiImpl(capabilities, scheduler));

        final PluginCommand command = getCommand("rifttowny");
        if (command == null) {
            getLogger().severe("The 'rifttowny' command is missing from plugin.yml; "
                    + "administration commands will not work.");
        } else {
            final RiftTownyCommand executor = new RiftTownyCommand();
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }

        this.teleportService = new net.riftbreaker.rifttowny.paper.spawn.TeleportService(
                scheduler,
                settings.spawnWarmup(),
                new net.riftbreaker.rifttowny.paper.spawn.SpawnCooldown(
                        settings.spawnCooldown(), System::nanoTime));
        getServer().getPluginManager().registerEvents(teleportService, this);

        registerTree("town", new net.riftbreaker.rifttowny.paper.command.TownCommands(
                townService, townRoleService, territoryService, flagService, ruinService,
                spawnService, teleportService, bankService, outlawService, residentNames, residentRepository,
                townRepository, nationRepository, directory, territoryMap, messages,
                denialText).tree());

        registerTree("plot", new net.riftbreaker.rifttowny.paper.command.PlotCommands(
                plotService, residentNames, residentRepository, townRepository, messages,
                denialText).tree());

        registerTree("nation", new net.riftbreaker.rifttowny.paper.command.NationCommands(
                nationService, nationRoleService, residentNames, residentRepository, townRepository,
                nationRepository, directory, diplomacy, bankService, messages, denialText).tree());

        registerTree("resident", new net.riftbreaker.rifttowny.paper.command.ResidentCommands(
                residentRepository, plotService, directory, residentNames, preferenceService,
                outlawService, settings.territoryNotices(), messages, clock).tree());

        final net.riftbreaker.rifttowny.paper.chat.ChannelRenderer channelRenderer =
                new net.riftbreaker.rifttowny.paper.chat.ChannelRenderer(
                        messages, () -> chatAdapter.service());
        final net.riftbreaker.rifttowny.paper.command.ChatCommands chatCommands =
                new net.riftbreaker.rifttowny.paper.command.ChatCommands(
                        activeChannels, channelAudience, channelRenderer, messages);
        registerTree("townchat", chatCommands.townTree());
        registerTree("nationchat", chatCommands.nationTree());
        getServer().getPluginManager().registerEvents(
                new net.riftbreaker.rifttowny.paper.chat.ChannelChatListener(
                        activeChannels, channelAudience, channelRenderer, messages),
                this);

        registerProtection();
        scheduleHousekeeping();

        getLogger().info("RiftTowny enabled on " + platformName() + ". " + schema.describe() + '.');
        getLogger().info("Storage: " + settings.storage().describeForLog()
                + ", topology: " + settings.describeTopology()
                + ", " + settings.describeRuins()
                + ", " + settings.describeSpawnTravel()
                + ". Prices: " + settings.prices().describe()
                + ". " + settings.taxes().describe() + '.');
    }

    @Override
    public void onDisable() {
        RiftTownyProvider.register(null);
        if (scheduler != null) {
            scheduler.shutdown();
        }
        if (database != null) {
            database.close();
        }
        // Cleared last, and unconditionally: a half-enabled plugin still published itself in
        // onLoad, and leaving a disabled instance reachable is how a reload ends up serving
        // commands from a plugin whose database is already closed.
        instance = null;
    }

    /** Opens the pool and applies migrations. */
    private boolean openStorage() {
        try {
            this.database = RiftTownyDatabase.open(settings.storage());
            // Migrations run inline on the enabling thread on purpose. Everything after this point
            // assumes the schema exists, and a plugin that finished enabling before its tables did
            // would fail in a far more confusing way than a slightly longer startup.
            //
            // Flyway is given this plugin's classloader, not the thread context one: under a plugin
            // classloader the context loader cannot see the plugin's own resources, and Flyway's
            // response to finding no migrations is to create an empty schema rather than to fail.
            this.schema = new SchemaMigrator(database, getClass().getClassLoader()).migrate();

            final Executor storageExecutor = task -> scheduler.async(task);
            this.outbox = new JdbcOutboxRepository(database, storageExecutor);
            this.idempotencyKeys = new JdbcIdempotencyStore(database, storageExecutor);
            this.residentRepository =
                    new net.riftbreaker.rifttowny.storage.JdbcResidentRepository(database, storageExecutor);
            this.townRepository =
                    new net.riftbreaker.rifttowny.storage.JdbcTownRepository(database, storageExecutor);
            this.nationRepository =
                    new net.riftbreaker.rifttowny.storage.JdbcNationRepository(database, storageExecutor);
            this.civicStore =
                    new net.riftbreaker.rifttowny.storage.JdbcCivicStore(database, storageExecutor);

            final java.time.Clock clock = java.time.Clock.systemUTC();
            this.clock = clock;
            this.territoryIndex =
                    net.riftbreaker.rifttowny.domain.territory.TerritoryIndex.empty();
            this.civicCache = net.riftbreaker.rifttowny.domain.civic.CivicCache.empty();
            this.nationCache = net.riftbreaker.rifttowny.domain.civic.NationCache.empty();
            this.diplomacyBook = net.riftbreaker.rifttowny.domain.diplomacy.DiplomacyBook.empty();
            this.outlawBook = net.riftbreaker.rifttowny.domain.justice.Outlaws.empty();
            this.residentPreferences =
                    net.riftbreaker.rifttowny.domain.resident.ResidentPreferences.empty();
            this.civicCacheService = new net.riftbreaker.rifttowny.domain.service.CivicCacheService(
                    civicStore, civicCache, nationCache, diplomacyBook, outlawBook,
                    getLogger()::warning);
            this.flagOverrides = net.riftbreaker.rifttowny.domain.flag.FlagOverrides.empty();
            this.flagService = new net.riftbreaker.rifttowny.domain.service.FlagService(
                    civicStore, clock, flagOverrides);
            this.spawnService = new net.riftbreaker.rifttowny.domain.service.SpawnService(
                    civicStore, clock, territoryIndex)
                    .pricedAt(settings.prices(), economyAdapter);
            // The spawn cache is keyed by town, so it has to be told when one stops existing -
            // a disband or a merge otherwise leaves a spawn in memory until the next restart.
            civicCacheService.alsoForget(spawnService::forget);
            this.plotService = new net.riftbreaker.rifttowny.domain.service.PlotService(
                    civicStore, clock, territoryIndex, settings.prices(), economyAdapter);
            // RiftEco if it is here, and a wallet that refuses everything if it is not. The civic
            // ledger works either way; what the wallet decides is whether money can cross between a
            // player and the town at all. Bound through the registry below, which is what turns a
            // version mismatch into a recorded status instead of a failed enable.
            this.economyAdapter =
                    new net.riftbreaker.rifttowny.integrations.economy.RiftEcoAdapter();
            this.bankService = new net.riftbreaker.rifttowny.domain.service.BankService(
                    civicStore, clock, economyAdapter);
            // Given TownService::collapse rather than reaching into it: a tax run ending a town and
            // a mayor ending one are the same act, so they go through the same path, and injecting
            // it keeps the two services from depending on each other.
            this.taxService = new net.riftbreaker.rifttowny.domain.service.TaxService(
                    civicStore, clock, economyAdapter, settings.taxes(),
                    settings.topology().serverId(),
                    (town, reason) -> townService.collapse(town)
                            .thenApply(net.riftbreaker.rifttowny.domain.service.ServiceResult
                                    ::succeeded));
            this.residentNames = net.riftbreaker.rifttowny.domain.civic.ResidentNames.empty();
            this.residentNameService =
                    new net.riftbreaker.rifttowny.domain.service.ResidentNameService(
                            civicStore, clock, residentNames);
            this.ruinIndex = net.riftbreaker.rifttowny.domain.territory.RuinIndex.empty();
            this.ruinService = new net.riftbreaker.rifttowny.domain.service.RuinService(
                    civicStore,
                    net.riftbreaker.rifttowny.domain.naming.NamePolicy.defaults(),
                    clock,
                    territoryIndex,
                    ruinIndex,
                    civicCacheService,
                    settings.ruinLifetime())
                    .pricedAt(settings.prices(), economyAdapter);
            this.townService = new net.riftbreaker.rifttowny.domain.service.TownService(
                    civicStore,
                    net.riftbreaker.rifttowny.domain.naming.NamePolicy.defaults(),
                    clock,
                    territoryIndex,
                    civicCacheService,
                    flagOverrides,
                    ruinIndex,
                    settings.ruinReclaimDelay(),
                    settings.ruinLifetime(),
                    settings.prices(),
                    economyAdapter,
                    outlawBook);
            this.townRoleService = new net.riftbreaker.rifttowny.domain.service.TownRoleService(
                    civicStore, clock, lockedPermissions(), civicCacheService);
            this.territoryService = new net.riftbreaker.rifttowny.domain.service.TerritoryService(
                    civicStore, clock, territoryIndex, settings.prices(), economyAdapter,
                    flagOverrides);
            this.nationRoleService =
                    new net.riftbreaker.rifttowny.domain.service.NationRoleService(
                            civicStore, clock, lockedPermissions());
            this.nationService = new net.riftbreaker.rifttowny.domain.service.NationService(
                    civicStore,
                    net.riftbreaker.rifttowny.domain.naming.NamePolicy.defaults(),
                    clock,
                    civicCacheService);
            // Read-only views over the same caches protection reads. They hold no state of their
            // own, so nothing has to keep them current and there is nothing for them to get wrong.
            this.directory = new net.riftbreaker.rifttowny.domain.directory.CivicDirectory(
                    civicCache, territoryIndex, nationCache);
            this.territoryMap = new net.riftbreaker.rifttowny.domain.directory.TerritoryMap(
                    territoryIndex, ruinIndex, civicCache);
            // The world check is a live server lookup rather than a stored list: a claim arriving
            // for a world that is not loaded here would be territory nobody can visit.
            this.importer = new net.riftbreaker.rifttowny.domain.service.CivicImporter(
                    civicStore,
                    net.riftbreaker.rifttowny.domain.naming.NamePolicy.defaults(),
                    clock,
                    civicCacheService,
                    worldId -> getServer().getWorld(worldId) != null);
            this.diplomacy = new net.riftbreaker.rifttowny.domain.service.DiplomacyService(
                    civicStore, clock, diplomacyBook);
            this.outlawService = new net.riftbreaker.rifttowny.domain.service.OutlawService(
                    civicStore, clock, outlawBook);
            this.preferenceService = new net.riftbreaker.rifttowny.domain.service.PreferenceService(
                    civicStore, clock, residentPreferences);
            this.positions = net.riftbreaker.rifttowny.domain.directory.LastKnownChunk.empty();
            this.activeChannels = net.riftbreaker.rifttowny.domain.chat.ActiveChannels.empty();
            this.channelAudience = new net.riftbreaker.rifttowny.domain.chat.ChannelAudience(
                    civicCache, nationCache);
            // Built here rather than beside the expansion so it exists whether PlaceholderAPI is
            // installed or not: RiftChat, the web panel and anything else that wants these answers
            // reach the same resolver, and only the PlaceholderAPI wrapper is optional.
            this.placeholders = new net.riftbreaker.rifttowny.domain.directory.TownyPlaceholders(
                    directory, civicCache, nationCache, territoryIndex, ruinIndex, positions,
                    residentNames, settings.prices(), settings.taxes(),
                    who -> getServer().getPlayer(who.value()) != null,
                    new net.riftbreaker.rifttowny.domain.directory.TownyPlaceholders.Truth(
                            settings.truthWords().yes(), settings.truthWords().no()),
                    diplomacyBook, settings.relationColours(), clock);

            // Both loaded before enable returns, and waited on. A protection listener answers from
            // these and cannot wait for a database, so a partially loaded index would read as
            // wilderness and let the first player through the door break anything they liked.
            final int loaded = territoryService.loadIndex()
                    .orTimeout(30L, java.util.concurrent.TimeUnit.SECONDS).join();
            getLogger().info("Loaded " + loaded + " claimed chunk(s) into memory.");

            final var civicLoad = civicCacheService.loadAll()
                    .orTimeout(30L, java.util.concurrent.TimeUnit.SECONDS).join();
            getLogger().info(civicLoad.describe());

            // Waited on for the same reason: a check that ran before this finished would answer from
            // built-in defaults, so a town that had opened its doors would appear to have closed
            // them. Wrong quietly is worse than slow.
            final int flags = flagService.loadAll()
                    .orTimeout(30L, java.util.concurrent.TimeUnit.SECONDS).join();
            getLogger().info("Loaded " + flags + " flag override(s) into memory.");

            // Waited on beside the others. A listener that ran first would read a ruin as
            // wilderness, and the blocks it let through would be gone before the load finished.
            final int standing = ruinService.loadIndex()
                    .orTimeout(30L, java.util.concurrent.TimeUnit.SECONDS).join();
            getLogger().info("Loaded " + standing + " standing ruin(s) into memory.");

            final int spawns = spawnService.loadAll()
                    .orTimeout(30L, java.util.concurrent.TimeUnit.SECONDS).join();
            getLogger().info("Loaded " + spawns + " town spawn(s) into memory.");

            // Waited on for the same reason as the rest: the ALLY rung is answered from this, so a
            // listener running before it finished would refuse an ally who is entitled to build.
            final int declarations = diplomacy.loadAll()
                    .orTimeout(30L, java.util.concurrent.TimeUnit.SECONDS).join();
            getLogger().info("Loaded " + declarations + " diplomatic declaration(s) into memory.");

            // Same reason again: the OUTLAW rung is answered from this, and a listener running
            // before it finished would let somebody a town has barred build in it.
            final int outlawries = outlawService.loadAll()
                    .orTimeout(30L, java.util.concurrent.TimeUnit.SECONDS).join();
            getLogger().info("Loaded " + outlawries + " outlawry(ies) into memory.");

            final int chosen = preferenceService.loadAll()
                    .orTimeout(30L, java.util.concurrent.TimeUnit.SECONDS).join();
            getLogger().info("Loaded " + chosen + " player preference(s) into memory.");

            final int named = residentNameService.loadAll()
                    .orTimeout(30L, java.util.concurrent.TimeUnit.SECONDS).join();
            getLogger().info("Loaded " + named + " resident name(s) into memory.");
            return true;
        } catch (final RuntimeException failure) {
            getLogger().severe("RiftTowny did not start: storage could not be opened or migrated - "
                    + failure.getMessage());
            if (database != null) {
                database.close();
                database = null;
            }
            return false;
        }
    }

    /**
     * Registers the listeners that make a claim mean something in-world.
     *
     * <p>Registered last, after the caches are loaded and waited on. A listener that started
     * answering from an empty index would report every chunk as wilderness, which is the one wrong
     * answer that cannot be taken back — the blocks are already gone.</p>
     */
    private void registerProtection() {
        final var messenger =
                new net.riftbreaker.rifttowny.paper.protection.ProtectionMessenger(messages);
        // The loaded overrides are the settings source, so admin, claim, organisation and world
        // layers all answer. Area and war supply their own layers when those modules land.
        final var query = new net.riftbreaker.rifttowny.domain.flag.ProtectionQuery(
                territoryIndex, civicCache, flagOverrides, ruinIndex, diplomacyBook, outlawBook);
        this.protection = new net.riftbreaker.rifttowny.paper.protection.ProtectionService(
                query, messenger);

        final var manager = getServer().getPluginManager();
        manager.registerEvents(messenger, this);
        manager.registerEvents(
                new net.riftbreaker.rifttowny.paper.protection.BlockProtectionListener(protection),
                this);
        manager.registerEvents(
                new net.riftbreaker.rifttowny.paper.protection.InteractionListener(protection,
                        new net.riftbreaker.rifttowny.paper.protection.BlockActions(
                                new net.riftbreaker.rifttowny.paper.protection.BukkitBlockTags())),
                this);
        manager.registerEvents(
                new net.riftbreaker.rifttowny.paper.protection.EntityProtectionListener(protection),
                this);
        manager.registerEvents(
                new net.riftbreaker.rifttowny.paper.protection.WorldProtectionListener(protection),
                this);

        // Registered unconditionally: a name that silently stops updating shows a town's mayor under
        // a username they abandoned, and no configuration should be able to cause that.
        manager.registerEvents(
                new net.riftbreaker.rifttowny.paper.protection.ResidentPresenceListener(
                        residentNameService,
                        (message, failure) -> getLogger().log(
                                java.util.logging.Level.WARNING, message, failure)),
                this);

        if (settings.territoryNotices()) {
            // Territory is invisible until it refuses somebody something, and finding a town by
            // failing to break a block in it is a worse introduction than a line above the hotbar.
            manager.registerEvents(
                    new net.riftbreaker.rifttowny.paper.protection.TerritoryNoticeListener(
                            new net.riftbreaker.rifttowny.paper.protection.TerritoryNotice(
                                    territoryIndex, ruinIndex, civicCache),
                            messages,
                            residentNames,
                            java.time.Clock.systemUTC(),
                            settings.territoryNoticesOnActionBar(),
                            positions,
                            residentPreferences),
                    this);
        }
    }

    /**
     * Periodic tidying that nothing depends on having run.
     *
     * <p>Deliberately asynchronous and deliberately unhurried. An expired invitation is already
     * inert — hidden from every listing and refused on accept — so this only stops the table growing
     * without bound, and running it hourly rather than every tick costs nothing.</p>
     */
    private void scheduleHousekeeping() {
        scheduler.asyncRepeating(
                () -> nationService.pruneExpiredInvitations().whenComplete((removed, failure) -> {
                    if (failure != null) {
                        getLogger().log(java.util.logging.Level.WARNING,
                                "Could not sweep expired invitations", failure);
                    } else if (removed > 0) {
                        getLogger().info("Swept " + removed + " expired invitation(s).");
                    }
                }),
                java.time.Duration.ofMinutes(5),
                java.time.Duration.ofHours(1));

        if (settings.taxes().collectsAnything()) {
            // Checked far more often than a run is due. The period claim is what stops it running
            // twice, so a frequent check simply finds the period already taken and does nothing —
            // which is what makes a tax due at midnight still happen on a server that was restarted
            // at 23:59.
            scheduler.asyncRepeating(
                    () -> taxService.runIfDue().whenComplete((run, failure) -> {
                        if (failure != null) {
                            getLogger().log(java.util.logging.Level.WARNING,
                                    "Tax run failed", failure);
                        } else {
                            run.ifPresent(completed ->
                                    getLogger().info("Tax run " + completed.periodKey() + ": "
                                            + completed.describe()));
                        }
                    }),
                    java.time.Duration.ofMinutes(2),
                    java.time.Duration.ofMinutes(10));
        }


        if (!settings.outboxRetention().isZero()) {
            // The queue has a writer and no drain. Every civic change appends a row and the module
            // that would deliver them has not shipped, so this is the only thing standing between a
            // long-running server and a table that grows for ever. Delivered rows go too, for when
            // that module does land.
            //
            // Daily, and offset well past startup: nothing waits on it, and a delete sweeping a
            // week of rows is the last thing a server should be doing while it is still filling.
            scheduler.asyncRepeating(
                    () -> {
                        final java.time.Instant before =
                                clock.instant().minus(settings.outboxRetention());
                        outbox.pruneUndelivered(before)
                                .thenCombine(outbox.pruneDelivered(before), Integer::sum)
                                .whenComplete((removed, failure) -> {
                                    if (failure != null) {
                                        getLogger().log(java.util.logging.Level.WARNING,
                                                "Could not sweep the outbox", failure);
                                    } else if (removed > 0) {
                                        getLogger().info(
                                                "Swept " + removed + " outbox row(s) older than "
                                                        + settings.outboxRetention().toDays()
                                                        + " day(s).");
                                    }
                                });
                    },
                    java.time.Duration.ofMinutes(10),
                    java.time.Duration.ofHours(24));
        }
        if (!settings.ruinsEnabled()) {
            return;
        }
        // Releasing a lapsed ruin's land is the only thing that turns it back into wilderness, so
        // unlike the invitation sweep this one has a visible effect: until it runs, the ground stays
        // protected and cannot be reclaimed.
        scheduler.asyncRepeating(
                () -> ruinService.sweepLapsed().whenComplete((released, failure) -> {
                    if (failure != null) {
                        getLogger().log(java.util.logging.Level.WARNING,
                                "Could not sweep lapsed ruins", failure);
                    } else if (released > 0) {
                        getLogger().info("Released the land of " + released + " lapsed ruin(s).");
                    }
                }),
                java.time.Duration.ofMinutes(1),
                settings.ruinSweepInterval());
    }

    /**
     * Permissions an administrator has forbidden to configurable roles.
     *
     * <p>An unknown name is reported rather than ignored: a typo here silently leaves a dangerous
     * permission unlocked, which is the opposite of what the operator was trying to do.</p>
     */
    private java.util.Set<net.riftbreaker.rifttowny.domain.role.Permission> lockedPermissions() {
        final java.util.Set<net.riftbreaker.rifttowny.domain.role.Permission> locked =
                java.util.EnumSet.noneOf(net.riftbreaker.rifttowny.domain.role.Permission.class);
        for (final String raw : getConfig().getStringList("roles.locked-permissions")) {
            net.riftbreaker.rifttowny.domain.role.Permission.parse(raw).ifPresentOrElse(
                    locked::add,
                    () -> getLogger().warning("Unknown permission in roles.locked-permissions: "
                            + raw + ". It locks nothing."));
        }
        return locked;
    }

    private void saveResourceIfAbsent(final String name) {
        if (!new File(getDataFolder(), name).exists()) {
            saveResource(name, false);
        }
    }

    /** Binds a command tree to a {@code plugin.yml} command. */
    private void registerTree(
            final String name,
            final net.riftbreaker.rifttowny.paper.command.tree.CommandNode root) {
        final PluginCommand command = getCommand(name);
        if (command == null) {
            getLogger().severe("The '" + name + "' command is missing from plugin.yml; "
                    + "it will not work.");
            return;
        }
        final var executor =
                new net.riftbreaker.rifttowny.paper.command.TreeCommandExecutor(root);
        command.setExecutor(executor);
        command.setTabCompleter(executor);
    }

    /** "Folia" or "Paper", as detected rather than as configured. */
    public String platformName() {
        return scheduler != null && scheduler.isFolia() ? "Folia" : "Paper";
    }

    public MessageService messages() {
        return messages;
    }

    public RiftTownySettings settings() {
        return settings;
    }

    public RiftScheduler scheduler() {
        return scheduler;
    }

    public DefaultCapabilityRegistry capabilities() {
        return capabilities;
    }

    public JdbcOutboxRepository outbox() {
        return outbox;
    }

    public JdbcIdempotencyStore idempotencyKeys() {
        return idempotencyKeys;
    }

    public SchemaMigrator.MigrationSummary schema() {
        return schema;
    }

    /** The protection checks, for diagnostics and for anything that needs to ask before acting. */
    public net.riftbreaker.rifttowny.paper.protection.ProtectionService protection() {
        return protection;
    }

    public net.riftbreaker.rifttowny.domain.territory.TerritoryIndex territoryIndex() {
        return territoryIndex;
    }

    /** Brings another plugin's world in. Reached by {@code /rifttowny migrate}. */
    public net.riftbreaker.rifttowny.domain.service.CivicImporter importer() {
        return importer;
    }

    public net.riftbreaker.rifttowny.domain.civic.CivicCache civicCache() {
        return civicCache;
    }

    /** The flag layers. Reached by {@code /rifttowny flag} for the two no town can set. */
    public net.riftbreaker.rifttowny.domain.service.FlagService flags() {
        return flagService;
    }
}
