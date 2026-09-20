package lab;

import lab.Model.Attempt;
import lab.Model.Endpoint;
import lab.Model.ReceiverMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PauseTest {
    @TempDir Path dir;

    @Test
    void pauseDoesNotCancelInFlightDelivery() {
        Lab lab = new Lab(dir, 3L);
        Endpoint ep = lab.createEndpoint("p", "s", 3, ReceiverMode.OK, "1");
        // Pause the endpoint while the delivery is being processed by the receiver.
        lab.receiver().setListener((ep2, ev, at) -> lab.setPaused(ep2.id, true));
        Lab.SendResult r = lab.sendEvent(ep.id, "e1", "p");
        assertEquals("SUCCEEDED", lab.chain(r.chainId).status);
        assertTrue(lab.endpoint(ep.id).paused);
    }

    @Test
    void pauseBlocksNewAttemptsAndResumeKeepsOriginalOrder() {
        Lab lab = new Lab(dir, 3L);
        Endpoint ep = lab.createEndpoint("p", "s", 5, ReceiverMode.SERVER_500, "1");
        Lab.SendResult r1 = lab.sendEvent(ep.id, "e1", "p");
        Lab.SendResult r2 = lab.sendEvent(ep.id, "e2", "p");
        Attempt retry1 = lab.attemptsOf(r1.chainId).get(1);
        Attempt retry2 = lab.attemptsOf(r2.chainId).get(1);
        long planned1 = retry1.scheduledAt;
        long planned2 = retry2.scheduledAt;

        lab.setPaused(ep.id, true);
        lab.stepClock(10_000); // far beyond both planned retries
        assertEquals("SCHEDULED", lab.attempt(retry1.attemptId).status);
        assertEquals("SCHEDULED", lab.attempt(retry2.attemptId).status);

        List<String> delivered = new ArrayList<>();
        lab.receiver().setListener((ep2, ev, at) -> delivered.add(at.attemptId));
        lab.setPaused(ep.id, false);
        lab.stepToNext();
        // Original plan untouched: scheduledAt was not re-based to resume time.
        assertEquals(planned1, lab.attempt(retry1.attemptId).scheduledAt);
        assertEquals(planned2, lab.attempt(retry2.attemptId).scheduledAt);
        // Both processed in original schedule order, not re-ordered by resume time.
        Attempt a1 = lab.attempt(retry1.attemptId);
        Attempt a2 = lab.attempt(retry2.attemptId);
        assertEquals("FAILED", a1.status);
        assertEquals("FAILED", a2.status);
        List<String> expected = new ArrayList<>(List.of(retry1.attemptId, retry2.attemptId));
        expected.sort((x, y) -> Long.compare(lab.attempt(x).scheduledAt, lab.attempt(y).scheduledAt));
        assertEquals(expected, delivered);
    }
}
