package net.riftbreaker.rifttowny.api;

import java.util.Optional;
import java.util.UUID;

/** Fast, read-only check that may prevent a resident from taking a plot. */
@FunctionalInterface
public interface ClaimGuard {
    /** Return a user-facing denial reason, or empty to allow this guard to pass. */
    Optional<String> denyReason(UUID residentId, ChunkKey chunk);
}
