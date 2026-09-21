package lab.webhook;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/**
 * Deterministic backoff with an injectable jitter seed.
 *
 * Jitter uses a per-(chain) LCG stream seeded from the endpoint seed plus the
 * event id hash, so the same configuration always produces the same schedule,
 * across processes and restarts. Formula:
 *   base = min(initial * multiplier^failures, maxBackoff)
 *   jitter fraction = LCG unit in [0,1)
 *   delay = base * (0.5 + 0.5 * jitter)
 */
public final class Backoff {

    private Backoff() {}

    /** SplitMix64-derived unit in [0,1) from a 64-bit stream position. */
    public static double unit(long seed, long index) {
        long z = seed + index * 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z = z ^ (z >>> 31);
        return (z >>> 11) * 0x1.0p-53;
    }

    public static long chainSeed(long endpointSeed, String eventId) {
        long h = 0x6a09e667f3bcc909L;
        for (int i = 0; i < eventId.length(); i++) {
            h ^= eventId.charAt(i);
            h *= 0x100000001b3L;
        }
        return endpointSeed ^ h;
    }

    public static long jitteredDelay(long initial, double multiplier, long max,
                                     long seed, long failureCount) {
        double base = initial * Math.pow(multiplier, Math.max(0, failureCount - 1));
        if (base > max) base = max;
        double f = 0.5 + 0.5 * unit(seed, failureCount);
        return (long) Math.max(1, Math.round(base * f));
    }

    public record RetryAfter(long delayMillis, String display) {}

    /**
     * Parses Retry-After as delta-seconds (RFC 9110) or an HTTP-date.
     * HTTP-date is interpreted against the virtual "now" so the lab stays
     * deterministic under virtual time.
     *
     * @return null if absent/unparseable
     */
    public static RetryAfter parseRetryAfter(String value, long nowMillis) {
        if (value == null || value.isBlank()) return null;
        String v = value.trim();
        try {
            long secs = Long.parseLong(v);
            return new RetryAfter(Math.max(0, secs) * 1000L, secs + "s");
        } catch (NumberFormatException ignored) {
            // fall through to http-date
        }
        for (DateTimeFormatter f : HTTP_DATE_FORMATS) {
            try {
                long when = ZonedDateTime.parse(v, f).toInstant().toEpochMilli();
                long delay = Math.max(0, when - nowMillis);
                return new RetryAfter(delay, "http-date");
            } catch (DateTimeParseException ignored) {
                // try next
            }
        }
        return null;
    }

    private static final DateTimeFormatter[] HTTP_DATE_FORMATS = {
            DateTimeFormatter.RFC_1123_DATE_TIME.withLocale(Locale.ENGLISH),
            DateTimeFormatter.ofPattern("EEEE, dd-MMM-uu HH:mm:ss zzz", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("EEE MMM ppd HH:mm:ss uuuu", Locale.ENGLISH),
    };

    public static String httpDate(long epochMillis) {
        return DateTimeFormatter.RFC_1123_DATE_TIME
                .format(java.time.Instant.ofEpochMilli(epochMillis).atZone(java.time.ZoneOffset.UTC));
    }
}
