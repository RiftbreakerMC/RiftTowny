package net.riftbreaker.rifttowny.storage;

import net.riftbreaker.rifttowny.domain.config.StorageBackend;
import net.riftbreaker.rifttowny.domain.config.StorageSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.Executor;

/**
 * A migrated, throwaway SQLite database per test.
 *
 * <p>The pool is closed in {@link #closeDatabase()} rather than left to garbage collection. JUnit
 * deletes a {@code @TempDir} after the test method, and a pooled SQLite connection keeps the file
 * open — on Windows that makes the delete fail and the whole test class error out after passing.
 * {@code @AfterEach} runs before the temp directory is removed, so closing here is what makes the
 * cleanup succeed.</p>
 *
 * <p>Work runs on the calling thread. These tests assert what the SQL does, not that the executor
 * is asynchronous, and a same-thread executor keeps failures on the test's own stack.</p>
 */
abstract class SqliteFixture {

    /** Same-thread, so an assertion failure has the SQL that caused it in its stack trace. */
    protected static final Executor DIRECT = Runnable::run;

    @TempDir
    Path directory;

    protected RiftTownyDatabase database;

    @BeforeEach
    void openDatabase() {
        final Path file = directory.resolve("rifttowny-test.db");
        final StorageSettings settings = new StorageSettings(
                StorageBackend.SQLITE,
                "jdbc:sqlite:" + file.toAbsolutePath(),
                "",
                "",
                StorageSettings.MINIMUM_SQLITE_POOL_SIZE,
                5_000L);

        database = RiftTownyDatabase.open(settings);
        new SchemaMigrator(database, SqliteFixture.class.getClassLoader()).migrate();
    }

    @AfterEach
    void closeDatabase() {
        if (database != null) {
            database.close();
            database = null;
        }
    }
}
