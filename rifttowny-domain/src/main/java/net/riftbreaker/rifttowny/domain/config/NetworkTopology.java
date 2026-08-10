package net.riftbreaker.rifttowny.domain.config;

import java.util.Objects;

/**
 * Where this server sits in the network.
 *
 * @param serverId this backend's stable identifier, used for outbox ownership, advisory leases and
 *                 audit rows. Must be unique across the network and stable across restarts
 * @param shared whether more than one backend server shares this database
 */
public record NetworkTopology(String serverId, boolean shared) {

    /** The identifier used when an operator has not set one and the install is single-server. */
    public static final String DEFAULT_SERVER_ID = "standalone";

    public NetworkTopology {
        Objects.requireNonNull(serverId, "serverId");
        serverId = serverId.trim();
    }

    public static NetworkTopology standalone() {
        return new NetworkTopology(DEFAULT_SERVER_ID, false);
    }
}
