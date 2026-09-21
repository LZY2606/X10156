package lab.webhook;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Crash points, lost-response-after-commit, pause race, replay and restart durability. */
class DurabilityTest {

    private LabEngine engine(Path dir) {
        LabEngine e = new LabEngine(dir);
        e.setJitterSeed(7);
        return e;
    }

    private void endpoint(LabEngine e, String behavior, int code, int maxAttempts, long cap) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", "ep1"); m.put("secret", "s3cret");
        m.put("initialBackoffMillis", 1000); m.put("backoffMultiplier", 2.0);
        m.put("maxBackoffMillis", cap); m.put("maxAttempts", maxAttempts);
        m.put("behavior", behavior); m.put("statusCode", code);
        e.createEndpoint(m);
    }

    private String createEvent(LabEngine e, String eventId) {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("endpointId", "ep1"); req.put("eventId", eventId); req.put("payload", "PAYLOAD");
        return e.createEvent(req).chainId();
    }

    private long attempts(LabEngine e, String chainId) {
        return e.getChain(chainId).attempts().size();
    }

    private Models.Attempt tail(LabEngine e, String chainId) {
        List<Models.Attempt> ats = e.getChain(chainId).attempts();
        return ats.get(ats.size() - 1);
    }

    private Models.Attempt lastResolved(LabEngine e, String chainId) {
        List<Models.Attempt> ats = e.getChain(chainId).attempts();
        for (int i = ats.size() - 1; i >= 0; i--) {
            if (ats.get(i).status() != Models.AttemptStatus.PENDING) return ats.get(i);
        }
        throw new IllegalStateException("no resolved attempt");
    }

    private long dispatchedCount(LabEngine e, String chainId) {
        return e.getChain(chainId).attempts().stream()
                .filter(a -> a.status() != Models.AttemptStatus.PENDING).count();
    }

    @Test
    void crashAfterScheduleKeepsPendingAttempt(@TempDir Path dir) {
        LabEngine e = engine(dir);
        endpoint(e, "SUCCESS", 200, 5, 60_000);
        String chain = createEvent(e, "evt-c1");
        e.armCrash(LabEngine.CRASH_AFTER_SCHEDULE);
        assertThrows(LabEngine.CrashException.class, e::step);
        // attempt was durably scheduled; nothing dispatched yet
        assertEquals(Models.AttemptStatus.PENDING, tail(e, chain).status());
        assertEquals(1, attempts(e, chain));

        e.close();
        try (LabEngine e2 = engine(dir)) {
            chain = e2.listEvents().get(0).chainId();
            assertEquals(Models.AttemptStatus.PENDING, tail(e2, chain).status());
            e2.step(); // original scheduled attempt dispatches and succeeds
            assertEquals(Models.AttemptStatus.SUCCEEDED, tail(e2, chain).status());
        }
    }

    @Test
    void crashBeforeSendReplaysPendingAttempt(@TempDir Path dir) {
        LabEngine e = engine(dir);
        endpoint(e, "SUCCESS", 200, 5, 60_000);
        String chain = createEvent(e, "evt-c2");
        e.armCrash(LabEngine.CRASH_BEFORE_SEND);
        assertThrows(LabEngine.CrashException.class, e::step);
        assertEquals(Models.AttemptStatus.IN_FLIGHT, tail(e, chain).status());
        e.close();

        try (LabEngine e2 = engine(dir)) {
            chain = e2.listEvents().get(0).chainId();
            // recovery marks the lost attempt, schedules a brand-new attempt
            Models.Attempt recovered = lastResolved(e2, chain);
            assertEquals(Models.AttemptStatus.RECOVERED, recovered.status());
            assertEquals(Models.AttemptStatus.PENDING, tail(e2, chain).status());
            e2.step();
            assertEquals(Models.AttemptStatus.SUCCEEDED, lastResolved(e2, chain).status());
            // new attempt id, but same event id and identical payload bytes
            List<Models.Attempt> ats = e2.getChain(chain).attempts();
            assertNotEquals(ats.get(0).attemptId(), ats.get(1).attemptId());
            String b0 = new String(Base64.getDecoder().decode(ats.get(0).bodyBase64()),
                    StandardCharsets.UTF_8);
            String b1 = new String(Base64.getDecoder().decode(ats.get(1).bodyBase64()),
                    StandardCharsets.UTF_8);
            assertEquals("PAYLOAD", b0);
            assertEquals(b0, b1);
        }
    }

    @Test
    void crashAfterSendMarksRecoveredAndRetries(@TempDir Path dir) {
        LabEngine e = engine(dir);
        endpoint(e, "TIMEOUT", 0, 5, 60_000);
        String chain = createEvent(e, "evt-c3");
        e.armCrash(LabEngine.CRASH_AFTER_SEND);
        assertThrows(LabEngine.CrashException.class, e::step);
        e.close();
        try (LabEngine e2 = engine(dir)) {
            chain = e2.listEvents().get(0).chainId();
            assertEquals(Models.AttemptStatus.RECOVERED, lastResolved(e2, chain).status());
            e2.step();
            // retry runs; timeout again -> failed_retry, event not lost
            assertEquals(Models.AttemptStatus.FAILED_RETRY, lastResolved(e2, chain).status());
            assertTrue(attempts(e2, chain) >= 2);
        }
    }

    @Test
    void processedButResponseLostIsAcknowledgedOnRecoveryRetry(@TempDir Path dir) {
        LabEngine e = engine(dir);
        endpoint(e, "SUCCESS", 200, 5, 60_000);
        String chain = createEvent(e, "evt-lost");
        e.armCrash(LabEngine.CRASH_AFTER_SEND);
        assertThrows(LabEngine.CrashException.class, e::step);
        e.close();

        // the receiver committed "processed" before the crash; the retry must be
        // an idempotent duplicate acknowledgment, not a second execution.
        try (LabEngine e2 = engine(dir)) {
            chain = e2.listEvents().get(0).chainId();
            assertEquals(1, e2.snapshot().get("processedCount"));
            e2.step();
            Models.Attempt ok = tail(e2, chain);
            assertEquals(Models.AttemptStatus.SUCCEEDED, ok.status());
            assertEquals(200, ok.responseStatus());
            assertEquals("true", ok.responseHeaders().get("x-lab-duplicate"));
            assertEquals(1, e2.snapshot().get("processedCount"));
        }
    }

    @Test
    void pauseDoesNotCancelInFlightAndResumeKeepsOriginalOrder(@TempDir Path dir) {
        LabEngine e = engine(dir);
        endpoint(e, "TIMEOUT", 0, 5, 60_000);
        // A fails and schedules a retry at t0+delayA
        String a = createEvent(e, "evt-A");
        e.step();
        Models.Attempt a1 = lastResolved(e, a);
        long retryA = a1.nextScheduledAt();
        long delayA = a1.nextScheduledAt() - a1.resolvedAt();

        // B is created later (after advancing half of A's delay); its initial
        // dispatch time therefore sits between now and A's retry time.
        e.advance(delayA / 2);
        String b = createEvent(e, "evt-B");

        e.setPaused("ep1", true);
        e.step(); // reaches B's initial dispatch time, but pause blocks new attempts
        assertEquals(0, dispatchedCount(e, b), "B not dispatched while paused");
        assertEquals(1, dispatchedCount(e, a), "A retry also blocked while paused");
        // original planned instant of A survives untouched
        assertEquals(retryA, lastResolved(e, a).nextScheduledAt());

        e.setPaused("ep1", false);
        e.step(); // next due is B's original initial dispatch (never rearranged)
        assertEquals(1, dispatchedCount(e, b), "B dispatches first at its original time");
        assertEquals(1, dispatchedCount(e, a), "A retry still in the future");
        e.step(); // now reaches A's original retry instant
        assertEquals(2, dispatchedCount(e, a), "A retries at original planned instant");
    }

    @Test
    void resumeDoesNotRescheduleByCurrentTime(@TempDir Path dir) {
        LabEngine e = engine(dir);
        endpoint(e, "TIMEOUT", 0, 5, 60_000);
        String chain = createEvent(e, "evt-far");
        e.step();
        long planned = lastResolved(e, chain).nextScheduledAt();
        long delay = planned - e.getClock();

        e.setPaused("ep1", true);
        e.advance(delay / 2);              // time passes while paused
        assertEquals(1, dispatchedCount(e, chain));
        assertEquals(planned, lastResolved(e, chain).nextScheduledAt(),
                "original scheduled instant is not rewritten by current time");
        e.step();                         // step must ignore paused work
        assertEquals(1, dispatchedCount(e, chain), "still blocked while paused");

        e.setPaused("ep1", false);
        // step jumps to the next executable plan point — the original instant,
        // not "now + fresh backoff" — and delivers there.
        e.step();
        assertEquals(planned, e.getClock(), "resume honors original scheduled instant");
        assertEquals(2, dispatchedCount(e, chain), "sent at original planned instant");
        // and the delivered attempt really is scheduled at that preserved instant
        Models.Attempt second = e.getChain(chain).attempts().get(1);
        assertEquals(planned, second.scheduledAt());
    }

    @Test
    void replayCreatesNewChainPointingBackWithoutMutatingOld(@TempDir Path dir) {
        LabEngine e = engine(dir);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", "ep1"); m.put("secret", "s3cret");
        m.put("initialBackoffMillis", 1); m.put("backoffMultiplier", 2.0);
        m.put("maxBackoffMillis", 10); m.put("maxAttempts", 2);
        m.put("behavior", "RATE_LIMIT"); m.put("retryAfter", "60");
        e.createEndpoint(m);
        String originalChain = createEvent(e, "evt-dl");
        e.step();
        assertEquals(Models.EventStatus.DEAD_LETTER, e.getChain(originalChain).status());
        int originalAttemptCount = e.getChain(originalChain).attempts().size();

        // make receiver succeed, then replay the dead letter
        Map<String, Object> upd = new LinkedHashMap<>();
        upd.put("behavior", "SUCCESS");
        e.updateEndpoint("ep1", upd);
        Models.Event replayed = e.replayDeadLetter(originalChain, new LinkedHashMap<>());
        assertNotEquals(originalChain, replayed.chainId());
        assertEquals(originalChain, replayed.replayOfChain());
        e.step();

        assertEquals(Models.EventStatus.SUCCEEDED, e.getChain(replayed.chainId()).status());
        // original chain is untouched
        Models.Event old = e.getChain(originalChain);
        assertEquals(Models.EventStatus.DEAD_LETTER, old.status());
        assertEquals(originalAttemptCount, old.attempts().size());
        for (Models.Attempt at : old.attempts()) {
            assertNotEquals(Models.AttemptStatus.SUCCEEDED, at.status());
        }
    }

    @Test
    void replaySurvivesRestartAndOldAttemptsStayImmutable(@TempDir Path dir) {
        String original;
        String replay;
        try (LabEngine e = engine(dir)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", "ep1"); m.put("secret", "s3cret");
            m.put("initialBackoffMillis", 1); m.put("backoffMultiplier", 2.0);
            m.put("maxBackoffMillis", 10); m.put("maxAttempts", 2);
            m.put("behavior", "RATE_LIMIT"); m.put("retryAfter", "60");
            e.createEndpoint(m);
            original = createEvent(e, "evt-rr");
            e.step();
            replay = e.replayDeadLetter(original, new LinkedHashMap<>()).chainId();
        }
        try (LabEngine e2 = engine(dir)) {
            Models.Event r = e2.getChain(replay);
            assertEquals(original, r.replayOfChain());
            assertEquals(1, r.replayDepth());
            e2.updateEndpoint("ep1", Map.of("behavior", "SUCCESS"));
            e2.step();
            assertEquals(Models.EventStatus.SUCCEEDED, r.chainId() == null ? null :
                    e2.getChain(replay).status());
            assertEquals(Models.EventStatus.DEAD_LETTER, e2.getChain(original).status());
        }
    }

    @Test
    void restartAfterResponseLandedKeepsTerminalResult(@TempDir Path dir) {
        try (LabEngine e = engine(dir)) {
            endpoint(e, "SUCCESS", 200, 5, 60_000);
            createEvent(e, "evt-done");
            e.step();
        }
        try (LabEngine e2 = engine(dir)) {
            String chain = e2.listEvents().get(0).chainId();
            assertEquals(Models.EventStatus.SUCCEEDED, e2.getChain(chain).status());
            assertEquals(1, attempts(e2, chain), "no duplicate retry after restart");
            // no due work
            LabEngine.TickResult r = e2.step();
            assertEquals(0, r.dispatched());
        }
    }

    @Test
    void duplicateEventIdIsDurableAcrossRestart(@TempDir Path dir) {
        try (LabEngine e = engine(dir)) {
            endpoint(e, "SUCCESS", 200, 5, 60_000);
            createEvent(e, "evt-persist");
            e.step();
        }
        try (LabEngine e2 = engine(dir)) {
            Map<String, Object> req = new LinkedHashMap<>();
            req.put("endpointId", "ep1"); req.put("eventId", "evt-persist"); req.put("payload", "x");
            LabEngine.ApiException ex = assertThrows(LabEngine.ApiException.class,
                    () -> e2.createEvent(req));
            assertEquals(409, ex.status());
        }
    }

    @Test
    void updatingConfigDoesNotClearPauseAndPauseSurvivesRestart(@TempDir Path dir) {
        try (LabEngine e = engine(dir)) {
            endpoint(e, "SUCCESS", 200, 5, 60_000);
            e.setPaused("ep1", true);
            Map<String, Object> upd = new LinkedHashMap<>();
            upd.put("behavior", "TIMEOUT");
            e.updateEndpoint("ep1", upd);
            assertTrue(e.requireEndpoint("ep1").paused(), "config edit must not unpause");
            assertEquals("TIMEOUT", e.requireEndpoint("ep1").behavior());
        }
        try (LabEngine e2 = engine(dir)) {
            assertTrue(e2.requireEndpoint("ep1").paused(), "pause state is durable");
            assertEquals("TIMEOUT", e2.requireEndpoint("ep1").behavior());
        }
    }
}
