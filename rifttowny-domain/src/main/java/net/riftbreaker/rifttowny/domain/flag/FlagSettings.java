package net.riftbreaker.rifttowny.domain.flag;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One layer's opinions, keyed by flag and relationship.
 *
 * <p><strong>Partial on purpose.</strong> An absent entry is "no opinion", not "denied" — that is
 * what lets a claim override one flag without having to restate every other, and what makes the
 * layered resolution work at all. A layer that answered every question would end resolution at
 * whichever layer happened to be first.</p>
 */
public final class FlagSettings {

    private static final FlagSettings EMPTY = new FlagSettings(Map.of());

    private final Map<Key, Boolean> opinions;

    private FlagSettings(final Map<Key, Boolean> opinions) {
        this.opinions = Collections.unmodifiableMap(new LinkedHashMap<>(opinions));
    }

    /** A layer with no opinion about anything, which every resolution simply passes through. */
    public static FlagSettings empty() {
        return EMPTY;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** This layer's opinion, or empty when it has none. */
    public Optional<Boolean> opinionOn(final ProtectionFlag flag, final Relationship relationship) {
        if (flag == null || relationship == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(opinions.get(new Key(flag, relationship)));
    }

    public boolean isEmpty() {
        return opinions.isEmpty();
    }

    public int size() {
        return opinions.size();
    }

    /**
     * The first flag where a less entitled relationship is allowed something a more entitled one is
     * not.
     *
     * <p>Not enforced during resolution — a resolver that second-guessed its own configuration
     * would be unpredictable, and predictability is the whole point of something that runs in a
     * block-break listener. It is offered so the configuration validator can tell an operator that
     * their visitors can build and their allies cannot, which is almost always a typo.</p>
     */
    public Optional<ProtectionFlag> firstNonMonotonic() {
        for (final ProtectionFlag flag : ProtectionFlag.values()) {
            Boolean previous = null;
            for (final Relationship relationship : Relationship.values()) {
                final Boolean opinion = opinions.get(new Key(flag, relationship));
                if (opinion == null) {
                    continue;
                }
                if (Boolean.TRUE.equals(previous) && Boolean.FALSE.equals(opinion)) {
                    return Optional.of(flag);
                }
                previous = opinion;
            }
        }
        return Optional.empty();
    }

    private record Key(ProtectionFlag flag, Relationship relationship) {
    }

    /** Assembles a layer. */
    public static final class Builder {

        private final Map<Key, Boolean> opinions = new LinkedHashMap<>();

        private Builder() {
        }

        public Builder set(
                final ProtectionFlag flag, final Relationship relationship, final boolean allowed) {
            opinions.put(
                    new Key(Objects.requireNonNull(flag, "flag"),
                            Objects.requireNonNull(relationship, "relationship")),
                    allowed);
            return this;
        }

        /** The same opinion for every relationship at or above one. */
        public Builder setFrom(
                final ProtectionFlag flag, final Relationship from, final boolean allowed) {
            for (final Relationship relationship : Relationship.values()) {
                if (relationship.isAtLeast(from)) {
                    set(flag, relationship, allowed);
                }
            }
            return this;
        }

        /** The same opinion for every relationship. */
        public Builder setAll(final ProtectionFlag flag, final boolean allowed) {
            for (final Relationship relationship : Relationship.values()) {
                set(flag, relationship, allowed);
            }
            return this;
        }

        public FlagSettings build() {
            return opinions.isEmpty() ? EMPTY : new FlagSettings(opinions);
        }
    }
}
