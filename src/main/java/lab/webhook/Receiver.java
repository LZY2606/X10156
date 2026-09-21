package lab.webhook;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The built-in in-process programmable receiver. Deliveries never leave the
 * JVM: this object plays the role of the remote HTTP endpoint.
 *
 * It:
 *  - verifies the signature over the exact bytes and covered headers
 *  - keeps an idempotency ledger keyed by event id ("processed" side effects)
 *  - simulates success, timeout, disconnect, 429, five 5xx statuses,
 *    and "processed but response lost"
 */
public final class Receiver {

    public static final Set<Integer> FIVE_X = Set.of(500, 501, 502, 503, 504);

    /** Durable side-effect committer (fsync-backed in production). */
    public interface Committer {
        void commitProcessed(String endpointId, String eventId, String attemptId, long atVirtual);
    }

    /** Normalized request handed to the in-process receiver. */
    public record Request(String endpointId, String method, String path, byte[] body,
                          Map<String, String> headers, List<String> covered,
                          String eventId, String attemptId, long timestamp,
                          String signature) {}

    private final Committer committer;

    public Receiver(Committer committer) {
        this.committer = committer;
    }

    public Models.ReceiverOutcome deliver(Request req, Models.Endpoint ep, long nowVirtual,
                                          Set<String> alreadyProcessed) {
        // 1) signature verification over actual bytes and covered headers
        Map<String, Object> verification = verify(req, ep);

        // 2) idempotency: a previously committed event is acknowledged, never re-run
        boolean duplicate = alreadyProcessed.contains(ep.id() + "|" + req.eventId());
        if (duplicate) {
            Map<String, String> h = baseHeaders();
            h.put("x-lab-duplicate", "true");
            return new Models.ReceiverOutcome(Models.Behavior.SUCCESS, 200, null,
                    "{\"ok\":true,\"duplicate\":true}", null, true, true,
                    "event already processed; idempotent acknowledgment", verification);
        }

        Models.Behavior behavior = Models.Behavior.from(ep.behavior());
        // invalid status config degrades to 500 so the lab still behaves safely
        if (behavior == Models.Behavior.STATUS && !FIVE_X.contains(ep.statusCode())) {
            behavior = Models.Behavior.STATUS;
        }
        int statusCode = behavior == Models.Behavior.STATUS
                ? (FIVE_X.contains(ep.statusCode()) ? ep.statusCode() : 500)
                : 0;

        switch (behavior) {
            case TIMEOUT -> {
                return new Models.ReceiverOutcome(Models.Behavior.TIMEOUT, null, null, null,
                        "simulated response timeout", false, false,
                        "receiver accepted connection but never responded", verification);
            }
            case DISCONNECT -> {
                return new Models.ReceiverOutcome(Models.Behavior.DISCONNECT, null, null, null,
                        "simulated connection reset", false, false,
                        "connection closed before response", verification);
            }
            case RATE_LIMIT -> {
                Map<String, String> h = baseHeaders();
                String ra = ep.retryAfter() == null || ep.retryAfter().isBlank() ? "2" : ep.retryAfter();
                h.put("retry-after", ra);
                String body = "{\"error\":\"rate limited\"}";
                return new Models.ReceiverOutcome(Models.Behavior.RATE_LIMIT, 429, ra, body, null,
                        false, false, "receiver returned 429 with Retry-After", verification);
            }
            case STATUS -> {
                String body = "{\"error\":\"server error " + statusCode + "\"}";
                return new Models.ReceiverOutcome(Models.Behavior.STATUS, statusCode, null, body,
                        null, false, false, "receiver returned " + statusCode, verification);
            }
            case LOST -> {
                // side effect is durably committed... but the response never arrives
                committer.commitProcessed(ep.id(), req.eventId(), req.attemptId(), nowVirtual);
                return new Models.ReceiverOutcome(Models.Behavior.LOST, null, null, null,
                        "response lost after processing", true, false,
                        "business logic committed; response lost on the way back", verification);
            }
            case SUCCESS -> {
                committer.commitProcessed(ep.id(), req.eventId(), req.attemptId(), nowVirtual);
                return new Models.ReceiverOutcome(Models.Behavior.SUCCESS, 200, null,
                        "{\"ok\":true}", null, true, false,
                        "processed and acknowledged", verification);
            }
            default -> throw new IllegalStateException("unknown behavior " + behavior);
        }
    }

    private Map<String, Object> verify(Request req, Models.Endpoint ep) {
        Map<String, Object> v = new LinkedHashMap<>();
        long ts;
        try {
            ts = Long.parseLong(req.headers().getOrDefault(Signer.HEADER_TIMESTAMP,
                    String.valueOf(req.timestamp())));
        } catch (NumberFormatException e) {
            ts = req.timestamp();
        }
        String computed;
        boolean valid;
        String reason;
        try {
            valid = Signer.verify(req.method(), req.path(), req.body(), req.headers(),
                    req.covered(), req.eventId(), req.attemptId(), ts, ep.secret(), req.signature());
            computed = Signer.hmac(Signer.canonicalInput(req.method(), req.path(), req.body(),
                    req.headers(), req.covered(), req.eventId(), req.attemptId(), ts), ep.secret());
            reason = valid ? "signature matches HMAC-SHA256"
                    : "signature does not match";
        } catch (Exception e) {
            valid = false;
            computed = null;
            reason = "verification error: " + e.getMessage();
        }
        v.put("valid", valid);
        v.put("algorithm", "HMAC-SHA256");
        v.put("computed", computed);
        v.put("provided", req.signature());
        v.put("bodySha256", Signer.sha256Hex(req.body()));
        v.put("bodyLength", req.body().length);
        v.put("reason", reason);
        return v;
    }

    private Map<String, String> baseHeaders() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("content-type", "application/json");
        h.put("x-powered-by", "webhook-delivery-lab-receiver");
        return h;
    }

}
