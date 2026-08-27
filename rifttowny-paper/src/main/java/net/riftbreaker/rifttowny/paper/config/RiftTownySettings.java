package net.riftbreaker.rifttowny.paper.config;

import net.riftbreaker.rifttowny.domain.config.NetworkTopology;
import net.riftbreaker.rifttowny.domain.config.StorageBackend;
import net.riftbreaker.rifttowny.domain.config.StorageSettings;
import org.bukkit.configuration.file.FileConfiguration;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

/**
 * The configuration RiftTowny actually starts with.
 *
 * @param storage where data lives
 * @param topology where this server sits in the network
 * @param outboxRetention how long an undelivered outbox row is kept before the housekeeping sweep
 *        drops it. Zero keeps everything, which is only sane once a drain exists
 * @param townyPermissionAliases whether {@code towny.*} permissions are accepted alongside
 *        {@code rifttowny.*}, for servers migrating an existing permission set
 */
public record RiftTownySettings(
        StorageSettings storage,
        NetworkTopology topology,
        java.time.Duration outboxRetention,
        boolean townyPermissionAliases,
        java.time.Duration ruinLifetime,
        java.time.Duration ruinReclaimDelay,
        java.time.Duration ruinSweepInterval,
        java.time.Duration spawnWarmup,
        java.time.Duration spawnCooldown,
        boolean territoryNotices,
        boolean territoryNoticesOnActionBar,
        net.riftbreaker.rifttowny.domain.bank.CivicPrices prices,
        net.riftbreaker.rifttowny.domain.bank.TaxPolicy taxes,
        TruthWords truthWords,
        net.riftbreaker.rifttowny.domain.directory.TownyPlaceholders.RelationColours relationColours,
        TownyImport townyImport
) {

    /**
     * Where a Towny database lives, for {@code /rifttowny migrate}.
     *
     * <p><strong>Here rather than as command arguments, and the reason is the password.</strong> A
     * credential typed into a command is written to the console log, to any command-logging plugin
     * on the server, and to the sender's own client history — three copies of a database password
     * that nobody meant to make. The master brief's rule is that no secret reaches a log, and a
     * command argument is a log entry.</p>
     *
     * <p>Read-only in every sense: RiftTowny opens this connection to read and never writes a row
     * to it. An account with SELECT and nothing else is the right one to configure.</p>
     */
    public record TownyImport(
            String jdbcUrl,
            String username,
            String password,
            String tablePrefix,
            String dataFolder
    ) {

        public TownyImport {
            jdbcUrl = jdbcUrl == null ? "" : jdbcUrl.trim();
            username = username == null ? "" : username;
            password = password == null ? "" : password;
            tablePrefix = tablePrefix == null || tablePrefix.isBlank() ? "towny_" : tablePrefix.trim();
            dataFolder = dataFolder == null ? "" : dataFolder.trim();
        }

        /** Whether an operator has filled in either route. */
        public boolean isConfigured() {
            return usesSql() || usesFlatfile();
        }

        public boolean usesSql() {
            return !jdbcUrl.isEmpty();
        }

        public boolean usesFlatfile() {
            return !dataFolder.isEmpty();
        }

        /**
         * Whether both routes are configured at once.
         *
         * <p>Refused rather than resolved by precedence. The two could hold different data — a
         * server that moved from flatfile to MySQL has both on disk, and the flatfiles are the
         * stale half. Picking one silently would import the wrong server's history and look like
         * it worked.</p>
         */
        public boolean isAmbiguous() {
            return usesSql() && usesFlatfile();
        }

        /**
         * The connection, with no credentials in it.
         *
         * <p>What gets printed. A JDBC URL can itself carry {@code ?user=&password=}, so the query
         * string is cut off rather than trusted to be harmless.</p>
         */
        public String describe() {
            if (usesFlatfile()) {
                return dataFolder;
            }
            if (!usesSql()) {
                return "not configured";
            }
            final int query = jdbcUrl.indexOf('?');
            return query < 0 ? jdbcUrl : jdbcUrl.substring(0, query);
        }
    }

    /**
     * The words a boolean placeholder renders as.
     *
     * <p>Configurable because they are configurable in Towny, and a server that customised them
     * there has scoreboards written against the customised words. Blank entries fall back rather
     * than producing a boolean that renders as nothing.</p>
     */
    public record TruthWords(String yes, String no) {

        public TruthWords {
            yes = yes == null || yes.isBlank() ? "true" : yes;
            no = no == null || no.isBlank() ? "false" : no;
        }

        public static TruthWords defaults() {
            return new TruthWords("true", "false");
        }
    }

    public RiftTownySettings {
        Objects.requireNonNull(storage, "storage");
        Objects.requireNonNull(topology, "topology");
        Objects.requireNonNull(ruinLifetime, "ruinLifetime");
        Objects.requireNonNull(ruinReclaimDelay, "ruinReclaimDelay");
        Objects.requireNonNull(ruinSweepInterval, "ruinSweepInterval");
        Objects.requireNonNull(spawnWarmup, "spawnWarmup");
        Objects.requireNonNull(spawnCooldown, "spawnCooldown");
        Objects.requireNonNull(prices, "prices");
        Objects.requireNonNull(taxes, "taxes");
    }

    /** Whether a disbanded town leaves a ruin at all. */
    public boolean ruinsEnabled() {
        return !ruinLifetime.isZero() && !ruinLifetime.isNegative();
    }

    /**
     * Reads {@code config.yml}.
     *
     * <p>Builds the JDBC URL here rather than asking the operator for one: a hand-written URL is
     * where the SQLite pragmas and the MariaDB connection properties get silently lost.</p>
     *
     * @param dataFolder the plugin folder, used to resolve a relative SQLite path
     */
    public static RiftTownySettings from(final FileConfiguration config, final Path dataFolder) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(dataFolder, "dataFolder");

        final StorageBackend backend = StorageBackend.parse(config.getString("storage.backend", "sqlite"));
        final int poolSize = config.getInt("storage.pool-size", backend == StorageBackend.SQLITE ? 4 : 10);
        final long timeout = config.getLong("storage.connection-timeout-millis", 5_000L);

        final StorageSettings storage = switch (backend) {
            case SQLITE -> new StorageSettings(
                    backend,
                    "jdbc:sqlite:" + dataFolder
                            .resolve(config.getString("storage.sqlite.file", "rifttowny.db"))
                            .toAbsolutePath(),
                    "", "", poolSize, timeout);
            case MARIADB -> new StorageSettings(
                    backend,
                    "jdbc:mariadb://"
                            + config.getString("storage.mariadb.host", "127.0.0.1") + ':'
                            + config.getInt("storage.mariadb.port", 3306) + '/'
                            + config.getString("storage.mariadb.database", "rifttowny")
                            + "?useUnicode=true&characterEncoding=utf8",
                    config.getString("storage.mariadb.username", ""),
                    config.getString("storage.mariadb.password", ""),
                    poolSize, timeout);
        };

        final NetworkTopology topology = new NetworkTopology(
                config.getString("network.server-id", NetworkTopology.DEFAULT_SERVER_ID),
                config.getBoolean("network.shared", false));

        // Clamped at zero rather than rejected. A negative lifetime is nonsense, and the nearest
        // sensible reading of it is the one the operator can also write on purpose: ruins off.
        final long ruinHours = Math.max(0L, config.getLong("ruins.lifetime-hours", 72L));
        // Clamped to the window rather than refused: a delay longer than the ruin lasts would be a
        // ruin nobody could ever take on, which is a typo rather than an intention.
        final long reclaimHours = Math.min(
                ruinHours, Math.max(0L, config.getLong("ruins.reclaim-after-hours", 24L)));
        // The sweep floor is a minute: a sweep running every few seconds would be a full table scan
        // in a loop to release land nobody is waiting on.
        final long sweepMinutes = Math.max(1L, config.getLong("ruins.sweep-minutes", 15L));

        return new RiftTownySettings(
                storage,
                topology,
                java.time.Duration.ofDays(
                        Math.max(0L, config.getLong("network.outbox-retention-days", 7L))),
                config.getBoolean("permissions.towny-aliases", false),
                java.time.Duration.ofHours(ruinHours),
                java.time.Duration.ofHours(reclaimHours),
                java.time.Duration.ofMinutes(sweepMinutes),
                java.time.Duration.ofSeconds(
                        Math.max(0L, config.getLong("spawn.warmup-seconds", 5L))),
                java.time.Duration.ofSeconds(
                        Math.max(0L, config.getLong("spawn.cooldown-seconds", 60L))),
                config.getBoolean("notices.territory", true),
                config.getBoolean("notices.action-bar", true),
                new net.riftbreaker.rifttowny.domain.bank.CivicPrices(
                        price(config, "prices.town-founding"),
                        price(config, "prices.claim"),
                        price(config, "prices.claim-refund"),
                        price(config, "prices.plot"),
                        price(config, "prices.reclaim"),
                        price(config, "prices.spawn-travel")),
                new net.riftbreaker.rifttowny.domain.bank.TaxPolicy(
                        config.getBoolean("taxes.enabled", false),
                        java.time.Duration.ofHours(
                                Math.max(1L, config.getLong("taxes.interval-hours", 24L))),
                        price(config, "taxes.resident"),
                        price(config, "taxes.upkeep-per-chunk"),
                        price(config, "taxes.nation-per-town"),
                        java.time.Duration.ofHours(
                                Math.max(0L, config.getLong("taxes.grace-hours", 72L)))),
                new TruthWords(
                        config.getString("placeholders.true", "true"),
                        config.getString("placeholders.false", "false")),
                new net.riftbreaker.rifttowny.domain.directory.TownyPlaceholders.RelationColours(
                        config.getString("placeholders.relation-colours.own", ""),
                        config.getString("placeholders.relation-colours.nation", ""),
                        config.getString("placeholders.relation-colours.ally", ""),
                        config.getString("placeholders.relation-colours.enemy", ""),
                        config.getString("placeholders.relation-colours.neutral", "")),
                new TownyImport(
                        config.getString("migration.towny.jdbc-url", ""),
                        config.getString("migration.towny.username", ""),
                        config.getString("migration.towny.password", ""),
                        config.getString("migration.towny.table-prefix", "towny_"),
                        config.getString("migration.towny.data-folder", "")));
    }

    /**
     * Reads one price.
     *
     * <p>Through the string form rather than {@code getDouble}: an operator writes {@code 12.10} and
     * a double turns that into {@code 12.099999999999999}, which is the error the whole ledger is
     * built as {@link java.math.BigDecimal} to avoid. Anything unreadable is zero — a price that
     * cannot be parsed should not become an accidental charge.</p>
     */
    private static java.math.BigDecimal price(final FileConfiguration config, final String path) {
        final String raw = config.getString(path);
        if (raw == null || raw.isBlank()) {
            return java.math.BigDecimal.ZERO;
        }
        try {
            final java.math.BigDecimal parsed = new java.math.BigDecimal(raw.trim());
            return parsed.signum() < 0 ? java.math.BigDecimal.ZERO : parsed;
        } catch (final NumberFormatException notANumber) {
            return java.math.BigDecimal.ZERO;
        }
    }

    /** How spawn travel reads in the startup log. */
    public String describeSpawnTravel() {
        if (spawnWarmup.isZero() && spawnCooldown.isZero()) {
            return "spawn travel is instant and uncapped";
        }
        return "spawn warmup " + spawnWarmup.toSeconds() + "s, cooldown "
                + spawnCooldown.toSeconds() + "s";
    }

    /** How ruins read in the startup log. */
    public String describeRuins() {
        return ruinsEnabled()
                ? "ruins stand for " + ruinLifetime.toHours() + "h, reclaimable after "
                        + ruinReclaimDelay.toHours() + "h, swept every "
                        + ruinSweepInterval.toMinutes() + "m"
                : "ruins disabled";
    }

    /** How the topology reads in {@code /rifttowny status}. */
    public String describeTopology() {
        return topology.shared()
                ? "shared network as '" + topology.serverId() + '\''
                : "single server (" + topology.serverId().toLowerCase(Locale.ROOT) + ')';
    }
}
