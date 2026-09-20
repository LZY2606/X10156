package lab;

import lab.Model.Attempt;
import lab.Model.Endpoint;
import lab.Model.ReceiverMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LostResponseTest {
    @TempDir Path dir;

    @Test
    void processedButResponseLostRetriesWithNewAttemptAndSucceedsAsDuplicate() {
        Lab lab = new Lab(dir, 5L);
        Endpoint ep = lab.createEndpoint("lost", "s", 3, ReceiverMode.LOST_RESPONSE, "1");
        Lab.SendResult r = lab.sendEvent(ep.id, "evt-1", "{\"n\":1}");

        List<Attempt> atts = lab.attemptsOf(r.chainId);
        assertEquals(2, atts.size());
        Attempt first = atts.get(0);
        assertEquals("FAILED", first.status);
        assertEquals("TIMEOUT", first.errorKind); // response lost on the wire
        assertTrue(lab.receiver().hasProcessed(ep.id, "evt-1")); // but receiver committed it
        assertEquals(1, lab.receiver().processedCount());

        lab.stepToNext();
        atts = lab.attemptsOf(r.chainId);
        assertEquals(2, atts.size());
        Attempt second = atts.get(1);
        assertEquals("SUCCESS", second.status);
        assertEquals(200, second.responseStatus);
        assertTrue(second.responseHeaders.contains("X-Wh-Duplicate"));
        assertEquals("SUCCEEDED", lab.chain(r.chainId).status);
        // Receiver processed the event exactly once.
        assertEquals(1, lab.receiver().processedCount());
        // Retry reused event id + payload but got a fresh attempt id and timestamp.
        assertEquals(first.eventId, second.eventId);
        assertNotEquals(first.attemptId, second.attemptId);
        assertTrue(second.timestamp > first.timestamp);
        assertTrue(second.canonicalRequest.contains("evt-1"));
        assertTrue(second.canonicalRequest.contains("{\"n\":1}"));
    }
}
