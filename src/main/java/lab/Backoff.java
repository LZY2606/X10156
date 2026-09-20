package lab;

/** Deterministic exponential backoff with injectable jitter seed. */
public final class Backoff {
    private Backoff() {}

    public static final long BASE_MS = 1_000;
    public static final long CAP_MS = 60_000;

    /** Delay for retry of attempt {@code number} (1-based) of the given event. */
    public static long delayMillis(long seed, String eventId, int number) {
        long base = Math.min(BASE_MS << Math.min(number - 1, 6), CAP_MS);
        long jitter = mix(seed ^ mix(eventId.hashCode() * 0x9E3779B97F4A7C15L) ^ number) % base;
        if (jitter < 0) jitter = -jitter;
        return base + jitter;
    }

    static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
