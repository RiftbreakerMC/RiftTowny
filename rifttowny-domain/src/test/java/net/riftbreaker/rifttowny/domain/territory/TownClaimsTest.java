package net.riftbreaker.rifttowny.domain.territory;

import net.riftbreaker.rifttowny.api.ChunkKey;
import net.riftbreaker.rifttowny.domain.org.ChangeDenial;
import net.riftbreaker.rifttowny.domain.org.TownId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TownClaimsTest {

    private static final Instant NOW = Instant.parse("2026-08-10T12:00:00Z");
    private static final UUID WORLD = UUID.randomUUID();
    private static final UUID OTHER_WORLD = UUID.randomUUID();
    private static final TownId RIFTHOLM = TownId.random();

    private static ChunkKey at(final int x, final int z) {
        return new ChunkKey(WORLD, x, z);
    }

    /** A town whose homeblock is (0,0), with any extra ordinary claims applied in order. */
    private static TownClaims town(final ChunkKey... ordinary) {
        TownClaims claims = TownClaims.empty(RIFTHOLM)
                .claim(at(0, 0), ClaimKind.HOMEBLOCK, NOW).orElseThrow();
        for (final ChunkKey chunk : ordinary) {
            claims = claims.claim(chunk, ClaimKind.ORDINARY, NOW).orElseThrow();
        }
        return claims;
    }

    @Nested
    @DisplayName("founding territory")
    class Founding {

        @Test
        @DisplayName("the first claim must be the homeblock")
        void firstClaimIsTheHomeblock() {
            final TownClaims empty = TownClaims.empty(RIFTHOLM);

            assertThat(empty.claim(at(0, 0), ClaimKind.ORDINARY, NOW).denial())
                    .contains(ChangeDenial.FIRST_CLAIM_MUST_BE_THE_HOMEBLOCK);
            assertThat(empty.claim(at(0, 0), ClaimKind.OUTPOST, NOW).denial())
                    .contains(ChangeDenial.FIRST_CLAIM_MUST_BE_THE_HOMEBLOCK);
            assertThat(empty.claim(at(0, 0), ClaimKind.HOMEBLOCK, NOW).wasApplied()).isTrue();
        }

        @Test
        @DisplayName("a town has exactly one homeblock")
        void onlyOneHomeblock() {
            assertThat(town().claim(at(1, 0), ClaimKind.HOMEBLOCK, NOW).denial())
                    .contains(ChangeDenial.ALREADY_THE_HOMEBLOCK);
        }

        @Test
        @DisplayName("claiming a chunk the town already owns is refused")
        void doubleClaimIsRefused() {
            assertThat(town().claim(at(0, 0), ClaimKind.ORDINARY, NOW).denial())
                    .contains(ChangeDenial.CHUNK_ALREADY_CLAIMED);
        }

        @Test
        @DisplayName("restoring with two homeblocks fails loudly")
        void twoHomeblocksIsCorrupt() {
            final List<Claim> corrupt = List.of(
                    Claim.of(at(0, 0), RIFTHOLM, ClaimKind.HOMEBLOCK, NOW),
                    Claim.of(at(5, 5), RIFTHOLM, ClaimKind.HOMEBLOCK, NOW));

            assertThatThrownBy(() -> TownClaims.restore(RIFTHOLM, corrupt))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("two homeblocks");
        }

        @Test
        @DisplayName("restoring claims with no homeblock fails loudly")
        void noHomeblockIsCorrupt() {
            final List<Claim> corrupt =
                    List.of(Claim.of(at(0, 0), RIFTHOLM, ClaimKind.ORDINARY, NOW));

            assertThatThrownBy(() -> TownClaims.restore(RIFTHOLM, corrupt))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("no homeblock");
        }

        @Test
        @DisplayName("restoring another town's claim fails loudly")
        void foreignClaimIsCorrupt() {
            final List<Claim> corrupt =
                    List.of(Claim.of(at(0, 0), TownId.random(), ClaimKind.HOMEBLOCK, NOW));

            assertThatThrownBy(() -> TownClaims.restore(RIFTHOLM, corrupt))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("contiguity")
    class Contiguity {

        @Test
        @DisplayName("an ordinary claim must share an edge with the town")
        void ordinaryMustTouch() {
            final TownClaims claims = town();

            assertThat(claims.claim(at(1, 0), ClaimKind.ORDINARY, NOW).wasApplied()).isTrue();
            assertThat(claims.claim(at(5, 5), ClaimKind.ORDINARY, NOW).denial())
                    .contains(ChangeDenial.CLAIM_MUST_TOUCH_TOWN);
        }

        @Test
        @DisplayName("a diagonal touch is not a touch, because you cannot walk across a corner")
        void diagonalsDoNotConnect() {
            assertThat(town().claim(at(1, 1), ClaimKind.ORDINARY, NOW).denial())
                    .contains(ChangeDenial.CLAIM_MUST_TOUCH_TOWN);
        }

        @Test
        @DisplayName("a chunk in another world never touches the town")
        void otherWorldsDoNotConnect() {
            assertThat(town().claim(new ChunkKey(OTHER_WORLD, 0, 1), ClaimKind.ORDINARY, NOW)
                    .denial())
                    .contains(ChangeDenial.CLAIM_MUST_TOUCH_TOWN);
        }

        @Test
        @DisplayName("an outpost must not touch the town, or it is an ordinary claim in disguise")
        void outpostsMustBeDetached() {
            final TownClaims claims = town();

            assertThat(claims.claim(at(1, 0), ClaimKind.OUTPOST, NOW).denial())
                    .contains(ChangeDenial.OUTPOST_MUST_NOT_TOUCH_TOWN);
            assertThat(claims.claim(at(9, 9), ClaimKind.OUTPOST, NOW).wasApplied()).isTrue();
        }

        @Test
        @DisplayName("an outpost anchors its own cluster, so land may grow from it")
        void outpostsAnchorTheirOwnCluster() {
            final TownClaims claims = town()
                    .claim(at(9, 9), ClaimKind.OUTPOST, NOW).orElseThrow();

            assertThat(claims.claim(at(9, 10), ClaimKind.ORDINARY, NOW).wasApplied()).isTrue();
        }

        @Test
        @DisplayName("an outpost may sit in another world entirely")
        void outpostsMayBeInAnotherWorld() {
            assertThat(town().claim(new ChunkKey(OTHER_WORLD, 0, 0), ClaimKind.OUTPOST, NOW)
                    .wasApplied()).isTrue();
        }
    }

    @Nested
    @DisplayName("unclaiming")
    class Unclaiming {

        @Test
        @DisplayName("a leaf claim comes off cleanly")
        void leafClaimsComeOff() {
            final TownClaims claims = town(at(1, 0), at(2, 0));

            assertThat(claims.unclaim(at(2, 0)).orElseThrow().size()).isEqualTo(2);
        }

        @Test
        @DisplayName("unclaiming a chunk the town does not own is refused")
        void unknownChunkIsRefused() {
            assertThat(town().unclaim(at(5, 5)).denial()).contains(ChangeDenial.CHUNK_NOT_CLAIMED);
        }

        @Test
        @DisplayName("the homeblock is the last chunk that may go")
        void homeblockGoesLast() {
            final TownClaims claims = town(at(1, 0));

            assertThat(claims.unclaim(at(0, 0)).denial())
                    .contains(ChangeDenial.HOMEBLOCK_MUST_BE_UNCLAIMED_LAST);
            assertThat(town().unclaim(at(0, 0)).wasApplied())
                    .as("a town down to its homeblock may release it")
                    .isTrue();
        }

        @Test
        @DisplayName("unclaiming the middle of a corridor is refused, not silently severed")
        void severingIsRefused() {
            //  (0,0) - (1,0) - (2,0), all in a line from the homeblock.
            final TownClaims claims = town(at(1, 0), at(2, 0));

            assertThat(claims.unclaim(at(1, 0)).denial())
                    .as("otherwise (2,0) is stranded with no way back to the homeblock")
                    .contains(ChangeDenial.UNCLAIM_WOULD_DISCONNECT);
        }

        @Test
        @DisplayName("a redundant chunk in a loop may go, because the ring still connects")
        void loopsSurviveARemoval() {
            // A 2x2 block: every chunk still reaches the homeblock after any one is removed.
            final TownClaims claims = town(at(1, 0), at(0, 1), at(1, 1));

            assertThat(claims.unclaim(at(1, 1)).wasApplied()).isTrue();
            assertThat(claims.unclaim(at(1, 0)).wasApplied())
                    .as("(1,1) still reaches home through (0,1)")
                    .isTrue();
        }

        @Test
        @DisplayName("removing an outpost strands whatever grew from it")
        void removingAnOutpostStrandsItsCluster() {
            final TownClaims claims = town()
                    .claim(at(9, 9), ClaimKind.OUTPOST, NOW).orElseThrow()
                    .claim(at(9, 10), ClaimKind.ORDINARY, NOW).orElseThrow();

            assertThat(claims.unclaim(at(9, 9)).denial())
                    .contains(ChangeDenial.UNCLAIM_WOULD_DISCONNECT);
            assertThat(claims.unclaim(at(9, 10)).wasApplied())
                    .as("the leaf of the outpost cluster comes off fine")
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("moving the homeblock")
    class MovingHomeblock {

        @Test
        @DisplayName("the homeblock moves to another owned chunk, demoting the old one")
        void moveDemotesTheOldHomeblock() {
            final TownClaims moved = town(at(1, 0)).moveHomeblock(at(1, 0)).orElseThrow();

            assertThat(moved.homeblock().orElseThrow().chunk()).isEqualTo(at(1, 0));
            assertThat(moved.at(at(0, 0)).orElseThrow().kind()).isEqualTo(ClaimKind.ORDINARY);
            assertThat(moved.size()).isEqualTo(2);
        }

        @Test
        @DisplayName("the homeblock cannot move to land the town does not own")
        void cannotMoveToUnownedLand() {
            assertThat(town().moveHomeblock(at(5, 5)).denial())
                    .contains(ChangeDenial.CHUNK_NOT_CLAIMED);
        }

        @Test
        @DisplayName("moving it to itself is refused rather than being a silent no-op")
        void movingToItselfIsRefused() {
            assertThat(town().moveHomeblock(at(0, 0)).denial())
                    .contains(ChangeDenial.ALREADY_THE_HOMEBLOCK);
        }

        @Test
        @DisplayName("a move that would sever the town is refused")
        void severingMoveIsRefused() {
            // Homeblock at (0,0) anchors (1,0). Moving the anchor to the detached outpost at (9,9)
            // leaves (0,0) and (1,0) with nothing to hang from.
            final TownClaims claims = town(at(1, 0))
                    .claim(at(9, 9), ClaimKind.OUTPOST, NOW).orElseThrow();

            assertThat(claims.moveHomeblock(at(9, 9)).denial())
                    .as("the old homeblock stops anchoring, so its cluster would be stranded")
                    .contains(ChangeDenial.UNCLAIM_WOULD_DISCONNECT);
        }
    }

    @Nested
    @DisplayName("previewing")
    class Previewing {

        @Test
        @DisplayName("a preview reports what would happen without changing anything")
        void previewDoesNotMutate() {
            final TownClaims claims = town();

            final ClaimPreview preview = claims.previewClaim(at(1, 0), ClaimKind.ORDINARY);

            assertThat(preview.permitted()).isTrue();
            assertThat(preview.claimsBefore()).isEqualTo(1);
            assertThat(preview.claimsAfter()).isEqualTo(2);
            assertThat(preview.delta()).isEqualTo(1);
            assertThat(preview.touchesTown()).isTrue();
            assertThat(claims.size()).as("the preview must not have claimed anything").isEqualTo(1);
        }

        @Test
        @DisplayName("a refused preview carries the same reason the real call would give")
        void previewCarriesTheReason() {
            final ClaimPreview preview = town().previewClaim(at(5, 5), ClaimKind.ORDINARY);

            assertThat(preview.permitted()).isFalse();
            assertThat(preview.refusal()).contains(ChangeDenial.CLAIM_MUST_TOUCH_TOWN);
            assertThat(preview.delta()).isZero();
        }

        @Test
        @DisplayName("an unclaim preview reports the loss and the severing refusal")
        void unclaimPreview() {
            final TownClaims claims = town(at(1, 0), at(2, 0));

            assertThat(claims.previewUnclaim(at(2, 0)).delta()).isEqualTo(-1);
            assertThat(claims.previewUnclaim(at(1, 0)).refusal())
                    .contains(ChangeDenial.UNCLAIM_WOULD_DISCONNECT);
        }
    }

    @Nested
    @DisplayName("reading")
    class Reading {

        @Test
        @DisplayName("ownership, lookup and counts agree with each other")
        void readingIsConsistent() {
            final TownClaims claims = town(at(1, 0));

            assertThat(claims.owns(at(1, 0))).isTrue();
            assertThat(claims.owns(at(5, 5))).isFalse();
            assertThat(claims.at(at(1, 0)).orElseThrow().kind()).isEqualTo(ClaimKind.ORDINARY);
            assertThat(claims.at(at(5, 5))).isEmpty();
            assertThat(claims.size()).isEqualTo(2);
            assertThat(claims.all()).hasSize(2);
        }

        @Test
        @DisplayName("outposts are listed separately from ordinary land")
        void outpostsAreListed() {
            final TownClaims claims = town(at(1, 0))
                    .claim(at(9, 9), ClaimKind.OUTPOST, NOW).orElseThrow();

            assertThat(claims.outposts()).hasSize(1);
            assertThat(claims.outposts().getFirst().chunk()).isEqualTo(at(9, 9));
        }

        @Test
        @DisplayName("the aggregate is immutable, so a refused change cannot leak")
        void immutability() {
            final TownClaims before = town();

            before.claim(at(1, 0), ClaimKind.ORDINARY, NOW);

            assertThat(before.size()).isEqualTo(1);
        }

        @Test
        @DisplayName("an empty town owns nothing and has no homeblock")
        void emptyTown() {
            final TownClaims empty = TownClaims.empty(RIFTHOLM);

            assertThat(empty.isEmpty()).isTrue();
            assertThat(empty.homeblock()).isEmpty();
            assertThat(empty.touchesTown(at(0, 0))).isFalse();
        }
    }
}
