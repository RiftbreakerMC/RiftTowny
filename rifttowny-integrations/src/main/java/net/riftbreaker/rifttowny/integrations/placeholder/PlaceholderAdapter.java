package net.riftbreaker.rifttowny.integrations.placeholder;

import net.riftbreaker.rifttowny.api.capability.Capability;
import net.riftbreaker.rifttowny.domain.directory.TownyPlaceholders;
import net.riftbreaker.rifttowny.integrations.IntegrationAdapter;

import java.util.Objects;

/**
 * Registers the {@code %townyadvanced_*%} expansion, if PlaceholderAPI is here.
 *
 * <p>Inside the capability registry like every other integration, so a PlaceholderAPI that is
 * absent, or present at a version whose {@code PlaceholderExpansion} has moved, costs the server
 * its placeholders and nothing else. The registry catches {@link LinkageError} as well as
 * exceptions, which is the case that matters: a moved superclass method fails at class-load time,
 * not at call time, and would otherwise take the whole enable with it.</p>
 *
 * <p>The expansion class is deliberately not touched until {@link #bind()} runs. Referring to it
 * from a field initialiser would load {@code PlaceholderExpansion} at construction — before the
 * guard — which is exactly the failure the guard exists to contain.</p>
 */
public final class PlaceholderAdapter implements IntegrationAdapter {

    private final TownyPlaceholders placeholders;
    private final String version;

    public PlaceholderAdapter(final TownyPlaceholders placeholders, final String version) {
        this.placeholders = Objects.requireNonNull(placeholders, "placeholders");
        this.version = Objects.requireNonNull(version, "version");
    }

    @Override
    public Capability capability() {
        return Capability.PLACEHOLDERS_PAPI;
    }

    @Override
    public Object bind() {
        final TownyAdvancedExpansion expansion =
                new TownyAdvancedExpansion(placeholders, version);
        if (!expansion.register()) {
            // Registration refuses when an expansion already claims the identifier, which on this
            // server means Towny itself is present - and RiftTowny refuses to start beside Towny,
            // so reaching here at all is worth reporting rather than swallowing.
            throw new IllegalStateException(
                    "PlaceholderAPI refused the '" + TownyAdvancedExpansion.IDENTIFIER
                            + "' expansion; something else has already claimed that identifier.");
        }
        return expansion;
    }

    @Override
    public String describe(final Object bound) {
        if (bound instanceof TownyAdvancedExpansion expansion) {
            return "%" + TownyAdvancedExpansion.IDENTIFIER + "_*%, "
                    + expansion.getPlaceholders().size() + " placeholder(s), manifest "
                    + TownyPlaceholders.MANIFEST_VERSION;
        }
        return "bound";
    }
}
