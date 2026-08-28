package net.riftbreaker.rifttowny.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the pragmas, because getting them wrong is silent.
 *
 * <p>They were previously passed through Hikari's {@code connectionInitSql} as a semicolon-separated
 * list, which runs only the first statement. The pool started, WAL was on, and foreign keys were
 * off — invisible until a cascade was relied on, by which point the orphan rows already existed.
 * These assertions run against a pooled connection, so they check what the application actually
 * gets rather than what the configuration intended.</p>
 */
class SqlitePragmaTest extends SqliteFixture {

    private int pragma(final String name) throws Exception {
        return database.read(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("PRAGMA " + name);
                 ResultSet results = statement.executeQuery()) {
                return results.next() ? results.getInt(1) : -1;
            }
        });
    }

    private String textPragma(final String name) throws Exception {
        return database.read(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("PRAGMA " + name);
                 ResultSet results = statement.executeQuery()) {
                return results.next() ? results.getString(1) : null;
            }
        });
    }

    @Test
    @DisplayName("foreign keys are enforced on a pooled connection")
    void foreignKeysAreOn() throws Exception {
        assertThat(pragma("foreign_keys"))
                .as("without this every ON DELETE CASCADE in the schema is decorative")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("the journal is WAL, so a reader does not block a writer")
    void journalIsWal() throws Exception {
        assertThat(textPragma("journal_mode")).isEqualToIgnoringCase("wal");
    }

    @Test
    @DisplayName("a busy timeout is set, so a concurrent writer waits instead of failing")
    void busyTimeoutIsSet() throws Exception {
        assertThat(pragma("busy_timeout")).isPositive();
    }

    @Test
    @DisplayName("a cascade actually deletes the child rows")
    void cascadesReallyCascade() throws Exception {
        final String town = java.util.UUID.randomUUID().toString();
        final String role = java.util.UUID.randomUUID().toString();

        database.write(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO rt_role (role_id, scope, organisation_id, name, name_normalised, "
                            + "display_name, priority, created_at) VALUES (?, 'TOWN', ?, 'Officer', "
                            + "'officer', 'Officer', 500, 0)")) {
                statement.setString(1, role);
                statement.setString(2, town);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO rt_role_permission (role_id, permission) "
                            + "VALUES (?, 'BUILD')")) {
                statement.setString(1, role);
                statement.executeUpdate();
            }
            return null;
        });

        database.write(connection -> {
            try (PreparedStatement statement =
                         connection.prepareStatement("DELETE FROM rt_role WHERE role_id = ?")) {
                statement.setString(1, role);
                statement.executeUpdate();
            }
            return null;
        });

        final int orphans = database.read(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT COUNT(*) FROM rt_role_permission WHERE role_id = ?")) {
                statement.setString(1, role);
                try (ResultSet results = statement.executeQuery()) {
                    return results.next() ? results.getInt(1) : -1;
                }
            }
        });

        assertThat(orphans)
                .as("an orphaned permission row would resurrect if the role id were ever reused")
                .isZero();
    }
}
