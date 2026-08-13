package net.riftbreaker.rifttowny.domain.civic;

import net.riftbreaker.rifttowny.domain.org.ResidentId;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What to call a player, without asking the database.
 *
 * <p>Everything RiftTowny shows about people is stored as a UUID, because a name is not an identity
 * — players change theirs, and a town whose mayor renamed themselves must still have a mayor. That
 * leaves every listing with the same problem: a UUID tells a player nothing, and the lookup that
 * turns it into a name is a query.</p>
 *
 * <p>For a command a query is fine. For the border notice it is not: that runs on movement and must
 * answer from memory or not at all, which is why an unnamed holder there reads as "another
 * resident" rather than blocking. This cache is what lets both use the same answer.</p>
 *
 * <p><strong>Bounded by town membership, not by accounts.</strong> Every resident of every town is
 * held — a few thousand entries on a large server, the same order as the civic cache — and anybody
 * who has never joined a town is not, because nothing names them. Online players are added on join
 * whether they belong to a town or not, since they are the ones most likely to be looked at.</p>
 */
public final class ResidentNames {

    private final Map<ResidentId, String> names = new ConcurrentHashMap<>();

    public static ResidentNames empty() {
        return new ResidentNames();
    }

    /** Replaces everything, as at startup. */
    public synchronized void replaceAll(final Map<ResidentId, String> loaded) {
        Objects.requireNonNull(loaded, "loaded");
        names.keySet().retainAll(loaded.keySet());
        names.putAll(loaded);
    }

    /** Records a name, as on join or when a resident is saved. */
    public void remember(final ResidentId who, final String name) {
        if (who != null && name != null && !name.isBlank()) {
            names.put(who, name);
        }
    }

    /** The name, if one is known. */
    public Optional<String> of(final ResidentId who) {
        return who == null ? Optional.empty() : Optional.ofNullable(names.get(who));
    }

    /**
     * The name, or a stand-in.
     *
     * <p>Never the UUID. A player shown thirty-six characters of hex has been told nothing and will
     * report it as a bug; "someone" at least reads as an answer.</p>
     */
    public String describe(final ResidentId who) {
        return of(who).orElse("someone");
    }

    public int size() {
        return names.size();
    }
}
