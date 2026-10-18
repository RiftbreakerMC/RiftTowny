package net.riftbreaker.rifttowny.api;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TownInfoTest {
    @Test
    void townIdentityDoesNotChangeWhenItsNameChanges() {
        UUID id = UUID.randomUUID();
        TownInfo oldName = new TownInfo(id, "Old Name");
        TownInfo newName = new TownInfo(id, "New Name");

        assertThat(oldName).isEqualTo(newName);
        assertThat(oldName.hashCode()).isEqualTo(newName.hashCode());
    }
}
