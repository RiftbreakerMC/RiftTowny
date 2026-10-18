package net.riftbreaker.rifttowny.api;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TownInfoTest {
    @Test
    void townIdentityDoesNotChangeWhenItsNameChanges() {
        UUID id = UUID.randomUUID();

        assertThat(new TownInfo(id, "Old Name"))
                .isEqualTo(new TownInfo(id, "New Name"))
                .hasSameHashCodeAs(new TownInfo(id, "Another Name"));
    }
}
