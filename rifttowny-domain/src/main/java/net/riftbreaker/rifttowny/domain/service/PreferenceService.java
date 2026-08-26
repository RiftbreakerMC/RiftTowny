package net.riftbreaker.rifttowny.domain.service;

import net.riftbreaker.rifttowny.domain.org.ResidentId;
import net.riftbreaker.rifttowny.domain.resident.NoticePreference;
import net.riftbreaker.rifttowny.domain.resident.ResidentPreferences;
import net.riftbreaker.rifttowny.domain.store.CivicStore;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * What a player has chosen for themselves.
 *
 * <p>No permission check anywhere in here, and that is the whole character of it: these are settings
 * about a player's own screen rather than about a town, so the only person who can change them is
 * the person they belong to, and the command passes their own id. There is nothing for a role to
 * grant and nothing for a mayor to override.</p>
 *
 * <p>Thin, like its neighbours: the rule that matters — that an absent choice means "follow the
 * server" — lives in {@link ResidentPreferences}, and this persists a choice and keeps that current.
 * </p>
 */
public final class PreferenceService {

    private final CivicStore store;
    private final Clock clock;
    private final ResidentPreferences preferences;

    public PreferenceService(
            final CivicStore store, final Clock clock, final ResidentPreferences preferences) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.preferences = Objects.requireNonNull(preferences, "preferences");
    }

    /** The cache, for the listener and {@code /rifttowny status}. */
    public ResidentPreferences preferences() {
        return preferences;
    }

    /** Fills the cache from storage. Called once at enable, beside the other loads. */
    public CompletableFuture<Integer> loadAll() {
        return store.inTransaction(transaction -> {
            final List<ResidentPreferences.Choice> loaded = transaction.preferences().all();
            preferences.replaceAll(loaded);
            return loaded.size();
        });
    }

    /** What this player chose, or empty when they never did. */
    public Optional<NoticePreference> noticeFor(final ResidentId who) {
        return preferences.noticeFor(who);
    }

    /** Records a choice about territory notices. */
    public CompletableFuture<NoticePreference> chooseNotice(
            final ResidentId who, final NoticePreference notice) {
        Objects.requireNonNull(who, "who");
        Objects.requireNonNull(notice, "notice");

        return store.inTransaction(transaction -> {
            transaction.preferences().save(
                    new ResidentPreferences.Choice(who, notice), clock.instant());
            return notice;
        }).thenApply(stored -> {
            // After the commit, never inside it - the same rule every book here follows. A
            // rolled-back choice that had already reached the cache would leave a player silenced
            // by a preference the database never took.
            preferences.choose(who, stored);
            return stored;
        });
    }

    /**
     * Forgets a choice, putting the player back on whatever the server does.
     *
     * <p>The row is deleted rather than set to a default value. A stored default would pin the
     * operator's setting as it stood at that moment, which is the opposite of what the player asked
     * for by clearing it.</p>
     */
    public CompletableFuture<Boolean> clearNotice(final ResidentId who) {
        Objects.requireNonNull(who, "who");
        return store.inTransaction(transaction -> transaction.preferences().clear(who))
                .thenApply(removed -> {
                    preferences.clear(who);
                    return removed;
                });
    }
}
