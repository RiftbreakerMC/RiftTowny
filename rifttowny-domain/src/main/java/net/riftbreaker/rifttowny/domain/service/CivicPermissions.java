package net.riftbreaker.rifttowny.domain.service;

import net.riftbreaker.rifttowny.domain.org.ChangeDenial;
import net.riftbreaker.rifttowny.domain.store.ChangeRefusedException;
import net.riftbreaker.rifttowny.domain.store.CivicTransaction;
import net.riftbreaker.rifttowny.domain.org.Nation;
import net.riftbreaker.rifttowny.domain.org.NationId;
import net.riftbreaker.rifttowny.domain.org.OrganisationScope;
import net.riftbreaker.rifttowny.domain.org.Resident;
import net.riftbreaker.rifttowny.domain.org.ResidentId;
import net.riftbreaker.rifttowny.domain.org.Town;
import net.riftbreaker.rifttowny.domain.org.TownId;
import net.riftbreaker.rifttowny.domain.role.Permission;
import net.riftbreaker.rifttowny.domain.role.RoleBook;
import net.riftbreaker.rifttowny.domain.role.SystemRole;

import java.util.Objects;
import java.util.Optional;

/**
 * May this player do this, here.
 *
 * <p>One copy, because there were two and they had already drifted. {@code NationService} refused a
 * nation with no role book as {@code ROLE_NOT_FOUND} and {@code DiplomacyService} refused the same
 * state as {@code MISSING_PERMISSION} — harmless in that both refuse, and exactly the shape of
 * divergence that stops being harmless the third time somebody copies the weaker one. The bank was
 * about to be that third copy.</p>
 *
 * <h2>Why a nation check is not a town check</h2>
 *
 * <p>A town knows its own residents, so its standing is a question it can answer alone. A nation has
 * no residents at all — its citizens are the residents of its member towns — so answering "what
 * standing does this player have here" needs the actor's town loaded first. That lookup is the part
 * a caller forgets, and forgetting it does not fail loudly: {@link Nation#standingOf} with no town
 * reads every officer as an outsider, so the check refuses everything and looks like a permission
 * bug rather than a missing argument.</p>
 *
 * <p>Every method here throws {@link ChangeRefusedException} rather than returning a boolean, which
 * inside a transaction means the whole change rolls back. That is the intended shape: a refusal
 * discovered halfway through must not leave half of it committed.</p>
 */
final class CivicPermissions {

    private CivicPermissions() {
    }

    /**
     * Checks a nation permission and hands back the nation, since every caller wanted it anyway.
     *
     * @throws ChangeRefusedException if the nation is gone, has no role book, or the actor's
     *         standing does not carry the permission
     */
    static Nation requireNation(
            final CivicTransaction transaction,
            final NationId nationId,
            final ResidentId actor,
            final Permission permission
    ) {
        Objects.requireNonNull(nationId, "nationId");
        Objects.requireNonNull(actor, "actor");
        final Nation nation = nation(transaction, nationId);
        if (!allowsNation(transaction, nation, actor, permission)) {
            throw new ChangeRefusedException(ChangeDenial.MISSING_PERMISSION);
        }
        return nation;
    }

    /**
     * The same question without the refusal, for an advisory check made before anything is touched.
     *
     * <p>A missing role book answers {@code false} here rather than throwing, because the caller is
     * asking rather than acting — and it still throws on the authoritative check that follows.</p>
     */
    static boolean allowsNation(
            final CivicTransaction transaction,
            final Nation nation,
            final ResidentId actor,
            final Permission permission
    ) {
        final Optional<RoleBook> book =
                transaction.roles().find(OrganisationScope.NATION, nation.id().value());
        if (book.isEmpty()) {
            return false;
        }
        final SystemRole standing = nation.standingOf(actor, townOf(transaction, actor).orElse(null));
        return book.get().allows(actor, permission, standing);
    }

    /** Checks a town permission. The town is already loaded by every caller that needs this. */
    static void requireTown(
            final CivicTransaction transaction,
            final Town town,
            final ResidentId actor,
            final Permission permission
    ) {
        Objects.requireNonNull(town, "town");
        Objects.requireNonNull(actor, "actor");
        if (!allowsTown(transaction, town, actor, permission)) {
            throw new ChangeRefusedException(ChangeDenial.MISSING_PERMISSION);
        }
    }

    /** The advisory form, for the same reason as {@link #allowsNation}. */
    static boolean allowsTown(
            final CivicTransaction transaction,
            final Town town,
            final ResidentId actor,
            final Permission permission
    ) {
        return transaction.roles()
                .find(OrganisationScope.TOWN, town.id().value())
                .map(book -> book.allows(actor, permission, town.standingOf(actor)))
                .orElse(false);
    }

    /** The actor's own town, which is what a nation needs to place them. */
    static Optional<TownId> townOf(final CivicTransaction transaction, final ResidentId who) {
        return transaction.residents().find(who).flatMap(Resident::town);
    }

    static Nation nation(final CivicTransaction transaction, final NationId id) {
        return transaction.nations().find(id)
                .orElseThrow(() -> new ChangeRefusedException(ChangeDenial.NATION_NOT_FOUND));
    }

    static Town town(final CivicTransaction transaction, final TownId id) {
        return transaction.towns().find(id)
                .orElseThrow(() -> new ChangeRefusedException(ChangeDenial.TOWN_NOT_FOUND));
    }
}
