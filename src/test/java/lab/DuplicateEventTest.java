package lab;

import lab.Model.Endpoint;
import lab.Model.ReceiverMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class DuplicateEventTest {
    @TempDir Path dir;

    @Test
    void duplicateEventIdIsIgnoredAndPointsToOriginalChain() {
        Lab lab = new Lab(dir, 17L);
        Endpoint ep = lab.createEndpoint("d", "s", 3, ReceiverMode.OK, "1");
        Lab.SendResult first = lab.sendEvent(ep.id, "evt-1", "p");
        int attemptsAfterFirst = lab.state.attempts.size();
        Lab.SendResult second = lab.sendEvent(ep.id, "evt-1", "different payload");
        assertFalse(first.duplicate);
        assertTrue(second.duplicate);
        assertEquals(first.chainId, second.chainId);
        assertEquals(1, lab.state.chains.size());
        assertEquals(1, lab.state.events.size());
        assertEquals(attemptsAfterFirst, lab.state.attempts.size());
        // Same event id on a different endpoint is a distinct event.
        Endpoint ep2 = lab.createEndpoint("d2", "s", 3, ReceiverMode.OK, "1");
        Lab.SendResult third = lab.sendEvent(ep2.id, "evt-1", "p");
        assertFalse(third.duplicate);
        assertEquals(2, lab.state.chains.size());
    }
}
