package net.riftbreaker.rifttowny.storage;

import net.riftbreaker.rifttowny.domain.config.StorageBackend;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * The two dialects must describe the same schema.
 *
 * <p>{@code SchemaMigratorTest} checks that both carry the same <em>number</em> of migrations, which
 * says nothing about what is in them: two files numbered alike can add different columns, or drop a
 * column on one side and not the other, and the count stays equal. Only SQLite is ever executed in
 * these tests, because MariaDB needs a server, so a divergence would first be seen in production on
 * the backend nothing here can run.
 *
 * <p>This replays the DDL of both and compares the table and column names each ends with. Types are
 * deliberately not compared: {@code INTEGER} against {@code INT}, {@code REAL} against
 * {@code DOUBLE} and {@code TINYINT(1)} against {@code INTEGER} are all correct differences, and a
 * test that flagged them would be switched off within a week. Names are the part that cannot
 * legally differ.
 *
 * <p><strong>An unrecognised statement fails the test.</strong> That matters more than the
 * comparison itself: a parser that quietly skipped what it did not understand would report two
 * schemas as identical by understanding neither, which is the failure mode of every homemade SQL
 * reader. If a migration starts using a shape this does not know, the test says so and asks to be
 * taught rather than passing.
 *
 * <p>What it does not do is check that the MariaDB syntax is valid. Only MariaDB can say that.
 */
class SchemaDialectParityTest {

    @Test
    @DisplayName("both dialects define the same tables and columns")
    void dialectsAgree() throws Exception {
        final Map<String, TreeSet<String>> sqlite = schemaOf(StorageBackend.SQLITE);
        final Map<String, TreeSet<String>> mariadb = schemaOf(StorageBackend.MARIADB);

        assertThat(sqlite)
                .as("a dialect defining no tables means the parser found nothing to compare")
                .isNotEmpty();
        assertThat(mariadb.keySet())
                .as("tables differ between the dialects")
                .containsExactlyInAnyOrderElementsOf(sqlite.keySet());
        for (final Map.Entry<String, TreeSet<String>> table : sqlite.entrySet()) {
            assertThat(mariadb.get(table.getKey()))
                    .as("columns of %s differ between the dialects", table.getKey())
                    .containsExactlyInAnyOrderElementsOf(table.getValue());
        }
    }

    @Test
    @DisplayName("both dialects carry the same migration filenames, not merely as many")
    void filenamesAgree() throws Exception {
        assertThat(namesOf(StorageBackend.MARIADB))
                .as("a version present on one side only, or described differently, is a drift the "
                        + "count check cannot see")
                .containsExactlyElementsOf(namesOf(StorageBackend.SQLITE));
    }

    // --- replaying the DDL ---------------------------------------------------------------------

    private Map<String, TreeSet<String>> schemaOf(final StorageBackend backend) throws Exception {
        final Map<String, TreeSet<String>> schema = new LinkedHashMap<>();
        for (final File file : filesOf(backend)) {
            final String sql =
                    stripComments(Files.readString(file.toPath(), StandardCharsets.UTF_8));
            for (final String statement : sql.split(";")) {
                apply(schema, normalise(statement), file.getName(), backend);
            }
        }
        return schema;
    }

    private void apply(
            final Map<String, TreeSet<String>> schema,
            final String statement,
            final String file,
            final StorageBackend backend
    ) {
        if (statement.isBlank()) {
            return;
        }
        final String upper = statement.toUpperCase(Locale.ROOT);
        if (upper.startsWith("CREATE INDEX") || upper.startsWith("CREATE UNIQUE INDEX")
                || upper.startsWith("INSERT INTO") || upper.startsWith("UPDATE ")
                || upper.startsWith("DROP INDEX ")) {
            // Neither adds nor removes a name. Index and data differences between the dialects are
            // real, but they are not what this compares.
            return;
        }
        if (upper.startsWith("DROP TABLE ")) {
            schema.remove(word(statement, 2));
            return;
        }
        if (upper.startsWith("CREATE TABLE ")) {
            createTable(schema, statement);
            return;
        }
        if (upper.startsWith("ALTER TABLE ")) {
            alterTable(schema, statement, file, backend);
            return;
        }
        fail("Unrecognised statement in %s (%s), so the dialects cannot be compared. Teach the "
                + "parser this shape rather than letting it be ignored: %s", file, backend,
                statement);
    }

    private static void createTable(
            final Map<String, TreeSet<String>> schema, final String statement) {
        final String table = word(statement, 2);
        final String body =
                statement.substring(statement.indexOf('(') + 1, statement.lastIndexOf(')'));
        final TreeSet<String> columns = new TreeSet<>();
        for (final String part : splitTopLevel(body)) {
            final String trimmed = part.trim();
            if (!trimmed.isEmpty() && !isConstraint(trimmed)) {
                columns.add(word(trimmed, 0).toLowerCase(Locale.ROOT));
            }
        }
        schema.put(table, columns);
    }

    private void alterTable(
            final Map<String, TreeSet<String>> schema,
            final String statement,
            final String file,
            final StorageBackend backend
    ) {
        final String table = word(statement, 2);
        final String rest =
                statement.substring(statement.indexOf(table) + table.length()).trim();

        if (rest.toUpperCase(Locale.ROOT).startsWith("RENAME TO ")) {
            schema.put(word(rest, 2), schema.remove(table));
            return;
        }
        final TreeSet<String> columns = schema.get(table);
        if (columns == null) {
            fail("%s (%s) alters %s before it exists", file, backend, table);
            return;
        }
        for (final String clause : splitTopLevel(rest)) {
            final String trimmed = clause.trim();
            final String upper = trimmed.toUpperCase(Locale.ROOT);
            if (upper.startsWith("ADD COLUMN ")) {
                columns.add(word(trimmed, 2).toLowerCase(Locale.ROOT));
            } else if (upper.startsWith("DROP COLUMN ")) {
                final String column = word(trimmed, 2).toLowerCase(Locale.ROOT);
                if (!columns.remove(column)) {
                    fail("%s (%s) drops %s.%s, which was never defined",
                            file, backend, table, column);
                }
            } else if (upper.startsWith("DROP INDEX ") || upper.startsWith("ADD INDEX ")
                    || upper.startsWith("ADD CONSTRAINT ") || upper.startsWith("DROP CONSTRAINT ")) {
                // Indexes and constraints are where the dialects legitimately diverge in shape:
                // MariaDB drops an index by altering its table, SQLite by a statement of its own.
                // Neither changes a column name, which is what this compares.
                continue;
            } else if (!trimmed.isEmpty()) {
                fail("Unrecognised ALTER clause in %s (%s): %s", file, backend, trimmed);
            }
        }
    }

    // --- text ----------------------------------------------------------------------------------

    /** Splits on commas outside brackets, so a DECIMAL(20, 4) stays one part. */
    private static List<String> splitTopLevel(final String body) {
        final List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < body.length(); i++) {
            final char c = body.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == ',' && depth == 0) {
                parts.add(body.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(body.substring(start));
        return parts;
    }

    /**
     * Whether a part of a CREATE TABLE body declares something other than a column.
     *
     * <p>{@code INDEX} and {@code KEY} are here because MariaDB declares indexes inside the table
     * and SQLite declares them afterwards. That is the one place the two files legitimately differ
     * in shape rather than in spelling, and reading MariaDB's inline index as a column called
     * "index" is exactly what this parser did before it was told.</p>
     */
    private static boolean isConstraint(final String part) {
        final String upper = part.toUpperCase(Locale.ROOT);
        return upper.startsWith("CONSTRAINT ") || upper.startsWith("PRIMARY KEY")
                || upper.startsWith("UNIQUE") || upper.startsWith("FOREIGN KEY")
                || upper.startsWith("CHECK") || upper.startsWith("INDEX ")
                || upper.startsWith("KEY ");
    }

    private static String stripComments(final String sql) {
        final StringBuilder kept = new StringBuilder(sql.length());
        for (final String line : sql.split("\n")) {
            if (!line.trim().startsWith("--")) {
                kept.append(line).append('\n');
            }
        }
        return kept.toString();
    }

    private static String normalise(final String statement) {
        return statement.replace('\n', ' ').replaceAll("\\s+", " ").trim();
    }

    private static String word(final String text, final int index) {
        final String[] words = text.trim().split("[\\s(]+");
        return index < words.length ? words[index] : "";
    }

    private static List<String> namesOf(final StorageBackend backend) throws Exception {
        return Arrays.stream(filesOf(backend)).map(File::getName).sorted().toList();
    }

    private static File[] filesOf(final StorageBackend backend) throws Exception {
        final String location = SchemaMigrator.locationFor(backend).replace("classpath:", "");
        final java.net.URL directory =
                SchemaDialectParityTest.class.getClassLoader().getResource(location);
        assertThat(directory).as("migration directory for %s", backend).isNotNull();
        final File[] files =
                new File(directory.toURI()).listFiles((dir, name) -> name.endsWith(".sql"));
        assertThat(files).as("migrations for %s", backend).isNotNull();
        Arrays.sort(files, java.util.Comparator.comparingInt(SchemaDialectParityTest::versionOf));
        return files;
    }

    /** Orders V2 before V10, which a plain name sort would not. */
    private static int versionOf(final File file) {
        final String name = file.getName();
        return Integer.parseInt(name.substring(1, name.indexOf("__")));
    }
}
