package webhooklab;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Domain model records and their JSON (de)serialization. */
public final class Model {

    private Model() {}

    public enum ChainStatus { ACTIVE, SUCCEEDED, FAILED, DEAD }

    public record Endpoint(
            String id,
            String secret,
            long maxAttempts,
            long baseBackoffMs,
            long maxBackoffMs,
            long jitterSeed,
            long timeoutMs,
            String behavior,
            boolean paused) {

        public static Endpoint defaults(String id) {
            return new Endpoint(id, "dev-secret-" + id, 5, 1000, 60000, 1, 5000, "success", false);
        }

        public Map<String, Object> toJson() {
            Map<String, Object> m = Json.obj();
            m.put("id", id);
            m.put("secret", secret);
            m.put("maxAttempts", maxAttempts);
            m.put("baseBackoffMs", baseBackoffMs);
            m.put("maxBackoffMs", maxBackoffMs);
            m.put("jitterSeed", jitterSeed);
            m.put("timeoutMs", timeoutMs);
            m.put("behavior", behavior);
            m.put("paused", paused);
            return m;
        }

        public static Endpoint fromJson(Map<String, Object> m) {
            Endpoint d = defaults(Json.str(m, "id", "endpoint"));
            return new Endpoint(
                    Json.str(m, "id", d.id),
                    Json.str(m, "secret", d.secret),
                    Json.lng(m, "maxAttempts", d.maxAttempts),
                    Json.lng(m, "baseBackoffMs", d.baseBackoffMs),
                    Json.lng(m, "maxBackoffMs", d.maxBackoffMs),
                    Json.lng(m, "jitterSeed", d.jitterSeed),
                    Json.lng(m, "timeoutMs", d.timeoutMs),
                    Json.str(m, "behavior", d.behavior),
                    Json.bool(m, "paused", d.paused));
        }
    }

    public record Event(String id, String endpointId, String payload, String contentType, long createdAt) {
        public Map<String, Object> toJson() {
            Map<String, Object> m = Json.obj();
            m.put("id", id);
            m.put("endpointId", endpointId);
            m.put("payload", payload);
            m.put("contentType", contentType);
            m.put("createdAt", createdAt);
            return m;
        }

        public static Event fromJson(Map<String, Object> m) {
            return new Event(
                    Json.str(m, "id"),
                    Json.str(m, "endpointId"),
                    Json.str(m, "payload", ""),
                    Json.str(m, "contentType", "application/json"),
                    Json.lng(m, "createdAt", 0));
        }
    }

    /** A delivery attempt: immutable once recorded. */
    public record Attempt(
            String id,
            String chainId,
            String eventId,
            String endpointId,
            int attemptNo,
            long scheduledAt,
            String reasonCode,
            String reason,
            String payload,
            String contentType,
            Long sentAt,
            String signatureInput,
            String signature,
            List<String> signedHeaders,
            String bodySha256) {

        public Map<String, Object> toJson() {
            Map<String, Object> m = Json.obj();
            m.put("id", id);
            m.put("chainId", chainId);
            m.put("eventId", eventId);
            m.put("endpointId", endpointId);
            m.put("attemptNo", attemptNo);
            m.put("scheduledAt", scheduledAt);
            m.put("reasonCode", reasonCode);
            m.put("reason", reason);
            m.put("payload", payload);
            m.put("contentType", contentType);
            m.put("sentAt", sentAt);
            m.put("signatureInput", signatureInput);
            m.put("signature", signature);
            List<Object> sh = new ArrayList<>();
            for (String h : signedHeaders) sh.add(h);
            m.put("signedHeaders", sh);
            m.put("bodySha256", bodySha256);
            return m;
        }

        public static Attempt fromJson(Map<String, Object> m) {
            List<String> sh = new ArrayList<>();
            List<Object> raw = Json.list(m, "signedHeaders");
            if (raw != null) for (Object o : raw) sh.add(String.valueOf(o));
            Object sentAt = m.get("sentAt");
            return new Attempt(
                    Json.str(m, "id"),
                    Json.str(m, "chainId"),
                    Json.str(m, "eventId"),
                    Json.str(m, "endpointId"),
                    Json.itg(m, "attemptNo", 1),
                    Json.lng(m, "scheduledAt", 0),
                    Json.str(m, "reasonCode", "INITIAL"),
                    Json.str(m, "reason", ""),
                    Json.str(m, "payload", ""),
                    Json.str(m, "contentType", "application/json"),
                    sentAt == null ? null : ((Number) sentAt).longValue(),
                    Json.str(m, "signatureInput"),
                    Json.str(m, "signature"),
                    sh,
                    Json.str(m, "bodySha256"));
        }
    }

    /** Terminal outcome of an attempt; append-only, never mutated. */
    public record AttemptResult(
            String attemptId,
            String chainId,
            String endpointId,
            long completedAt,
            String outcome,
            Integer status,
            String responseBody,
            String responseHeaders,
            String failureKind,
            String nextReasonCode,
            String nextReason,
            Long nextAttemptId,
            Long nextDelayMs) {

        public Map<String, Object> toJson() {
            Map<String, Object> m = Json.obj();
            m.put("attemptId", attemptId);
            m.put("chainId", chainId);
            m.put("endpointId", endpointId);
            m.put("completedAt", completedAt);
            m.put("outcome", outcome);
            m.put("status", status);
            m.put("responseBody", responseBody);
            m.put("responseHeaders", responseHeaders);
            m.put("failureKind", failureKind);
            m.put("nextReasonCode", nextReasonCode);
            m.put("nextReason", nextReason);
            m.put("nextAttemptId", nextAttemptId);
            m.put("nextDelayMs", nextDelayMs);
            return m;
        }

        public static AttemptResult fromJson(Map<String, Object> m) {
            return new AttemptResult(
                    Json.str(m, "attemptId"),
                    Json.str(m, "chainId"),
                    Json.str(m, "endpointId"),
                    Json.lng(m, "completedAt", 0),
                    Json.str(m, "outcome"),
                    m.get("status") == null ? null : ((Number) m.get("status")).intValue(),
                    Json.str(m, "responseBody"),
                    Json.str(m, "responseHeaders"),
                    Json.str(m, "failureKind"),
                    Json.str(m, "nextReasonCode"),
                    Json.str(m, "nextReason"),
                    m.get("nextAttemptId") == null ? null : ((Number) m.get("nextAttemptId")).longValue(),
                    m.get("nextDelayMs") == null ? null : ((Number) m.get("nextDelayMs")).longValue());
        }
    }

    public record Chain(
            String id,
            String eventId,
            String endpointId,
            ChainStatus status,
            long createdAt,
            String originChainId,
            String replayOfChainId,
            Long endedAt,
            String endReason) {

        public Map<String, Object> toJson() {
            Map<String, Object> m = Json.obj();
            m.put("id", id);
            m.put("eventId", eventId);
            m.put("endpointId", endpointId);
            m.put("status", status.name());
            m.put("createdAt", createdAt);
            m.put("originChainId", originChainId);
            m.put("replayOfChainId", replayOfChainId);
            m.put("endedAt", endedAt);
            m.put("endReason", endReason);
            return m;
        }

        public static Chain fromJson(Map<String, Object> m) {
            return new Chain(
                    Json.str(m, "id"),
                    Json.str(m, "eventId"),
                    Json.str(m, "endpointId"),
                    ChainStatus.valueOf(Json.str(m, "status", "ACTIVE")),
                    Json.lng(m, "createdAt", 0),
                    Json.str(m, "originChainId"),
                    Json.str(m, "replayOfChainId"),
                    m.get("endedAt") == null ? null : ((Number) m.get("endedAt")).longValue(),
                    Json.str(m, "endReason"));
        }
    }

    /** Receiver-side log entry: what the built-in receiver saw and decided. */
    public record ReceiverEntry(
            long seq,
            long at,
            String endpointId,
            String eventId,
            String attemptId,
            String chainId,
            String behavior,
            boolean duplicate,
            boolean signatureValid,
            String signatureInput,
            String expectedSignature,
            String receivedSignature,
            String bodySha256,
            String outcome,
            Integer status,
            String responseBody,
            String responseHeaders) {

        public Map<String, Object> toJson() {
            Map<String, Object> m = Json.obj();
            m.put("seq", seq);
            m.put("at", at);
            m.put("endpointId", endpointId);
            m.put("eventId", eventId);
            m.put("attemptId", attemptId);
            m.put("chainId", chainId);
            m.put("behavior", behavior);
            m.put("duplicate", duplicate);
            m.put("signatureValid", signatureValid);
            m.put("signatureInput", signatureInput);
            m.put("expectedSignature", expectedSignature);
            m.put("receivedSignature", receivedSignature);
            m.put("bodySha256", bodySha256);
            m.put("outcome", outcome);
            m.put("status", status);
            m.put("responseBody", responseBody);
            m.put("responseHeaders", responseHeaders);
            return m;
        }

        public static ReceiverEntry fromJson(Map<String, Object> m) {
            return new ReceiverEntry(
                    Json.lng(m, "seq", 0),
                    Json.lng(m, "at", 0),
                    Json.str(m, "endpointId"),
                    Json.str(m, "eventId"),
                    Json.str(m, "attemptId"),
                    Json.str(m, "chainId"),
                    Json.str(m, "behavior"),
                    Json.bool(m, "duplicate", false),
                    Json.bool(m, "signatureValid", false),
                    Json.str(m, "signatureInput"),
                    Json.str(m, "expectedSignature"),
                    Json.str(m, "receivedSignature"),
                    Json.str(m, "bodySha256"),
                    Json.str(m, "outcome"),
                    m.get("status") == null ? null : ((Number) m.get("status")).intValue(),
                    Json.str(m, "responseBody"),
                    Json.str(m, "responseHeaders"));
        }
    }
}
