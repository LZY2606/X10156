package webhooklab;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EngineBehaviorTest {

    @Test
    void immediateSuccess(@TempDir Path dir) {
        try (LabHarness h = new LabHarness(dir)) {
            h.endpoint("ep", "success", 1000, 60000, 1, 5);
            String chainId = h.send("evt-ok", "ep", "{\"x\":1}");
            h.engine.stepNext();
            Model.Chain c = h.chain(chainId);
            assertEquals(Model.ChainStatus.SUCCEEDED, c.status());
            List<Model.Attempt> as = h.attempts(chainId);
            assertEquals(1, as.size());
            Model.AttemptResult r = h.resultOf(as.get(0).id());
            assertEquals(200, r.status());
            assertNotNull(as.get(0).signature());
        }
    }

    @Test
    void retryUsesSameEventIdAndPayloadButNewAttemptIdAndTimestamp(@TempDir Path dir) {
        try (LabHarness h = new LabHarness(dir)) {
            h.endpoint("ep", "status:500;success", 1000, 60000, 3, 5);
            String chainId = h.send("evt-retry", "ep", "PAYLOAD-BYTES");
            h.drain();
            List<Model.Attempt> as = h.attempts(chainId);
            assertEquals(2, as.size());
            assertEquals("evt-retry", as.get(0).eventId());
            assertEquals("evt-retry", as.get(1).eventId());
            assertEquals("PAYLOAD-BYTES", as.get(1).payload());
            assertNotEquals(as.get(0).id(), as.get(1).id());
            assertTrue(as.get(1).scheduledAt() > as.get(0).scheduledAt());
            assertEquals(Model.ChainStatus.SUCCEEDED, h.chain(chainId).status());
            Model.AttemptResult first = h.resultOf(as.get(0).id());
            assertEquals(500, first.status());
            assertEquals("BACKOFF", first.nextReasonCode());
            assertTrue(first.nextDelayMs() >= 2000 && first.nextDelayMs() <= 3000);
        }
    }

    @Test
    void retryAfterSecondsHonored(@TempDir Path dir) {
        try (LabHarness h = new LabHarness(dir)) {
            h.endpoint("ep", "status:429:retry-after=2;success", 1000, 60000, 3, 5);
            String chainId = h.send("evt-ra", "ep", "{}");
            h.engine.stepNext(); // 429
            List<Model.Attempt> as = h.attempts(chainId);
            assertEquals(2000, h.resultOf(as.get(0).id()).nextDelayMs());
            assertEquals(as.get(0).scheduledAt() + 2000, as.get(1).scheduledAt());
            h.drain();
            assertEquals(Model.ChainStatus.SUCCEEDED, h.chain(chainId).status());
        }
    }

    @Test
    void retryAfterHttpDateHonored(@TempDir Path dir) {
        try (LabHarness h = new LabHarness(dir)) {
            h.endpoint("ep", "status:429:retry-after=httpdate+7000;success", 1000, 60000, 3, 5);
            String chainId = h.send("evt-date", "ep", "{}");
            h.engine.stepNext(); // 429 at t=0, date header points to t=7000
            List<Model.Attempt> as = h.attempts(chainId);
            assertEquals(7000, as.get(1).scheduledAt());
            Model.AttemptResult r = h.resultOf(as.get(0).id());
            Map<String, Object> hdrs = Json.parseObject(r.responseHeaders());
            String ra = Json.str(hdrs, "retry-after");
            assertEquals(7000, Backoff.parseRetryAfter(ra, 0));
            h.drain();
            assertEquals(Model.ChainStatus.SUCCEEDED, h.chain(chainId).status());
        }
    }

    @Test
    void retryAfterAboveCapDeadLetters(@TempDir Path dir) {
        try (LabHarness h = new LabHarness(dir)) {
            h.endpoint("ep", "status:429:retry-after=90", 1000, 60000, 3, 5);
            String chainId = h.send("evt-cap", "ep", "{}");
            h.engine.stepNext();
            assertEquals(Model.ChainStatus.DEAD, h.chain(chainId).status());
            Model.Attempt a = h.attempts(chainId).get(0);
            assertEquals("RETRY_AFTER_CAP", h.resultOf(a.id()).nextReasonCode());
            assertEquals(1, h.attempts(chainId).size(), "no attempt scheduled beyond cap");
        }
    }

    @Test
    void terminalFailureClassesDeadLetterOrFail(@TempDir Path dir) {
        // 410 Gone -> FAILED (non-retryable 4xx)
        try (LabHarness h = new LabHarness(dir.resolve("a"))) {
            h.endpoint("ep", "status:410", 1000, 60000, 1, 5);
            String chainId = h.send("evt-410", "ep", "{}");
            h.drain();
            assertEquals(Model.ChainStatus.FAILED, h.chain(chainId).status());
            assertEquals(1, h.attempts(chainId).size());
        }
        // max attempts -> DEAD
        try (LabHarness h = new LabHarness(dir.resolve("b"))) {
            h.endpoint("ep", "status:500", 100, 1000, 1, 3);
            String chainId = h.send("evt-500", "ep", "{}");
            h.drain();
            assertEquals(Model.ChainStatus.DEAD, h.chain(chainId).status());
            assertEquals(3, h.attempts(chainId).size());
        }
        // 3xx redirect -> non-retryable FAILED
        try (LabHarness h = new LabHarness(dir.resolve("c"))) {
            h.endpoint("ep", "status:301", 100, 1000, 1, 3);
            String chainId = h.send("evt-301", "ep", "{}");
            h.drain();
            assertEquals(Model.ChainStatus.FAILED, h.chain(chainId).status());
        }
    }

    @Test
    void timeoutAndDisconnectAreRetried(@TempDir Path dir) {
        try (LabHarness h = new LabHarness(dir)) {
            h.endpoint("ep", "timeout;disconnect;success", 100, 1000, 1, 5);
            String chainId = h.send("evt-t", "ep", "{}");
            h.drain();
            assertEquals(Model.ChainStatus.SUCCEEDED, h.chain(chainId).status());
            List<Model.Attempt> as = h.attempts(chainId);
            assertEquals(3, as.size());
            assertEquals("TIMEOUT", h.resultOf(as.get(0).id()).outcome());
            assertEquals("DISCONNECTED", h.resultOf(as.get(1).id()).outcome());
        }
    }

    @Test
    void processedButLostResponseRetriesAndHitsIdempotentDuplicate(@TempDir Path dir) {
        try (LabHarness h = new LabHarness(dir)) {
            h.endpoint("ep", "lost;success", 100, 1000, 1, 5);
            String chainId = h.send("evt-lost", "ep", "{}");
            h.drain();
            assertEquals(Model.ChainStatus.SUCCEEDED, h.chain(chainId).status());
            List<Model.Attempt> as = h.attempts(chainId);
            assertEquals(2, as.size());
            assertEquals("LOST", h.resultOf(as.get(0).id()).outcome());
            Model.ReceiverEntry second = h.store.receiverLog.get(1);
            assertTrue(second.duplicate(), "retry must hit the receiver's idempotency record");
            assertTrue(second.signatureValid());
        }
    }

    @Test
    void pauseBlocksNewAttemptsButDoesNotCancelInflightOrReorderOnResume(@TempDir Path dir) {
        try (LabHarness h = new LabHarness(dir)) {
            // first attempt fails; retry scheduled at +200..300ms
            h.endpoint("ep", "status:500;status:500;success", 200, 60000, 1, 5);
            String chainId = h.send("evt-p", "ep", "{}");
            h.engine.stepNext(); // fires attempt 1 at t=0 -> 500, schedules attempt 2
            long plannedAt = h.attempts(chainId).get(1).scheduledAt();
            assertTrue(plannedAt > 0);

            // Pause, then advance the clock well past the scheduled time: nothing fires.
            h.engine.setPaused("ep", true);
            h.engine.advance(1_000_000);
            assertEquals(2, h.attempts(chainId).size(), "pause must not start new attempts");
            Model.Attempt parked = h.attempts(chainId).get(1);
            assertNull(h.resultOf(parked.id()));
            assertEquals(plannedAt, parked.scheduledAt(), "schedule must not be reordered to now");

            // Resume: the parked attempt was already due (its plan time is in
            // the past), so it fires immediately in original schedule order;
            // the clock is not rewound and the attempt is not rescheduled.
            long beforeStep = h.store.nowMs;
            h.engine.setPaused("ep", false);
            h.engine.stepNext();
            assertEquals(beforeStep, h.store.nowMs, "due parked attempt fires without clock motion");
            assertEquals(plannedAt, parked.scheduledAt(), "original plan time preserved");
            assertEquals(500, h.resultOf(parked.id()).status());

            // Drain: chain succeeds in original order.
            h.drain();
            assertEquals(Model.ChainStatus.SUCCEEDED, h.chain(chainId).status());
        }
    }

    @Test
    void pauseRaceDeliveredAttemptStillCompletes(@TempDir Path dir) {
        // Because firing is synchronous inside one clock step, a pause set
        // afterward can never undo a delivery that already reached the receiver.
        try (LabHarness h = new LabHarness(dir)) {
            h.endpoint("ep", "status:500;success", 200, 60000, 1, 5);
            String chainId = h.send("evt-race", "ep", "{}");
            h.engine.stepNext();
            h.engine.setPaused("ep", true); // pause AFTER the delivery already landed
            assertEquals(1, h.store.receiverLog.size());
            assertEquals(500, h.store.receiverLog.get(0).status());
            Model.Attempt scheduled = h.attempts(chainId).get(1);
            assertNotNull(scheduled);
            assertNull(h.resultOf(scheduled.id()), "the next attempt stays parked while paused");
        }
    }

    @Test
    void replayCreatesNewChainPointingBackAndDoesNotMutateHistory(@TempDir Path dir) {
        try (LabHarness h = new LabHarness(dir)) {
            h.endpoint("ep", "status:410", 100, 1000, 1, 3);
            String chainId = h.send("evt-r", "ep", "{}");
            h.drain();
            assertEquals(Model.ChainStatus.FAILED, h.chain(chainId).status());
            int originalAttempts = h.attempts(chainId).size();

            // Replay now succeeds: change the receiver behavior for future contacts.
            h.endpoint("ep", "success", 100, 1000, 1, 3);
            Model.Chain replay = h.engine.replayChain(chainId);
            assertNotEquals(chainId, replay.id());
            assertEquals(chainId, replay.replayOfChainId());
            h.drain();

            assertEquals(Model.ChainStatus.SUCCEEDED, h.chain(replay.id()).status());
            assertEquals(Model.ChainStatus.FAILED, h.chain(chainId).status(), "original chain untouched");
            assertEquals(originalAttempts, h.attempts(chainId).size(), "old attempts untouched");
            assertEquals("evt-r#replay1", replay.eventId());
            Model.Attempt replayAttempt = h.attempts(replay.id()).get(0);
            assertEquals("REPLAY", replayAttempt.reasonCode());
            assertEquals("{}", replayAttempt.payload(), "original payload reused");

            // Replaying an active chain is rejected.
            String active = h.send("evt-active", "ep", "{}");
            Engine.ApiError err = assertThrows(Engine.ApiError.class, () -> h.engine.replayChain(active));
            assertEquals(409, err.status);
        }
    }

    @Test
    void duplicateEventIdRejected(@TempDir Path dir) {
        try (LabHarness h = new LabHarness(dir)) {
            h.endpoint("ep", "success", 100, 1000, 1, 3);
            h.send("evt-dup", "ep", "{}");
            Engine.ApiError err = assertThrows(Engine.ApiError.class, () -> h.send("evt-dup", "ep", "{}"));
            assertEquals(409, err.status);
        }
    }

    @Test
    void deterministicScheduleAcrossIdenticalRuns(@TempDir Path dir) throws Exception {
        long[] schedule = null;
        for (int run = 0; run < 2; run++) {
            Path d = dir.resolve("run" + run);
            try (LabHarness h = new LabHarness(d)) {
                h.endpoint("ep", "status:500", 1000, 60000, 99, 4);
                String chainId = h.send("evt-det", "ep", "{}");
                h.drain();
                List<Model.Attempt> as = h.attempts(chainId);
                long[] times = new long[as.size()];
                for (int i = 0; i < as.size(); i++) times[i] = as.get(i).scheduledAt();
                if (schedule == null) schedule = times;
                else assertArrayEquals(schedule, times);
            }
        }
    }
}
