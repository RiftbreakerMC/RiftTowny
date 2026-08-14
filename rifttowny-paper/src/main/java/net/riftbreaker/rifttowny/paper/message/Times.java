package net.riftbreaker.rifttowny.paper.message;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * How RiftTowny writes a moment down.
 *
 * <p>Two forms, for two questions. A founding date is a date — nobody cares that a town was founded
 * "at 14:32" — and a last-seen is an interval, because "three days ago" is the answer to what the
 * reader is actually asking and "2026-08-10" makes them count.</p>
 *
 * <p>Dates render in the server's own zone. A UTC timestamp on a screen a player reads is a small
 * lie about when something happened, and the server's zone is the one its community keeps time
 * in.</p>
 */
public final class Times {

    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("d MMM uuuu", Locale.ENGLISH);

    private Times() {
    }

    /** A calendar date in the server's zone. */
    public static String date(final Instant when) {
        return when == null ? "unknown" : DATE.format(when.atZone(ZoneId.systemDefault()));
    }

    /**
     * How long ago something was, in the largest unit that still says something.
     *
     * <p>One unit, never two. "3 days, 4 hours ago" is more precise and less useful: the reader is
     * deciding whether somebody is around, and the hours never change that answer.</p>
     */
    public static String ago(final Instant when, final Instant now) {
        if (when == null || now == null) {
            return "unknown";
        }
        final Duration since = Duration.between(when, now);
        if (since.isNegative() || since.toMinutes() < 1) {
            return "just now";
        }
        if (since.toHours() < 1) {
            return plural(since.toMinutes(), "minute") + " ago";
        }
        if (since.toDays() < 1) {
            return plural(since.toHours(), "hour") + " ago";
        }
        if (since.toDays() < 61) {
            return plural(since.toDays(), "day") + " ago";
        }
        return plural(since.toDays() / 30, "month") + " ago";
    }

    /** A duration in the largest unit that still says something, for a countdown. */
    public static String remaining(final Duration left) {
        if (left == null || left.isNegative() || left.isZero()) {
            return "no time";
        }
        if (left.toMinutes() < 1) {
            return plural(left.toSeconds(), "second");
        }
        if (left.toHours() < 1) {
            return plural(left.toMinutes(), "minute");
        }
        if (left.toDays() < 1) {
            return plural(left.toHours(), "hour");
        }
        return plural(left.toDays(), "day");
    }

    private static String plural(final long count, final String unit) {
        return count + " " + unit + (count == 1 ? "" : "s");
    }
}
