package net.riftbreaker.rifttowny.domain.service;

import net.riftbreaker.rifttowny.domain.civic.ResidentNames;
import net.riftbreaker.rifttowny.domain.org.Resident;
import net.riftbreaker.rifttowny.domain.org.ResidentId;
import net.riftbreaker.rifttowny.domain.store.CivicStore;

import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Keeps the name cache current, and keeps the stored name current with it.
 *
 * <p>Two jobs that are really one. A player's name is recorded when RiftTowny first sees them and
 * never again unless something writes it, so a player who renames themselves would be listed under
 * the old one indefinitely — the row says {@code last_known_name} and would be lying. Seeing them
 * join is the moment to fix both.</p>
 */
public final class ResidentNameService {

    private final CivicStore store;
    private final Clock clock;
    private final ResidentNames names;

    public ResidentNameService(
            final CivicStore store, final Clock clock, final ResidentNames names) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.names = Objects.requireNonNull(names, "names");
    }

    public ResidentNames cache() {
        return names;
    }

    /** Fills the cache from storage. Called once at enable. */
    public CompletableFuture<Integer> loadAll() {
        return store.inTransaction(transaction -> {
            final var loaded = transaction.residents().namesOfTownMembers();
            names.replaceAll(loaded);
            return loaded.size();
        });
    }

    /**
     * Records that this player is here, under this name.
     *
     * <p>Writes only when something actually changed. A join is a common event and a rename is a
     * rare one; an unconditional write would be a database round trip per login for nothing.</p>
     *
     * <p>A player RiftTowny has never seen is <strong>not</strong> created here. A resident row is
     * what a town's membership hangs on, and minting one for everybody who logs in would fill the
     * table with people who never join anything. They are created when they first do something.</p>
     */
    public CompletableFuture<Boolean> seen(final ResidentId who, final String currentName) {
        Objects.requireNonNull(who, "who");
        if (currentName == null || currentName.isBlank()) {
            return CompletableFuture.completedFuture(false);
        }
        names.remember(who, currentName);

        return store.inTransaction(transaction -> {
            final var found = transaction.residents().find(who);
            if (found.isEmpty()) {
                return false;
            }
            final Resident resident = found.get();
            final boolean renamed = !resident.lastKnownName().equals(currentName);
            // The last-seen stamp moves on every join; the name only when it actually changed.
            transaction.residents().save(
                    renamed
                            ? resident.renamedTo(currentName).seenAt(clock.instant())
                            : resident.seenAt(clock.instant()));
            return renamed;
        });
    }
}
