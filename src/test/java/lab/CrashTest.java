package lab;

import lab.Model.Attempt;
import lab.Model.Endpoint;
import lab.Model.ReceiverMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CrashTest {
    @TempDir Path dir;

    @Test
    void crashAfterScheduleWriteBeforeSendLosesNothing() {
        Lab lab = new Lab(dir, 11L);
        Endpoint ep = lab.createEndpoint("c", "s", 3, ReceiverMode.OK, "1");
        lab.setCrashHook(point -> { throw new Lab.CrashException(point); });
        assertThrows(Lab.CrashException.class, () -> lab.sendEvent(ep.id, "e1", "payload"));

        Lab restarted = new Lab(dir, 11L);
        List<Attempt> atts = restarted.attemptsOf("ch-2");
        assertEquals(1, atts.size());
        assertEquals("SCHEDULED", atts.get(0).status);
        restarted.stepToNext();
        assertEquals("SUCCEEDED", restarted.chain("ch-2").status);
        assertEquals(1, restarted.receiver().processedCount());
    }

    @Test
    void crashBeforeResponsePersistReplaysCommittedAttemptIdempotently() {
        Lab lab = new Lab(dir, 11L);
        Endpoint ep = lab.createEndpoint("c", "s", 3, ReceiverMode.LOST_RESPONSE, "1");
        lab.setCrashHook(point -> {
            if (point.equals("beforeResponsePersist")) throw new Lab.CrashException(point);
        });
        assertThrows(Lab.CrashException.class, () -> lab.sendEvent(ep.id, "e1", "payload"));
        // Receiver committed the processing before the crash.
        assertTrue(lab.receiver().hasProcessed(ep.id, "e1"));

        Lab restarted = new Lab(dir, 11L);
        List<Attempt> atts = restarted.attemptsOf("ch-2");
        assertEquals("SCHEDULED", atts.get(0).status);
        restarted.stepToNext();
        // The persisted attempt was re-executed with the same attempt id; the receiver
        // returned its committed response instead of processing twice.
        assertEquals("SUCCEEDED", restarted.chain("ch-2").status);
        assertEquals(1, restarted.receiver().processedCount());
        assertEquals("SUCCESS", restarted.attemptsOf("ch-2").get(0).status);
    }

    @Test
    void restartIsDeterministic() {
        String first = runWithCrash(dir.resolve("r1"));
        String second = runWithCrash(dir.resolve("r2"));
        assertEquals(first, second);
    }

    private String runWithCrash(Path d) {
        Lab lab = new Lab(d, 77L);
        Endpoint ep = lab.createEndpoint("c", "s", 3, ReceiverMode.SERVER_500, "1");
        lab.setCrashHook(point -> { throw new Lab.CrashException(point); });
        try { lab.sendEvent(ep.id, "e1", "payload"); } catch (Lab.CrashException expected) { }
        Lab restarted = new Lab(d, 77L);
        restarted.stepToNext();
        restarted.stepToNext();
        return Json.write(restarted.snapshot());
    }
}
