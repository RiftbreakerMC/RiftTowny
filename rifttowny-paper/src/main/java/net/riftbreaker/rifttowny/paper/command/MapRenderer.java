package net.riftbreaker.rifttowny.paper.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.riftbreaker.rifttowny.domain.directory.MapCell;
import net.riftbreaker.rifttowny.domain.directory.TerritoryMap;
import net.riftbreaker.rifttowny.domain.territory.ClaimKind;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws a {@link TerritoryMap.MapView} into chat.
 *
 * <p>Two channels carry two different questions, because a map that answers only one of them is
 * half a map. <strong>Colour says whose</strong> — your town, your nation, somebody else's, a ruin,
 * nobody's. <strong>Shape says what</strong> — a home chunk, an outpost, a plot you hold, an
 * ordinary claim. Overloading either one would force a player to choose which question they can
 * answer at a glance.</p>
 *
 * <p>Every square is two characters wide because a chat glyph is roughly twice as tall as it is
 * wide, so a one-character square draws a map stretched to twice its real height and a player
 * reading distances off it is reading them wrong.</p>
 *
 * <p>Hovering names the town and the chunk; clicking a claimed square opens that town's info. The
 * text is complete without either — Bedrock clients reach this through Geyser, where hover and
 * click are unreliable, and a map that only works on Java is a map half the server cannot use.</p>
 */
public final class MapRenderer {

    /** Nobody's land. */
    private static final String WILDERNESS = "--";

    /** A claim like any other. */
    private static final String CLAIMED = "[]";

    /** The town's origin chunk. */
    private static final String HOMEBLOCK = "{}";

    /** Land the town cannot walk to. */
    private static final String OUTPOST = "()";

    /** A plot the viewer holds personally. */
    private static final String OWN_PLOT = "##";

    private MapRenderer() {
    }

    /** Every line of the map, header and legend included, ready to send in order. */
    public static List<Component> render(final TerritoryMap.MapView view) {
        final List<Component> lines = new ArrayList<>(view.height());
        for (final List<MapCell> row : view.rows()) {
            Component line = Component.empty();
            for (final MapCell cell : row) {
                line = line.append(square(cell));
            }
            lines.add(line);
        }
        return List.copyOf(lines);
    }

    private static Component square(final MapCell cell) {
        Component square = Component.text(glyph(cell), colour(cell));
        if (cell.isCentre()) {
            // Gold and bold rather than a glyph of its own: the square still has to say what kind of
            // land you are standing on, and spending the shape channel on "you are here" would take
            // that away exactly where a player most wants it.
            square = square.color(NamedTextColor.GOLD).decorate(TextDecoration.BOLD);
        }
        square = square.hoverEvent(HoverEvent.showText(describe(cell)));
        if (cell.isClaimed() && !cell.label().isBlank()) {
            square = square.clickEvent(ClickEvent.runCommand("/town info " + cell.label()));
        }
        return square;
    }

    private static String glyph(final MapCell cell) {
        return switch (cell.standing()) {
            case WILDERNESS -> WILDERNESS;
            case OWN_PLOT -> OWN_PLOT;
            case RUIN -> "xx";
            case OWN_TOWN, NATION, FOREIGN -> shapeOfClaim(cell);
        };
    }

    private static String shapeOfClaim(final MapCell cell) {
        return cell.claimKind()
                .map(kind -> switch (kind) {
                    case HOMEBLOCK -> HOMEBLOCK;
                    case OUTPOST -> OUTPOST;
                    case ORDINARY, EMBASSY -> CLAIMED;
                })
                .orElse(CLAIMED);
    }

    private static TextColor colour(final MapCell cell) {
        return switch (cell.standing()) {
            case WILDERNESS -> NamedTextColor.DARK_GRAY;
            case OWN_PLOT, OWN_TOWN -> NamedTextColor.GREEN;
            case NATION -> NamedTextColor.AQUA;
            case FOREIGN -> NamedTextColor.RED;
            case RUIN -> NamedTextColor.DARK_RED;
        };
    }

    /** What hovering a square says. Chunk coordinates included: they are what a player navigates by. */
    private static Component describe(final MapCell cell) {
        final Component where = Component.text(
                cell.chunk().chunkX() + ", " + cell.chunk().chunkZ(), NamedTextColor.DARK_GRAY);
        if (!cell.isClaimed()) {
            return Component.text("Wilderness", NamedTextColor.GRAY)
                    .append(Component.text("  ")).append(where);
        }
        Component text = Component.text(cell.label(), colour(cell));
        if (cell.standing() == MapCell.MapStanding.RUIN) {
            text = text.append(Component.text(" in ruins", NamedTextColor.GRAY));
        } else {
            text = text.append(Component.text(kindSuffix(cell), NamedTextColor.GRAY));
        }
        return text.append(Component.text("  ")).append(where);
    }

    private static String kindSuffix(final MapCell cell) {
        if (cell.standing() == MapCell.MapStanding.OWN_PLOT) {
            return " - your plot";
        }
        return cell.claimKind()
                .filter(kind -> kind != ClaimKind.ORDINARY)
                .map(kind -> " - " + kind.name().toLowerCase(java.util.Locale.ROOT))
                .orElse("");
    }
}
