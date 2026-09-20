package lab;

import java.util.LinkedHashMap;
import java.util.Map;

/** Persisted domain model. All records serialize to/from JSON maps. */
public final class Model {
    private Model() {}

    public enum ReceiverMode {
        OK, TIMEOUT, DISCONNECT, RATE_LIMIT,
        SERVER_500, SERVER_502, SERVER_503, SERVER_504, SERVER_507,
        LOST_RESPONSE
    }

    public static final class Endpoint {
        public String id;
        public String name;
        public String secret;
        public int maxAttempts;
        public boolean paused;
        public ReceiverMode mode;
        public String retryAfter; // raw Retry-After value used in RATE_LIMIT mode (seconds or HTTP date)

        public Map<String, Object> toJson() {
            Map<String, Object> m = Json.obj();
            m.put("id", id);
            m.put("name", name);
            m.put("secret", secret);
            m.put("maxAttempts", (long) maxAttempts);
            m.put("paused", paused);
            m.put("mode", mode.name());
            m.put("retryAfter", retryAfter);
            return m;
        }

        public static Endpoint fromJson(Map<String, Object> m) {
            Endpoint e = new Endpoint();
            e.id = Json.str(m, "id");
            e.name = Json.str(m, "name");
            e.secret = Json.str(m, "secret");
            e.maxAttempts = (int) Json.num(m, "maxAttempts");
            e.paused = Boolean.TRUE.equals(m.get("paused"));
            e.mode = ReceiverMode.valueOf(Json.str(m, "mode"));
            e.retryAfter = Json.str(m, "retryAfter");
            return e;
        }
    }

    public static final class EventRec {
        public String eventId;
        public String endpointId;
        public String payload;
        public long createdAt;

        public Map<String, Object> toJson() {
            Map<String, Object> m = Json.obj();
            m.put("eventId", eventId);
            m.put("endpointId", endpointId);
            m.put("payload", payload);
            m.put("createdAt", createdAt);
            return m;
        }

        public static EventRec fromJson(Map<String, Object> m) {
            EventRec e = new EventRec();
            e.eventId = Json.str(m, "eventId");
            e.endpointId = Json.str(m, "endpointId");
            e.payload = Json.str(m, "payload");
            e.createdAt = Json.num(m, "createdAt");
            return e;
        }
    }

    public static final class Chain {
        public String chainId;
        public String eventId;
        public String endpointId;
        public String replayedFrom; // nullable chain id
        public String status; // ACTIVE, SUCCEEDED, DEAD
        public long createdAt;

        public Map<String, Object> toJson() {
            Map<String, Object> m = Json.obj();
            m.put("chainId", chainId);
            m.put("eventId", eventId);
            m.put("endpointId", endpointId);
            m.put("replayedFrom", replayedFrom);
            m.put("status", status);
            m.put("createdAt", createdAt);
            return m;
        }

        public static Chain fromJson(Map<String, Object> m) {
            Chain c = new Chain();
            c.chainId = Json.str(m, "chainId");
            c.eventId = Json.str(m, "eventId");
            c.endpointId = Json.str(m, "endpointId");
            c.replayedFrom = Json.str(m, "replayedFrom");
            c.status = Json.str(m, "status");
            c.createdAt = Json.num(m, "createdAt");
            return c;
        }
    }

    public static final class Attempt {
        public String attemptId;
        public String chainId;
        public String endpointId;
        public String eventId;
        public int number;
        public long scheduledAt;
        public long timestamp; // signing timestamp, fixed when the attempt is scheduled
        public String status = "SCHEDULED"; // SCHEDULED, SUCCESS, FAILED
        public String canonicalRequest; // exact bytes sent (as text)
        public String signatureInput;
        public String signature;
        public Integer responseStatus;
        public String responseHeaders;
        public String responseBody;
        public String errorKind; // TIMEOUT / DISCONNECT / null
        public String nextReason = "PENDING"; // PENDING, NONE_SUCCESS, NONE_DEAD, BACKOFF, RETRY_AFTER
        public Long completedAt;

        public Map<String, Object> toJson() {
            Map<String, Object> m = Json.obj();
            m.put("attemptId", attemptId);
            m.put("chainId", chainId);
            m.put("endpointId", endpointId);
            m.put("eventId", eventId);
            m.put("number", (long) number);
            m.put("scheduledAt", scheduledAt);
            m.put("timestamp", timestamp);
            m.put("status", status);
            m.put("canonicalRequest", canonicalRequest);
            m.put("signatureInput", signatureInput);
            m.put("signature", signature);
            m.put("responseStatus", responseStatus == null ? null : (long) responseStatus);
            m.put("responseHeaders", responseHeaders);
            m.put("responseBody", responseBody);
            m.put("errorKind", errorKind);
            m.put("nextReason", nextReason);
            m.put("completedAt", completedAt);
            return m;
        }

        public static Attempt fromJson(Map<String, Object> m) {
            Attempt a = new Attempt();
            a.attemptId = Json.str(m, "attemptId");
            a.chainId = Json.str(m, "chainId");
            a.endpointId = Json.str(m, "endpointId");
            a.eventId = Json.str(m, "eventId");
            a.number = (int) Json.num(m, "number");
            a.scheduledAt = Json.num(m, "scheduledAt");
            a.timestamp = Json.num(m, "timestamp");
            a.status = Json.str(m, "status");
            a.canonicalRequest = Json.str(m, "canonicalRequest");
            a.signatureInput = Json.str(m, "signatureInput");
            a.signature = Json.str(m, "signature");
            Object rs = m.get("responseStatus");
            a.responseStatus = rs == null ? null : (int) ((Number) rs).longValue();
            a.responseHeaders = Json.str(m, "responseHeaders");
            a.responseBody = Json.str(m, "responseBody");
            a.errorKind = Json.str(m, "errorKind");
            a.nextReason = Json.str(m, "nextReason");
            Object ca = m.get("completedAt");
            a.completedAt = ca == null ? null : ((Number) ca).longValue();
            return a;
        }
    }

    /** Whole sender-side lab state. */
    public static final class LabState {
        public long now;
        public long seq;
        public final LinkedHashMap<String, Endpoint> endpoints = new LinkedHashMap<>();
        public final LinkedHashMap<String, EventRec> events = new LinkedHashMap<>(); // key endpointId|eventId
        public final LinkedHashMap<String, Chain> chains = new LinkedHashMap<>();
        public final LinkedHashMap<String, Attempt> attempts = new LinkedHashMap<>();

        @SuppressWarnings("unchecked")
        public static LabState fromJson(Map<String, Object> m) {
            LabState s = new LabState();
            s.now = Json.num(m, "now");
            s.seq = Json.num(m, "seq");
            for (Object o : (Iterable<Object>) m.get("endpoints")) {
                Endpoint e = Endpoint.fromJson((Map<String, Object>) o);
                s.endpoints.put(e.id, e);
            }
            for (Object o : (Iterable<Object>) m.get("events")) {
                EventRec e = EventRec.fromJson((Map<String, Object>) o);
                s.events.put(e.endpointId + "|" + e.eventId, e);
            }
            for (Object o : (Iterable<Object>) m.get("chains")) {
                Chain c = Chain.fromJson((Map<String, Object>) o);
                s.chains.put(c.chainId, c);
            }
            for (Object o : (Iterable<Object>) m.get("attempts")) {
                Attempt a = Attempt.fromJson((Map<String, Object>) o);
                s.attempts.put(a.attemptId, a);
            }
            return s;
        }

        public Map<String, Object> toJson() {
            Map<String, Object> m = Json.obj();
            m.put("now", now);
            m.put("seq", seq);
            m.put("endpoints", endpoints.values().stream().map(Endpoint::toJson).collect(java.util.stream.Collectors.toList()));
            m.put("events", events.values().stream().map(EventRec::toJson).collect(java.util.stream.Collectors.toList()));
            m.put("chains", chains.values().stream().map(Chain::toJson).collect(java.util.stream.Collectors.toList()));
            m.put("attempts", attempts.values().stream().map(Attempt::toJson).collect(java.util.stream.Collectors.toList()));
            return m;
        }
    }
}
