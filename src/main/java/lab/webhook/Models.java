package lab.webhook;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Domain model records and enums for the delivery lab. */
public final class Models {

    private Models() {}

    /** What a programmable receiver should do when an attempt reaches it. */
    public enum Behavior {
        SUCCESS,        // 200, normal response
        TIMEOUT,        // simulated response timeout (retryable)
        DISCONNECT,     // simulated connection drop (retryable)
        RATE_LIMIT,     // 429 with Retry-After (seconds or http-date)
        STATUS,         // one of five server-side statuses
        LOST;           // processed and committed, but response never comes back

        public static Behavior from(String s) {
            if (s == null || s.isBlank()) return SUCCESS;
            return valueOf(s.trim().toUpperCase());
        }
    }

    public enum AttemptStatus {
        PENDING,        // scheduled, not yet dispatched
        IN_FLIGHT,      // delivered to receiver, awaiting outcome
        SUCCEEDED,
        FAILED_RETRY,   // failed, another attempt scheduled
        DEAD_LETTER,   // exhausted / over cap / terminal
        RECOVERED;      // crash recovery: outcome unknown, rescheduled

        public boolean terminal() {
            return this == SUCCEEDED || this == DEAD_LETTER;
        }
    }

    public enum EventStatus {
        PENDING, IN_FLIGHT, SUCCEEDED, DEAD_LETTER
    }

    public record Endpoint(String id, String name, String secret, String keyId,
                           String coveredHeaders,
                           long initialBackoffMillis, double backoffMultiplier,
                           long maxBackoffMillis, int maxAttempts,
                           boolean paused, String behavior, int statusCode,
                           String retryAfter, String createdAt) {

        public List<String> covered() { return Signer.coveredHeaderNames(coveredHeaders); }
    }

    /** Outcome produced by the programmable receiver for one attempt. */
    public record ReceiverOutcome(Behavior behavior, Integer statusCode, String retryAfter,
                                  String body, String error, boolean processed, boolean duplicate,
                                  String receiverLog, Map<String, Object> verification) {}

    /**
     * One delivery attempt. Stored as the full normalized request, signing input,
     * response, next schedule reason and final status.
     */
    public record Attempt(String attemptId, int seq, long scheduledAt, long dispatchedAt,
                          long resolvedAt,
                          String method, String path,
                          Map<String, String> requestHeaders, String bodyBase64, long bodyLength,
                          String sha256, String signingInput, String signature,
                          AttemptStatus status,
                          Integer responseStatus, Map<String, String> responseHeaders,
                          String responseBody, String error,
                          boolean receiverProcessed, Map<String, Object> verification,
                          Long nextScheduledAt, String nextReason,
                          String crashedAt, String replayOfChain, String retryAfterParsed) {

        public Map<String, Object> toView() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("attemptId", attemptId);
            m.put("seq", seq);
            m.put("scheduledAt", scheduledAt);
            m.put("dispatchedAt", dispatchedAt);
            m.put("resolvedAt", resolvedAt);
            m.put("method", method);
            m.put("path", path);
            m.put("requestHeaders", requestHeaders);
            m.put("bodyBase64", bodyBase64);
            m.put("bodyLength", bodyLength);
            m.put("sha256", sha256);
            m.put("signingInput", signingInput);
            m.put("signature", signature);
            m.put("status", status.name());
            m.put("responseStatus", responseStatus);
            m.put("responseHeaders", responseHeaders);
            m.put("responseBody", responseBody);
            m.put("error", error);
            m.put("receiverProcessed", receiverProcessed);
            m.put("verification", verification);
            m.put("nextScheduledAt", nextScheduledAt);
            m.put("nextReason", nextReason);
            m.put("crashedAt", crashedAt);
            m.put("replayOfChain", replayOfChain);
            m.put("retryAfterParsed", retryAfterParsed);
            return m;
        }
    }

    /** A delivery chain: one event plus its immutable ordered attempts. */
    public record Event(String eventId, String endpointId, String chainId,
                        String payload, String contentType, long createdAt,
                        EventStatus status, List<Attempt> attempts,
                        String replayOfChain, long replayDepth) {

        public Event withStatus(EventStatus s) {
            return new Event(eventId, endpointId, chainId, payload, contentType, createdAt,
                    s, attempts, replayOfChain, replayDepth);
        }

        public Event withAttempts(List<Attempt> a, EventStatus s) {
            return new Event(eventId, endpointId, chainId, payload, contentType, createdAt,
                    s, a, replayOfChain, replayDepth);
        }

        public Map<String, Object> toView() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("eventId", eventId);
            m.put("endpointId", endpointId);
            m.put("chainId", chainId);
            m.put("payload", payload);
            m.put("contentType", contentType);
            m.put("createdAt", createdAt);
            m.put("status", status.name());
            List<Object> ats = new ArrayList<>();
            attempts.forEach(a -> ats.add(a.toView()));
            m.put("attempts", ats);
            m.put("replayOfChain", replayOfChain);
            m.put("replayDepth", replayDepth);
            return m;
        }
    }
}
