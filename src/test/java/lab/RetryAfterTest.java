package lab;

import lab.Model.Attempt;
import lab.Model.Endpoint;
import lab.Model.ReceiverMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RetryAfterTest {
    @TempDir Path dir;

    private List<Attempt> attempts(Lab lab, String chainId) {
        return lab.attemptsOf(chainId);
    }

    @Test
    void retryAfterSeconds() {
        Lab lab = new Lab(dir, 1L);
        Endpoint ep = lab.createEndpoint("rl", "s", 5, ReceiverMode.RATE_LIMIT, "5");
        Lab.SendResult r = lab.sendEvent(ep.id, "e1", "p");
        List<Attempt> atts = attempts(lab, r.chainId);
        assertEquals(2, atts.size());
        assertEquals(429, atts.get(0).responseStatus);
        assertEquals("RETRY_AFTER", atts.get(0).nextReason);
        assertEquals(Lab.EPOCH_START + 5_000, atts.get(1).scheduledAt);
    }

    @Test
    void retryAfterHttpDate() {
        Lab lab = new Lab(dir, 1L);
        long target = Lab.EPOCH_START + 30_000;
        String httpDate = DateTimeFormatter.RFC_1123_DATE_TIME.format(
                java.time.Instant.ofEpochMilli(target).atZone(ZoneOffset.UTC));
        Endpoint ep = lab.createEndpoint("rl", "s", 3, ReceiverMode.RATE_LIMIT, httpDate);
        Lab.SendResult r = lab.sendEvent(ep.id, "e1", "p");
        List<Attempt> atts = attempts(lab, r.chainId);
        assertEquals("RETRY_AFTER", atts.get(0).nextReason);
        assertEquals(target, atts.get(1).scheduledAt);
        // Advancing to the date fires the retry; the date is then in the past,
        // so remaining retries are due immediately and the chain exhausts its limit.
        lab.stepToNext();
        assertEquals(3, attempts(lab, r.chainId).size());
        assertEquals("DEAD", lab.chain(r.chainId).status);
    }

    @Test
    void retryAfterParserHandlesBothForms() {
        assertEquals(2_000, RetryAfter.delayMillis("2", 1_000_000));
        String date = DateTimeFormatter.RFC_1123_DATE_TIME.format(
                java.time.Instant.ofEpochMilli(1_000_000 + 9_000).atZone(ZoneOffset.UTC));
        assertEquals(9_000, RetryAfter.delayMillis(date, 1_000_000));
        assertEquals(0, RetryAfter.delayMillis(date, 1_000_000 + 60_000)); // past date clamps to 0
    }
}
