package net.riftbreaker.rifttowny.domain.territory;

import net.riftbreaker.rifttowny.api.ChunkKey;
import net.riftbreaker.rifttowny.domain.org.ChangeDenial;

import java.util.Objects;
import java.util.Optional;

/**
 * What a claim or unclaim would do, before it does it.
 *
 * <p>Territory changes cost money and are awkward to reverse, so the player sees the answer first.
 * The preview runs the same rules the real operation does rather than a summary of them — a preview
 * computed a second way is a preview that eventually lies.</p>
 *
 * @param chunk the chunk in question
 * @param kind the kind that was asked for
 * @param permitted whether the operation would be allowed
 * @param denial why not, when it would not
 * @param claimsBefore how many chunks the town holds now
 * @param claimsAfter how many it would hold
 * @param touchesTown whether the chunk shares an edge with the town's existing land
 */
public record ClaimPreview(
        ChunkKey chunk,
        ClaimKind kind,
        boolean permitted,
        ChangeDenial denial,
        int claimsBefore,
        int claimsAfter,
        boolean touchesTown
) {

    public ClaimPreview {
        Objects.requireNonNull(chunk, "chunk");
        Objects.requireNonNull(kind, "kind");
    }

    public Optional<ChangeDenial> refusal() {
        return Optional.ofNullable(denial);
    }

    /** How many chunks the town gains or loses. Negative for an unclaim. */
    public int delta() {
        return claimsAfter - claimsBefore;
    }
}
