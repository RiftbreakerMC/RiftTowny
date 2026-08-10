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

    private RiftTownySettings settings;
    private MessageService messages;
    private RiftScheduler scheduler;
    private RiftTownyDatabase database;
    private SchemaMigrator.MigrationSummary schema;
    private JdbcOutboxRepository outbox;
    private JdbcIdempotencyStore idempotencyKeys;
    private DefaultCapabilityRegistry capabilities;

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

        this.messages = new MessageService(
                YamlConfiguration.loadConfiguration(new File(getDataFolder(), "messages.yml")),
                getLogger()::warning);
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
            final RiftTownyCommand executor = new RiftTownyCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }

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

    private void saveResourceIfAbsent(final String name) {
        if (!new File(getDataFolder(), name).exists()) {
            saveResource(name, false);
        }
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
