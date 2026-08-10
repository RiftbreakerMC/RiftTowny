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
    private net.riftbreaker.rifttowny.domain.service.TownService townService;
    private net.riftbreaker.rifttowny.domain.service.TownRoleService townRoleService;
    private net.riftbreaker.rifttowny.paper.message.DenialText denialText;

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

        registerTree("town", new net.riftbreaker.rifttowny.paper.command.TownCommands(
                townService, townRoleService, residentRepository, townRepository,
                messages, denialText).tree());

        getLogger().info("RiftTowny enabled on " + platformName() + ". " + schema.describe() + '.');
        getLogger().info("Storage: " + settings.storage().describeForLog()
                + ", topology: " + settings.describeTopology() + '.');
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
            this.civicStore =
                    new net.riftbreaker.rifttowny.storage.JdbcCivicStore(database, storageExecutor);

            final java.time.Clock clock = java.time.Clock.systemUTC();
            this.townService = new net.riftbreaker.rifttowny.domain.service.TownService(
                    civicStore,
                    net.riftbreaker.rifttowny.domain.naming.NamePolicy.defaults(),
                    clock);
            this.townRoleService = new net.riftbreaker.rifttowny.domain.service.TownRoleService(
                    civicStore, clock, lockedPermissions());
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
}
