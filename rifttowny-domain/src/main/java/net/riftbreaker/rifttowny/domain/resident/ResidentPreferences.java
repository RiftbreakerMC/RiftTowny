package net.riftbreaker.rifttowny.domain.resident;

import net.riftbreaker.rifttowny.domain.org.ResidentId;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * What each player has chosen for themselves, in memory.
 *
 * <p>Read on the movement listener — on every chunk a player crosses — so it answers from memory for
 * the same reason the claim index and the diplomacy book do. It is smaller than either: one entry
 * per player who has ever expressed a preference, and most never will.</p>
 *
 * <p><strong>Absent means "never chose", and that is load-bearing.</strong> {@link #noticeFor}
 * returns an empty optional for a player with no entry, and the caller falls back to the server's
 * own setting. A player who explicitly turned notices off has an entry saying {@code OFF}, which
 * survives an operator changing that setting; a player who never touched it moves with the server.
 * Collapsing the two into a boolean at any point loses that distinction permanently.</p>
 *
 * <p>Thread-safe and mutable, like its companions. The facts live in {@code rt_resident_preference},
 * and this is updated after a transaction commits, never inside one.</p>
 */
public final class ResidentPreferences {

    private final Map<ResidentId, NoticePreference> notices = new ConcurrentHashMap<>();

    private final AtomicLong generation = new AtomicLong();

    public static ResidentPreferences empty() {
        return new ResidentPreferences();
    }

    // --- writing -------------------------------------------------------------------------------

    /** Replaces everything, as at startup. */
    public synchronized void replaceAll(final Collection<Choice> loaded) {
        Objects.requireNonNull(loaded, "loaded");
        notices.clear();
        loaded.forEach(choice -> {
            if (choice.notice() != null) {
                notices.put(choice.who(), choice.notice());
            }
        });
        generation.incrementAndGet();
    }

    /** Records a choice. */
    public synchronized void choose(final ResidentId who, final NoticePreference notice) {
        Objects.requireNonNull(who, "who");
        Objects.requireNonNull(notice, "notice");
        notices.put(who, notice);
        generation.incrementAndGet();
    }

    /**
     * Forgets a choice, putting the player back on the server's setting.
     *
     * <p>Removed rather than set to some default value, because the absence <em>is</em> the state.
     * </p>
     */
    public synchronized void clear(final ResidentId who) {
        if (who != null && notices.remove(who) != null) {
            generation.incrementAndGet();
        }
    }

    // --- reading -------------------------------------------------------------------------------

    /**
     * What this player chose, or empty when they never did.
     *
     * <p>The hot path's only question, and one map lookup.</p>
     */
    public Optional<NoticePreference> noticeFor(final ResidentId who) {
        return who == null ? Optional.empty() : Optional.ofNullable(notices.get(who));
    }

    public int size() {
        return notices.size();
    }

    /** Changes on every mutation, so a downstream view can notice without being told. */
    public long generation() {
        return generation.get();
    }

    public String describe() {
        return "ResidentPreferences[chosen=" + notices.size() + ']';
    }

    /**
     * One player's stored choices.
     *
     * @param notice their territory-notice choice, or null when the row holds none
     */
    public record Choice(ResidentId who, NoticePreference notice) {

        public Choice {
            Objects.requireNonNull(who, "who");
        }
    }
}
