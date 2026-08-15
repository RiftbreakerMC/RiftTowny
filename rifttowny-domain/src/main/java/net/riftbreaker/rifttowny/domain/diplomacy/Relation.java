package net.riftbreaker.rifttowny.domain.diplomacy;

import java.util.Locale;
import java.util.Optional;

/**
 * What one nation has declared about another.
 *
 * <p>Two kinds, and they are not symmetrical in the way people first assume.</p>
 *
 * <p><strong>An alliance takes two.</strong> Declaring an ally is an offer; the two nations are
 * allied only when both have declared it. Otherwise one nation could hand another the run of its
 * territory — the {@link net.riftbreaker.rifttowny.domain.flag.Relationship#ALLY} rung grants real
 * access — by typing a command the other never saw.</p>
 *
 * <p><strong>An enmity takes one.</strong> Declaring somebody an enemy needs nobody's agreement,
 * because refusing to be someone's enemy is not a thing you can do. It binds the declarer, not the
 * target: it is a statement about how <em>they</em> will treat you, and the target is told.</p>
 */
public enum Relation {

    /** Offered by one nation. Real only when both have offered it. */
    ALLY,

    /** Declared by one nation, unilaterally. */
    ENEMY;

    public String storageValue() {
        return name();
    }

    public static Relation fromStorage(final String raw) {
        return parse(raw).orElseThrow(() ->
                new IllegalArgumentException("Unknown relation: " + raw));
    }

    public static Optional<Relation> parse(final String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "ally", "allies", "allied", "alliance" -> Optional.of(ALLY);
            case "enemy", "enemies", "hostile", "war" -> Optional.of(ENEMY);
            default -> Optional.empty();
        };
    }

    /** What to offer in tab completion and in a usage line. */
    public static String options() {
        return "ally, enemy";
    }
}
