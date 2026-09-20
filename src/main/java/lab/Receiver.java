package lab;

import lab.Model.Attempt;
import lab.Model.Endpoint;
import lab.Model.EventRec;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * In-process programmable receiver with its own independently persisted state,
 * simulating a remote endpoint with its own database. Deliveries never leave
 * the process; no arbitrary URL requests are possible.
 */
public final class Receiver {
    public static final class ReceiverException extends RuntimeException {
        ReceiverException(String kind) { super(kind); }
    }

    public static final class Response {
        public final int status;
        public final String headers;
        public final String body;
        Response(int status, String headers, String body) {
            this.status = status;
            this.headers = headers;
            this.body = body;
        }
    }

    /** Observes a delivery while it is in flight (used to test pause races). */
    public interface DeliveryListener {
        void onDeliver(Endpoint endpoint, EventRec event, Attempt attempt);
    }

    private final StateStore store;
    private final LinkedHashMap<String, Response> byAttempt = new LinkedHashMap<>();
    private final Set<String> processedEvents = new LinkedHashSet<>();
    private DeliveryListener listener;

    public Receiver(StateStore store) {
        this.store = store;
        if (store.exists()) {
            Map<String, Object> m = Json.readObject(store.load());
            @SuppressWarnings("unchecked")
            Map<String, Object> attempts = (Map<String, Object>) m.get("byAttempt");
            for (Map.Entry<String, Object> e : attempts.entrySet()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> r = (Map<String, Object>) e.getValue();
                byAttempt.put(e.getKey(), new Response(
                        (int) Json.num(r, "status"), Json.str(r, "headers"), Json.str(r, "body")));
            }
            @SuppressWarnings("unchecked")
            java.util.List<Object> events = (java.util.List<Object>) m.get("processedEvents");
            for (Object o : events) processedEvents.add((String) o);
        }
    }

    public void setListener(DeliveryListener listener) {
        this.listener = listener;
    }

    public boolean hasProcessed(String endpointId, String eventId) {
        return processedEvents.contains(endpointId + "|" + eventId);
    }

    public int processedCount() {
        return processedEvents.size();
    }

    public Response deliver(Endpoint endpoint, EventRec event, Attempt attempt) {
        // Idempotent replay: a retried execution of the very same persisted attempt
        // (e.g. sender crashed after we committed but before it recorded our response)
        // returns the originally committed response.
        Response committed = byAttempt.get(attempt.attemptId);
        if (committed != null) {
            return committed;
        }
        if (listener != null) {
            listener.onDeliver(endpoint, event, attempt);
        }
        switch (endpoint.mode) {
            case OK -> {
                return commit(endpoint, event, attempt, new Response(200, "", "ok"));
            }
            case TIMEOUT -> throw new ReceiverException("TIMEOUT");
            case DISCONNECT -> throw new ReceiverException("DISCONNECT");
            case RATE_LIMIT -> {
                return new Response(429, "Retry-After: " + endpoint.retryAfter, "rate limited");
            }
            case SERVER_500 -> { return new Response(500, "", "internal error"); }
            case SERVER_502 -> { return new Response(502, "", "bad gateway"); }
            case SERVER_503 -> { return new Response(503, "", "service unavailable"); }
            case SERVER_504 -> { return new Response(504, "", "gateway timeout"); }
            case SERVER_507 -> { return new Response(507, "", "insufficient storage"); }
            case LOST_RESPONSE -> {
                if (hasProcessed(endpoint.id, event.eventId)) {
                    // Duplicate delivery of an already-processed event: fast idempotent 200.
                    return new Response(200, "X-Wh-Duplicate: true", "already processed");
                }
                // Commit the processing to our own store, then the response is lost on the wire.
                commit(endpoint, event, attempt, new Response(200, "", "ok"));
                throw new ReceiverException("TIMEOUT");
            }
            default -> throw new IllegalStateException("unknown mode " + endpoint.mode);
        }
    }

    private Response commit(Endpoint endpoint, EventRec event, Attempt attempt, Response response) {
        byAttempt.put(attempt.attemptId, response);
        processedEvents.add(endpoint.id + "|" + event.eventId);
        persist();
        return response;
    }

    private void persist() {
        Map<String, Object> m = Json.obj();
        Map<String, Object> attempts = Json.obj();
        for (Map.Entry<String, Response> e : byAttempt.entrySet()) {
            Map<String, Object> r = Json.obj();
            r.put("status", (long) e.getValue().status);
            r.put("headers", e.getValue().headers);
            r.put("body", e.getValue().body);
            attempts.put(e.getKey(), r);
        }
        m.put("byAttempt", attempts);
        java.util.List<Object> ev = Json.arr();
        ev.addAll(processedEvents);
        m.put("processedEvents", ev);
        store.save(Json.write(m));
    }
}
