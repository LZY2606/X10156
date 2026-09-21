package webhooklab;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BackoffTest {

    @Test
    void deterministicJitterSequence() {
        // Same seed always produces the same jitter sequence across JVMs/restarts.
        assertEquals(Backoff.jitter(42, 1), Backoff.jitter(42, 1));
        assertEquals(Backoff.jitter(42, 5), Backoff.jitter(42, 5));
        assertNotEquals(Backoff.jitter(42, 1), Backoff.jitter(43, 1));
    }

    @Test
    void delayIsExponentialWithDeterministicJitterAndCapsAtMax() {
        long d1 = Backoff.delayMs(1000, 60000, 7, 1);
        long d2 = Backoff.delayMs(1000, 60000, 7, 2);
        long d3 = Backoff.delayMs(1000, 60000, 7, 3);
        long again = Backoff.delayMs(1000, 60000, 7, 2);
        assertEquals(d2, again); // deterministic
        assertTrue(d1 >= 2000 && d1 <= 3000, "d1=" + d1);
        assertTrue(d2 >= 4000 && d2 <= 5000, "d2=" + d2);
        assertTrue(d3 >= 8000 && d3 <= 9000, "d3=" + d3);
        assertEquals(60000, Backoff.delayMs(1000, 60000, 7, 10)); // capped
    }

    @Test
    void differentSeedsChangeScheduleButBothStayInWindow() {
        long a = Backoff.delayMs(1000, 60000, 1, 2);
        long b = Backoff.delayMs(1000, 60000, 2, 2);
        assertTrue(a >= 4000 && a <= 5000);
        assertTrue(b >= 4000 && b <= 5000);
    }

    @Test
    void retryAfterSeconds() {
        assertEquals(2000, Backoff.parseRetryAfter("2", 1_000_000));
        assertEquals(0, Backoff.parseRetryAfter("0", 1_000_000));
    }

    @Test
    void retryAfterHttpDate() {
        long now = 1_700_000_000_000L;
        String future = Backoff.httpDate(now + 3000);
        Long parsed = Backoff.parseRetryAfter(future, now);
        assertNotNull(parsed);
        assertEquals(3000, parsed);
    }

    @Test
    void retryAfterPastDateClampsToZero() {
        long now = 1_700_000_000_000L;
        String past = Backoff.httpDate(now - 5000);
        assertEquals(0, Backoff.parseRetryAfter(past, now));
    }

    @Test
    void retryAfterInvalidReturnsNull() {
        assertNull(Backoff.parseRetryAfter("not a date", 1000));
    }
}
