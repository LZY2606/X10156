package lab;

import lab.Model.Attempt;
import lab.Model.Chain;
import lab.Model.Endpoint;
import lab.Model.ReceiverMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class DeadLetterReplayTest {
    @TempDir Path dir;

    @Test
    void deadLetterThenReplayCreatesNewChainWithoutTouchingOldAttempts() {
        Lab lab = new Lab(dir, 13L);
        Endpoint ep = lab.createEndpoint("d", "s", 2, ReceiverMode.SERVER_500, "1");
        Lab.SendResult r = lab.sendEvent(ep.id, "e1", "payload");
        lab.stepToNext();
        Chain dead = lab.chain(r.chainId);
        assertEquals("DEAD", dead.status);
        List<Attempt> oldAttempts = lab.attemptsOf(dead.chainId);
        assertEquals(2, oldAttempts.size());
        assertEquals("NONE_DEAD", oldAttempts.get(1).nextReason);
        String before = oldAttempts.stream().map(a -> Json.write(a.toJson())).collect(Collectors.joining("\n"));

        Chain replay = lab.replay(dead.chainId);
        assertEquals(dead.chainId, replay.replayedFrom);
        assertNotEquals(dead.chainId, replay.chainId);
        assertEquals("ACTIVE", replay.status);
        // Old chain and its attempts are immutable history.
        assertEquals("DEAD", lab.chain(dead.chainId).status);
        String after = lab.attemptsOf(dead.chainId).stream()
                .map(a -> Json.write(a.toJson())).collect(Collectors.joining("\n"));
        assertEquals(before, after);

        lab.updateEndpoint(ep.id, null, null, null, ReceiverMode.OK, null);
        lab.stepToNext();
        assertEquals("SUCCEEDED", lab.chain(replay.chainId).status);
        assertEquals(2, lab.attemptsOf(replay.chainId).size());
        // Replaying a live chain is rejected.
        assertThrows(IllegalArgumentException.class, () -> lab.replay(replay.chainId));
    }
}
