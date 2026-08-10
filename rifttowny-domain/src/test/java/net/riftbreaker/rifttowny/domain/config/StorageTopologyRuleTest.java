package net.riftbreaker.rifttowny.domain.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StorageTopologyRuleTest {

    private static StorageSettings sqlite(final int poolSize) {
        return new StorageSettings(
                StorageBackend.SQLITE, "jdbc:sqlite:rifttowny.db", "", "", poolSize, 5_000L);
    }

    private static StorageSettings mariadb() {
        return new StorageSettings(
                StorageBackend.MARIADB, "jdbc:mariadb://db/rifttowny", "rift", "secret", 10, 5_000L);
    }

    @Test
    @DisplayName("SQLite on a single server is allowed")
    void sqliteStandaloneIsAllowed() {
        final List<StartupProblem> problems =
                StorageTopologyRule.evaluate(sqlite(4), NetworkTopology.standalone());

        assertThat(problems).isEmpty();
        assertThat(StorageTopologyRule.blocksStartup(problems)).isFalse();
    }

    @Test
    @DisplayName("SQLite in a shared topology refuses to start, rather than warning")
    void sqliteSharedIsFatal() {
        final List<StartupProblem> problems = StorageTopologyRule.evaluate(
                sqlite(4), new NetworkTopology("survival-1", true));

        assertThat(StorageTopologyRule.blocksStartup(problems)).isTrue();
        assertThat(problems)
                .filteredOn(StartupProblem::fatal)
                .extracting(StartupProblem::setting)
                .contains("storage.backend");
    }

    @Test
    @DisplayName("MariaDB in a shared topology is allowed")
    void mariadbSharedIsAllowed() {
        final List<StartupProblem> problems = StorageTopologyRule.evaluate(
                mariadb(), new NetworkTopology("survival-1", true));

        assertThat(problems).isEmpty();
    }

    @Test
    @DisplayName("A shared install keeping the standalone server id is fatal, because ids would collide")
    void sharedInstallMustNotKeepTheDefaultServerId() {
        final List<StartupProblem> problems = StorageTopologyRule.evaluate(
                mariadb(), new NetworkTopology(NetworkTopology.DEFAULT_SERVER_ID, true));

        assertThat(StorageTopologyRule.blocksStartup(problems)).isTrue();
        assertThat(problems)
                .extracting(StartupProblem::setting)
                .contains("network.server-id");
    }

    @Test
    @DisplayName("A shared install with a blank server id is fatal")
    void sharedInstallNeedsAServerId() {
        final List<StartupProblem> problems = StorageTopologyRule.evaluate(
                mariadb(), new NetworkTopology("   ", true));

        assertThat(StorageTopologyRule.blocksStartup(problems)).isTrue();
    }

    @Test
    @DisplayName("A too-small SQLite pool warns but still starts, and the pool is raised")
    void smallSqlitePoolWarnsAndIsRaised() {
        final StorageSettings settings = sqlite(1);
        final List<StartupProblem> problems =
                StorageTopologyRule.evaluate(settings, NetworkTopology.standalone());

        assertThat(StorageTopologyRule.blocksStartup(problems)).isFalse();
        assertThat(problems).hasSize(1);
        assertThat(problems.getFirst().severity()).isEqualTo(StartupProblem.Severity.WARNING);
        assertThat(settings.effectivePoolSize()).isEqualTo(StorageSettings.MINIMUM_SQLITE_POOL_SIZE);
    }

    @Test
    @DisplayName("A MariaDB pool is never silently resized")
    void mariadbPoolIsNotResized() {
        assertThat(mariadb().effectivePoolSize()).isEqualTo(10);
    }
}
