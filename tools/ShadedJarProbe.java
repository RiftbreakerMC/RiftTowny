import net.riftbreaker.rifttowny.domain.config.StorageBackend;
import net.riftbreaker.rifttowny.domain.config.StorageSettings;
import net.riftbreaker.rifttowny.domain.outbox.OutboxEvent;
import net.riftbreaker.rifttowny.storage.JdbcOutboxRepository;
import net.riftbreaker.rifttowny.storage.RiftTownyDatabase;
import net.riftbreaker.rifttowny.storage.SchemaMigrator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

/**
 * Proves the shipped jar works, not just that it compiled.
 *
 * Exercises, in one run: the relocated HikariCP pool, the deliberately unrelocated sqlite-jdbc JNI
 * driver, Flyway resolving migrations through the jar's own classloader, and a real outbox
 * round-trip. The JNI relocation trap only fires on the SQLite backend and only from a shaded jar,
 * so this is the one check that would have caught it.
 */
public final class ShadedJarProbe {

    public static void main(final String[] args) throws Exception {
        final Path file = Files.createTempDirectory("rifttowny-probe").resolve("probe.db");

        final StorageSettings settings = new StorageSettings(
                StorageBackend.SQLITE, "jdbc:sqlite:" + file.toAbsolutePath(), "", "", 4, 5_000L);

        try (RiftTownyDatabase database = RiftTownyDatabase.open(settings)) {
            System.out.println("pool opened  : " + database.backend());

            final SchemaMigrator.MigrationSummary summary =
                    new SchemaMigrator(database, ShadedJarProbe.class.getClassLoader()).migrate();
            System.out.println("migrated     : " + summary.describe());

            final JdbcOutboxRepository outbox = new JdbcOutboxRepository(database, Runnable::run);
            final UUID eventId = UUID.randomUUID();
            outbox.append(OutboxEvent.pending(
                    eventId, "probe.event", "{}", "probe", Instant.now())).join();
            outbox.append(OutboxEvent.pending(
                    eventId, "probe.event", "{}", "probe", Instant.now())).join();

            final int claimed = outbox.claimBatch(
                    "probe-server", 10, java.time.Duration.ofMinutes(5)).join().size();
            outbox.markDelivered(eventId).join();

            System.out.println("outbox       : appended twice, stored "
                    + outbox.counts().join().total() + " row(s), claimed " + claimed);
            System.out.println("RESULT       : OK");
        }
    }
}
