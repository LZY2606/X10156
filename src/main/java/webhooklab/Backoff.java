package webhooklab;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Deterministic exponential backoff with an injectable jitter seed.
 *
 * delay(n) = min(maxBackoffMs, baseBackoffMs * 2^(n-1) + jitter), n = 1-based retry index
 * jitter   = lcg(seed, n) mod (baseBackoffMs + 1)
 *
 * The LCG is a self-contained, spec-stable generator (identical results on
 * every JVM), so retry schedules are reproducible across runs and restarts.
 */
public final class Backoff {

    private Backoff() {}

    public static long jitter(long seed, long n) {
        long x = seed;
        for (long i = 0; i < n; i++) {
            x = x * 6364136223846793005L + 1442695040888963407L;
        }
        return (x >>> 11) & 0x7fffffffffffffffL;
    }

    public static long delayMs(long baseBackoffMs, long maxBackoffMs, long jitterSeed, int retryIndex) {
        long exp;
        if (retryIndex <= 0) {
            exp = baseBackoffMs;
        } else if (retryIndex >= 62) {
            exp = Long.MAX_VALUE / 2;
        } else {
            long shifted = baseBackoffMs << retryIndex;
            exp = (shifted >>> retryIndex) == baseBackoffMs ? shifted : Long.MAX_VALUE / 2;
        }
        long jitter = baseBackoffMs >= 0 ? jitter(jitterSeed, retryIndex + 1L) % (baseBackoffMs + 1) : 0;
        long delay = exp > Long.MAX_VALUE - jitter ? Long.MAX_VALUE : exp + jitter;
        return Math.min(maxBackoffMs, delay);
    }

    /**
     * Parses a Retry-After header value into a delay in milliseconds relative
     * to {@code nowMs}. Supports both delta-seconds and HTTP-date forms.
     * Returns null when the value cannot be parsed.
     */
    public static Long parseRetryAfter(String value, long nowMs) {
        if (value == null) return null;
        String v = value.trim();
        if (v.isEmpty()) return null;
        try {
            long seconds = Long.parseLong(v);
            return Math.max(0, seconds * 1000L);
        } catch (NumberFormatException ignored) {
            // fall through to HTTP-date
        }
        try {
            Instant instant = DateTimeFormatter.RFC_1123_DATE_TIME.parse(v, Instant::from);
            return Math.max(0, instant.toEpochMilli() - nowMs);
        } catch (Exception ignored) {
            // fall through to lenient IMF-fixdate parse
        }
        try {
            ZonedDateTime zdt = ZonedDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME.withLocale(Locale.US)
                    .withZone(ZoneOffset.UTC));
            return Math.max(0, zdt.toInstant().toEpochMilli() - nowMs);
        } catch (Exception e) {
            return null;
        }
    }

    /** Formats a virtual-time instant as an HTTP-date (IMF-fixdate). */
    public static String httpDate(long epochMs) {
        return DateTimeFormatter.RFC_1123_DATE_TIME.withLocale(Locale.US)
                .format(ZonedDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneOffset.UTC));
    }
}
