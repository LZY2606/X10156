package lab;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/** Parses Retry-After as either delta-seconds or an HTTP date, against the virtual clock. */
public final class RetryAfter {
    private RetryAfter() {}

    /** @return delay in milliseconds from {@code nowMillis}; never negative. */
    public static long delayMillis(String value, long nowMillis) {
        String v = value.trim();
        if (v.matches("\\d+")) {
            return Long.parseLong(v) * 1_000L;
        }
        long target = ZonedDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli();
        return Math.max(0, target - nowMillis);
    }
}
