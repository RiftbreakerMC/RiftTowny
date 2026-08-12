package net.riftbreaker.rifttowny.paper.protection;

import net.riftbreaker.rifttowny.api.ChunkKey;
import net.riftbreaker.rifttowny.domain.flag.ProtectionFlag;
import net.riftbreaker.rifttowny.domain.flag.ProtectionQuery;
import net.riftbreaker.rifttowny.domain.flag.ProtectionQuery.ProtectionAnswer;
import net.riftbreaker.rifttowny.domain.org.ResidentId;
import net.riftbreaker.rifttowny.domain.org.TownId;
import net.riftbreaker.rifttowny.domain.role.Permission;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * What every protection listener calls.
 *
 * <p>The listeners are deliberately thin — they translate a Bukkit event into a flag and a position
 * and nothing else. Everything that decides an outcome lives here or below: the bypass, the query,
 * the message. That is what keeps the rules identical whether a block was broken with a pickaxe, a
 * piston or a command, and it is why the rules are testable without a server.</p>
 *
 * <p><strong>Every call is synchronous and allocation-light.</strong> These run inside block-break
 * and movement events, so nothing here touches storage, and on Folia nothing here touches state
 * belonging to another region.</p>
 */
public final class ProtectionService {

    /** Ignores every protection rule. Deliberately not a child of {@code rifttowny.admin}. */
    public static final String BYPASS_PERMISSION = "rifttowny.bypass";

    private final ProtectionQuery query;
    private final ProtectionMessenger messenger;
    private final AtomicLong checks = new AtomicLong();
    private final AtomicLong refusals = new AtomicLong();
    private final AtomicLong bypasses = new AtomicLong();

    public ProtectionService(final ProtectionQuery query, final ProtectionMessenger messenger) {
        this.query = Objects.requireNonNull(query, "query");
        this.messenger = Objects.requireNonNull(messenger, "messenger");
    }

    // --- what a player does --------------------------------------------------------------------

    /** Whether a player may act on a block, explaining the refusal to them if not. */
    public boolean allows(final Player player, final Block block, final ProtectionFlag flag) {
        return allows(player, Chunks.of(block), flag, ProtectionQuery.permissionFor(flag).orElse(null));
    }

    /**
     * The same, with the role permission chosen by the caller.
     *
     * <p>For actions one flag covers but one permission does not: an armour stand and a lever are
     * both {@link ProtectionFlag#INTERACT}, and a town that lets members flip levers has not thereby
     * agreed to let them rearrange the statues.</p>
     */
    public boolean allows(
            final Player player,
            final ChunkKey chunk,
            final ProtectionFlag flag,
            final Permission permission
    ) {
        if (player == null) {
            return allowsWorldAction(chunk, flag);
        }
        if (player.hasPermission(BYPASS_PERMISSION)) {
            bypasses.incrementAndGet();
            return true;
        }
        checks.incrementAndGet();

        final ProtectionAnswer answer =
                query.mayAct(ResidentId.of(player.getUniqueId()), chunk, flag, permission);
        if (answer.denied()) {
            refusals.incrementAndGet();
            messenger.explain(player, answer);
            return false;
        }
        return true;
    }

    /** Whether a player may act at a position, using the flag's own permission. */
    public boolean allows(final Player player, final ChunkKey chunk, final ProtectionFlag flag) {
        return allows(player, chunk, flag, ProtectionQuery.permissionFor(flag).orElse(null));
    }

    // --- what the world does -------------------------------------------------------------------

    /**
     * Whether something with no player behind it may happen here.
     *
     * <p>Explosions, fire, fluids and pistons. No bypass applies — there is nobody holding a
     * permission — and no message is sent, because there is nobody to send it to.</p>
     */
    public boolean allowsWorldAction(final ChunkKey chunk, final ProtectionFlag flag) {
        checks.incrementAndGet();
        final ProtectionAnswer answer = query.mayAct(null, chunk, flag, null);
        if (answer.denied()) {
            refusals.incrementAndGet();
            return false;
        }
        return true;
    }

    public boolean allowsWorldAction(final Block block, final ProtectionFlag flag) {
        return allowsWorldAction(Chunks.of(block), flag);
    }

    // --- reading -------------------------------------------------------------------------------

    /** Who owns a chunk, if anybody. */
    public Optional<TownId> ownerAt(final ChunkKey chunk) {
        return query.ownerAt(chunk);
    }

    public ProtectionQuery query() {
        return query;
    }

    /** Counters for {@code /rifttowny status}, so protection is observable rather than assumed. */
    public Statistics statistics() {
        return new Statistics(checks.get(), refusals.get(), bypasses.get());
    }

    /**
     * @param bypasses not a warning on its own: a staff member building in a town they do not belong
     *        to is ordinary. A large number from one server is worth an operator's attention
     */
    public record Statistics(long checks, long refusals, long bypasses) {

        public String describe() {
            return checks + " check(s), " + refusals + " refused, " + bypasses + " bypassed";
        }
    }
}
