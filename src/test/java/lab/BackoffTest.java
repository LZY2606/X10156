package lab;

import lab.Model.Attempt;
import lab.Model.Endpoint;
import lab.Model.ReceiverMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BackoffTest {
    @TempDir Path dir;

    @Test
    void deterministicJitterFromSeed() {
        for (int n = 1; n <= 5; n++) {
            assertEquals(Backoff.delayMillis(42L, "evt-9", n), Backoff.delayMillis(42L, "evt-9", n));
        }
        long d1 = Backoff.delayMillis(42L, "evt-9", 1);
        assertTrue(d1 >= 1_000 && d1 < 2_000, "base 1s + jitter < base: " + d1);
        long d2 = Backoff.delayMillis(42L, "evt-9", 2);
        assertTrue(d2 >= 2_000 && d2 < 4_000, "base 2s + jitter < base: " + d2);
        long d10 = Backoff.delayMillis(42L, "evt-9", 10);
        assertTrue(d10 >= 60_000 && d10 < 120_000, "capped at 60s base: " + d10);
    }

    @Test
    void sameSeedSameScheduleAcrossRuns() {
        long[] scheduleA = runToDeath(dir.resolve("a"), 99L);
        long[] scheduleB = runToDeath(dir.resolve("b"), 99L);
        assertArrayEquals(scheduleA, scheduleB);
    }

    private long[] runToDeath(Path d, long seed) {
        Lab lab = new Lab(d, seed);
        Endpoint ep = lab.createEndpoint("x", "s", 4, ReceiverMode.SERVER_500, "1");
        Lab.SendResult r = lab.sendEvent(ep.id, "evt", "p");
        while (!"DEAD".equals(lab.chain(r.chainId).status)) lab.stepToNext();
        List<Attempt> atts = lab.attemptsOf(r.chainId);
        assertEquals(4, atts.size());
        return atts.stream().mapToLong(a -> a.scheduledAt).toArray();
    }
}
