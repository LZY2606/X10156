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

/** Signature bytes, Retry-After (seconds/date), deterministic backoff and core flows. */
class DeliveryFlowTest {

    private Map<String, Object> endpoint(String behavior, int code, String retryAfter) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", "ep1");
        m.put("secret", "s3cret");
        m.put("initialBackoffMillis", 1000);
        m.put("backoffMultiplier", 2.0);
        m.put("maxBackoffMillis", 60_000);
        m.put("maxAttempts", 6);
        m.put("behavior", behavior);
        m.put("statusCode", code);
        if (retryAfter != null) m.put("retryAfter", retryAfter);
        return m;
    }

    private LabEngine fresh(Path dir) {
        LabEngine e = new LabEngine(dir);
        e.setJitterSeed(7);
        return e;
    }

    private Models.Event send(LabEngine e, String eventId, String payload) {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("endpointId", "ep1");
        req.put("eventId", eventId);
        req.put("payload", payload);
        return e.createEvent(req);
    }

    private Models.Attempt last(LabEngine e, String chainId) {
        List<Models.Attempt> ats = e.getChain(chainId).attempts();
        return ats.get(ats.size() - 1);
    }

    private Models.Attempt first(LabEngine e, String chainId) {
        return e.getChain(chainId).attempts().get(0);
    }

    @Test
    void signatureCoversExactBytesAndSelectedHeaders(@TempDir Path dir) {
        LabEngine e = fresh(dir);
        e.createEndpoint(endpoint("SUCCESS", 200, null));
        String payload = "{\"msg\":\"中文 ☃ – bytes\"}";
        Models.Event ev = send(e, "evt-sig", payload);

        Models.Attempt a = first(e, ev.chainId());
        byte[] body = Base64.getDecoder().decode(a.bodyBase64());
        assertArrayEquals(payload.getBytes(StandardCharsets.UTF_8), body, "stored bytes equal sent bytes");
        // canonical input embeds method/path/sha256(body)/covered headers/ids/timestamp
        String expected = Signer.canonicalInput("POST", a.path(), body,
                a.requestHeaders(), List.of("x-lab-key-id", "x-lab-event-id",
                        "x-lab-attempt-id", "x-lab-timestamp", "content-type"),
                "evt-sig", a.attemptId(), a.scheduledAt());
        assertEquals(expected, a.signingInput());
        assertEquals(Signer.sha256Hex(body), a.sha256());
        // recompute HMAC over the exact canonical string
        assertEquals(Signer.hmac(expected, "s3cret"), a.signature());
        // tampering with one byte must invalidate
        byte[] tampered = body.clone();
        tampered[2] ^= 1;
        String tamperedInput = Signer.canonicalInput("POST", a.path(), tampered,
                a.requestHeaders(), List.of("x-lab-key-id", "x-lab-event-id",
                        "x-lab-attempt-id", "x-lab-timestamp", "content-type"),
                "evt-sig", a.attemptId(), a.scheduledAt());
        assertNotEquals(a.signature(), Signer.hmac(tamperedInput, "s3cret"));
    }

    @Test
    void receiverVerifiesSignatureAndSucceeds(@TempDir Path dir) {
        LabEngine e = fresh(dir);
        e.createEndpoint(endpoint("SUCCESS", 200, null));
        Models.Event ev = send(e, "evt-ok", "{}");
        e.step();
        Models.Attempt a = last(e, ev.chainId());
        assertEquals(Models.AttemptStatus.SUCCEEDED, a.status());
        assertEquals(200, a.responseStatus());
        assertEquals(Boolean.TRUE, a.verification().get("valid"));
        assertEquals(Models.EventStatus.SUCCEEDED, e.getChain(ev.chainId()).status());
    }

    @Test
    void retryUsesNewAttemptIdAndTimestampButSameEventAndPayload(@TempDir Path dir) {
        LabEngine e = fresh(dir);
        e.createEndpoint(endpoint("TIMEOUT", 0, null));
        Models.Event ev = send(e, "evt-retry", "ORIGINAL");
        e.step(); // attempt 1 fails, schedules 2
        Models.Attempt a1 = e.getChain(ev.chainId()).attempts().get(0);
        assertEquals(Models.AttemptStatus.FAILED_RETRY, a1.status());
        assertNotNull(a1.nextScheduledAt());
        e.step(); // attempt 2
        Models.Attempt a2 = e.getChain(ev.chainId()).attempts().get(1);
        assertNotEquals(a1.attemptId(), a2.attemptId());
        assertTrue(a2.scheduledAt() > a1.scheduledAt());
        assertEquals("ORIGINAL",
                new String(Base64.getDecoder().decode(a2.bodyBase64()), StandardCharsets.UTF_8));
        assertEquals("evt-retry", a2.requestHeaders().get(Signer.HEADER_EVENT_ID));
        assertNotEquals(a1.signature(), a2.signature());
    }

    @Test
    void retryAfterSeconds(@TempDir Path dir) {
        LabEngine e = fresh(dir);
        Map<String, Object> ep = endpoint("RATE_LIMIT", 429, "3");
        ep.put("initialBackoffMillis", 1000);
        ep.put("maxBackoffMillis", 60_000);
        e.createEndpoint(ep);
        Models.Event ev = send(e, "evt-ra", "{}");
        long t0 = e.getClock();
        e.step();
        Models.Attempt a1 = e.getChain(ev.chainId()).attempts().get(0);
        assertEquals(429, a1.responseStatus());
        assertEquals(t0 + 3000, a1.nextScheduledAt());
        assertEquals("3s", a1.retryAfterParsed());
        // stepping does not dispatch before 3 virtual seconds
        e.advance(2999);
        assertEquals(2, e.getChain(ev.chainId()).attempts().size());
        e.advance(1);
        assertEquals(3, e.getChain(ev.chainId()).attempts().size());
    }

    @Test
    void retryAfterHttpDate(@TempDir Path dir) {
        LabEngine e = fresh(dir);
        long now = LabEngine.EPOCH_START;
        String httpDate = Backoff.httpDate(now + 5000);
        Map<String, Object> ep = endpoint("RATE_LIMIT", 429, httpDate);
        e.createEndpoint(ep);
        Models.Event ev = send(e, "evt-date", "{}");
        e.step();
        Models.Attempt a1 = e.getChain(ev.chainId()).attempts().get(0);
        assertEquals("http-date", a1.retryAfterParsed());
        assertEquals(now + 5000, a1.nextScheduledAt());
        Backoff.RetryAfter parsed = Backoff.parseRetryAfter(httpDate, now);
        assertNotNull(parsed);
        assertEquals(5000, parsed.delayMillis());
    }

    @Test
    void deterministicBackoffAcrossEngines(@TempDir Path dir) throws Exception {
        long[] observedDelays;
        try (LabEngine e = fresh(dir)) {
            e.createEndpoint(endpoint("TIMEOUT", 0, null));
            Models.Event ev = send(e, "evt-det", "x");
            List<Long> delays = new java.util.ArrayList<>();
            for (int i = 0; i < 4; i++) {
                int sizeBefore = e.getChain(ev.chainId()).attempts().size();
                e.step();
                Models.Attempt justResolved = e.getChain(ev.chainId()).attempts().get(sizeBefore - 1);
                delays.add(justResolved.nextScheduledAt() - justResolved.resolvedAt());
            }
            observedDelays = delays.stream().mapToLong(Long::longValue).toArray();
        }
        // recompute independently from the documented formula
        long seed = Backoff.chainSeed(7, "evt-det");
        long[] expected = new long[4];
        for (int i = 1; i <= 4; i++) {
            expected[i - 1] = Backoff.jitteredDelay(1000, 2.0, 60_000, seed, i);
        }
        assertArrayEquals(expected, observedDelays);
        // same inputs -> identical delay; different seed -> different schedule
        long d1 = Backoff.jitteredDelay(1000, 2.0, 60_000, seed, 3);
        long d2 = Backoff.jitteredDelay(1000, 2.0, 60_000, seed, 3);
        long d3 = Backoff.jitteredDelay(1000, 2.0, 60_000, Backoff.chainSeed(8, "evt-det"), 3);
        assertEquals(d1, d2);
        assertNotEquals(d1, d3);
    }

    @Test
    void allFiveServerStatusesAreRetryableThenSucceedWhenBehaviorChanges(@TempDir Path dir) {
        for (int code : List.of(500, 501, 502, 503, 504)) {
            Path sub = dir.resolve("code" + code);
            LabEngine e = fresh(sub);
            e.createEndpoint(endpoint("STATUS", code, null));
            Models.Event ev = send(e, "evt-" + code, "{}");
            e.step();
            Models.Attempt a = e.getChain(ev.chainId()).attempts().get(0);
            assertEquals(code, a.responseStatus());
            assertEquals(Models.AttemptStatus.FAILED_RETRY, a.status());
            e.close();
        }
    }

    @Test
    void retryAfterExceedingCapDeadLetters(@TempDir Path dir) {
        LabEngine e = fresh(dir);
        Map<String, Object> ep = endpoint("RATE_LIMIT", 429, "120");
        ep.put("maxBackoffMillis", 60_000);
        e.createEndpoint(ep);
        Models.Event ev = send(e, "evt-cap", "{}");
        e.step();
        Models.Attempt a = last(e, ev.chainId());
        assertEquals(Models.AttemptStatus.DEAD_LETTER, a.status());
        assertTrue(a.nextReason().contains("exceeds endpoint cap"));
        assertEquals(Models.EventStatus.DEAD_LETTER, e.getChain(ev.chainId()).status());
    }

    @Test
    void exhaustedAttemptsDeadLetter(@TempDir Path dir) {
        LabEngine e = fresh(dir);
        Map<String, Object> ep = endpoint("TIMEOUT", 0, null);
        ep.put("maxAttempts", 3);
        ep.put("initialBackoffMillis", 10);
        ep.put("maxBackoffMillis", 60_000);
        e.createEndpoint(ep);
        Models.Event ev = send(e, "evt-ex", "{}");
        for (int i = 0; i < 5; i++) e.step();
        assertEquals(Models.EventStatus.DEAD_LETTER, e.getChain(ev.chainId()).status());
        Models.Attempt last = last(e, ev.chainId());
        assertEquals(3, last.seq());
        assertTrue(last.nextReason().contains("max attempts"));
    }

    @Test
    void duplicateEventIdIsRejected(@TempDir Path dir) {
        LabEngine e = fresh(dir);
        e.createEndpoint(endpoint("SUCCESS", 200, null));
        send(e, "evt-dup", "a");
        LabEngine.ApiException ex = assertThrows(LabEngine.ApiException.class,
                () -> send(e, "evt-dup", "b"));
        assertEquals(409, ex.status());
        long chains = e.listEvents().size();
        assertEquals(1, chains);
    }

    @Test
    void disconnectAndTimeoutAreRetryable(@TempDir Path dir) {
        for (String b : List.of("TIMEOUT", "DISCONNECT")) {
            Path sub = dir.resolve(b);
            LabEngine e = fresh(sub);
            e.createEndpoint(endpoint(b, 0, null));
            Models.Event ev = send(e, "evt-" + b, "{}");
            e.step();
            Models.Attempt a = e.getChain(ev.chainId()).attempts().get(0);
            assertEquals(Models.AttemptStatus.FAILED_RETRY, a.status());
            assertNotNull(a.error());
            e.close();
        }
    }
}
