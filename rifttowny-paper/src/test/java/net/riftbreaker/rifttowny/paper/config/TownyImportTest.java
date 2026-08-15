package net.riftbreaker.rifttowny.paper.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Towny import connection, and the one thing about it that must not leak.
 *
 * <p>{@code describe()} is what reaches chat and the server log when a migration runs. A JDBC URL
 * is allowed to carry credentials in its query string, so "print the URL" and "print no secrets"
 * are only the same instruction if something actually cuts the query off.</p>
 */
class TownyImportTest {

    private static RiftTownySettings.TownyImport connection(final String url) {
        return new RiftTownySettings.TownyImport(url, "root", "hunter2", null, "");
    }

    @Test
    @DisplayName("a plain URL is printed as it is")
    void plainUrlsArePrinted() {
        assertThat(connection("jdbc:mariadb://127.0.0.1:3306/towny").describe())
                .isEqualTo("jdbc:mariadb://127.0.0.1:3306/towny");
    }

    @Test
    @DisplayName("credentials in the query string are cut off, not printed")
    void queryStringsAreCut() {
        // The case this exists for: MySQL and MariaDB both accept ?user=&password= in the URL, and
        // an operator who configured it that way would otherwise see it echoed into chat.
        final String described = connection(
                "jdbc:mariadb://db:3306/towny?user=root&password=hunter2").describe();

        assertThat(described)
                .isEqualTo("jdbc:mariadb://db:3306/towny")
                .doesNotContain("hunter2")
                .doesNotContain("password");
    }

    @Test
    @DisplayName("the configured password is never in the description either")
    void thePasswordFieldIsNeverDescribed() {
        assertThat(connection("jdbc:mariadb://db:3306/towny").describe())
                .doesNotContain("hunter2")
                .doesNotContain("root");
    }

    @Test
    @DisplayName("an unconfigured connection says so rather than printing an empty string")
    void unconfiguredIsNamed() {
        final RiftTownySettings.TownyImport blank =
                new RiftTownySettings.TownyImport("", "", "", "", "");

        assertThat(blank.isConfigured()).isFalse();
        assertThat(blank.describe()).isEqualTo("not configured");
    }

    @Test
    @DisplayName("a blank prefix falls back to Towny's own default")
    void prefixDefaults() {
        assertThat(new RiftTownySettings.TownyImport("jdbc:x", "", "", "  ", "").tablePrefix())
                .isEqualTo("towny_");
        assertThat(new RiftTownySettings.TownyImport("jdbc:x", "", "", "tny_", "").tablePrefix())
                .isEqualTo("tny_");
    }

    @Test
    @DisplayName("nulls do not become the string 'null' in a connection")
    void nullsAreHandled() {
        final RiftTownySettings.TownyImport nulls =
                new RiftTownySettings.TownyImport(null, null, null, null, null);

        assertThat(nulls.isConfigured()).isFalse();
        assertThat(nulls.username()).isEmpty();
        assertThat(nulls.password()).isEmpty();
    }

    @Test
    @DisplayName("configuring both routes at once is ambiguous rather than resolved by precedence")
    void bothRoutesIsAmbiguous() {
        // A server that moved from flatfile to MySQL still has the old files on disk, and they are
        // the stale half. Picking one silently would import the wrong history and look like it had
        // worked.
        final RiftTownySettings.TownyImport both = new RiftTownySettings.TownyImport(
                "jdbc:mariadb://db/towny", "", "", null, "plugins/Towny/data");

        assertThat(both.isAmbiguous()).isTrue();
        assertThat(both.usesSql()).isTrue();
        assertThat(both.usesFlatfile()).isTrue();
    }

    @Test
    @DisplayName("a flatfile route describes its folder and is not ambiguous on its own")
    void flatfileRouteIsRecognised() {
        final RiftTownySettings.TownyImport flatfile =
                new RiftTownySettings.TownyImport("", "", "", null, "plugins/Towny/data");

        assertThat(flatfile.isConfigured()).isTrue();
        assertThat(flatfile.usesFlatfile()).isTrue();
        assertThat(flatfile.isAmbiguous()).isFalse();
        assertThat(flatfile.describe()).isEqualTo("plugins/Towny/data");
    }
}
