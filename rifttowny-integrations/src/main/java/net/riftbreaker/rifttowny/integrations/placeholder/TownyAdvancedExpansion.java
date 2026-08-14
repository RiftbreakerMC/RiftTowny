package net.riftbreaker.rifttowny.integrations.placeholder;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import me.clip.placeholderapi.expansion.Relational;
import net.riftbreaker.rifttowny.domain.directory.TownyPlaceholders;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Serves {@code %townyadvanced_*%} so a server's existing scoreboards keep working.
 *
 * <p>Deliberately thin. Every answer comes from {@link TownyPlaceholders}, which is Bukkit-free and
 * therefore testable; this class exists to satisfy PlaceholderAPI's shape and to turn an absent
 * answer into the {@code null} that means "not mine".</p>
 *
 * <p><strong>The null contract is the whole point.</strong> PlaceholderAPI treats a null return as
 * "this expansion does not serve that name" and leaves the literal {@code %townyadvanced_whatever%}
 * in the output for a player to see. An empty string means "served, and the answer is nothing".
 * Every name in the shipped manifest therefore resolves to a string, and a golden test proves it —
 * without that distinction, an unimplemented placeholder reaches a player as raw markup.</p>
 *
 * <p>It also implements {@link Relational}, which is how PlaceholderAPI serves
 * {@code %rel_townyadvanced_color%}: the colour of the viewed player as the viewer should see
 * them.</p>
 */
public final class TownyAdvancedExpansion extends PlaceholderExpansion implements Relational {

    /** Towny's own identifier, which is what makes existing scoreboards work unchanged. */
    public static final String IDENTIFIER = "townyadvanced";

    private final TownyPlaceholders placeholders;
    private final String version;
    private final List<String> manifest;

    public TownyAdvancedExpansion(final TownyPlaceholders placeholders, final String version) {
        this.placeholders = Objects.requireNonNull(placeholders, "placeholders");
        this.version = Objects.requireNonNull(version, "version");
        this.manifest = readManifest();
    }

    @Override
    public String getIdentifier() {
        return IDENTIFIER;
    }

    @Override
    public String getAuthor() {
        return "RiftbreakerMC";
    }

    @Override
    public String getVersion() {
        return version;
    }

    @Override
    public String getName() {
        return "RiftTowny";
    }

    /**
     * Survives a PlaceholderAPI reload.
     *
     * <p>The default is false, which unregisters the expansion on {@code /papi reload} — the
     * failure mode being placeholders that work until an operator touches PlaceholderAPI and then
     * silently stop. RiftTowny owns this expansion's lifetime; PlaceholderAPI does not.</p>
     */
    @Override
    public boolean persist() {
        return true;
    }

    /** What {@code /papi info townyadvanced} lists. Read from the shipped manifest, not hand-typed. */
    @Override
    public List<String> getPlaceholders() {
        return manifest;
    }

    /**
     * @param player may be null, and may be offline. Answering for an offline player is the useful
     *        behaviour: a scoreboard or a web panel asks about people who are not here
     */
    @Override
    public String onRequest(final OfflinePlayer player, final String params) {
        return placeholders
                .resolve(player == null ? null : player.getUniqueId(), params)
                .orElse(null);
    }

    /**
     * {@code %rel_townyadvanced_color%} — how the viewer should see the viewed player.
     *
     * <p>Only {@code color} is served, because it is the only relational placeholder Towny has. The
     * colour is the viewed player's own allegiance colour: their nation's if they have one, their
     * town's otherwise. A relationship ladder that distinguished ally from enemy would need
     * {@code RT-MOD-DIPLOMACY}, which is unbuilt, so a stranger and an ally currently look the
     * same rather than looking convincingly different and being wrong.</p>
     */
    @Override
    public String onPlaceholderRequest(
            final Player viewer, final Player viewed, final String params) {
        if (viewed == null || params == null) {
            return null;
        }
        if (!params.equalsIgnoreCase("color") && !params.equalsIgnoreCase("colour")) {
            return null;
        }
        return placeholders.resolve(viewed.getUniqueId(), "towny_colour").orElse(null);
    }

    /**
     * The manifest, from the resource the golden test also reads.
     *
     * <p>One source for both, so {@code /papi info} cannot advertise a placeholder the test has
     * never checked, nor omit one it has.</p>
     */
    private static List<String> readManifest() {
        final List<String> names = new ArrayList<>();
        try (InputStream stream = TownyPlaceholders.class
                .getResourceAsStream("/placeholders/townyadvanced.txt")) {
            if (stream == null) {
                return List.of();
            }
            try (BufferedReader reader =
                         new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    final String trimmed = line.trim();
                    if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                        names.add("%" + IDENTIFIER + '_' + trimmed + '%');
                    }
                }
            }
        } catch (final IOException unreadable) {
            // A missing manifest costs the /papi info listing and nothing else: every placeholder
            // still resolves, because resolution does not consult this list.
            return List.of();
        }
        return List.copyOf(names);
    }
}
