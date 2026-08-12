package net.riftbreaker.rifttowny.domain.flag;

import net.riftbreaker.rifttowny.api.ChunkKey;
import net.riftbreaker.rifttowny.domain.org.TownId;

import java.util.List;

/**
 * Where the configurable flag layers come from.
 *
 * <p>{@link FlagResolver} is pure: it is handed the layers already resolved and picks the first with
 * an opinion. Something has to do the resolving, and that something has to be replaceable — the
 * layers come from an admin file, a war, an area override, a claim, an organisation and a world, and
 * those arrive across several releases.</p>
 *
 * <p><strong>Currently {@link #builtInOnly()} is the only implementation.</strong> No flag override
 * is persisted yet — there is no {@code rt_flag_override} table — so every resolution falls through
 * to {@link ProtectionFlag#allowedByDefault}. This interface exists so that gap is a named seam with
 * one obvious place to fill it, rather than an empty list hardcoded inside a listener.</p>
 */
@FunctionalInterface
public interface FlagSettingsSource {

    /**
     * The layers that apply at a position, in resolution order.
     *
     * <p>Called on the server thread inside a protection check, so it must not touch storage. Use
     * {@link FlagResolver#layers} to assemble the list rather than ordering it by hand.</p>
     *
     * @param owner the town that owns the chunk, or null in wilderness
     */
    List<FlagResolver.FlagLayer> layersFor(ChunkKey chunk, TownId owner);

    /** No configured layers at all: every flag resolves to its built-in default. */
    static FlagSettingsSource builtInOnly() {
        return (chunk, owner) -> List.of();
    }
}
