package webhooklab;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Built-in, in-process programmable receiver. Deliveries are plain method
 * calls — the lab never opens network connections to arbitrary URLs.
 *
 * Behavior script per endpoint, contacts separated by ';':
 *   success | timeout | disconnect | lost
 *   status:&lt;code&gt;[:retry-after=&lt;seconds&gt;|httpdate[+&lt;ms&gt;]]
 * When the script is exhausted the receiver answers 'success'.
 *
 * 'lost' models "processed but the response was lost": the receiver durably
 * records the processing (idempotency mark) but the caller sees a lost
 * response and will retry; the retry then hits the duplicate fast-path.
 */
public final class Receiver {

    /** What the receiver decided, returned to the sender. */
    public sealed interface Outcome {
        record Responded(int status, String body, Map<String, String> headers) implements Outcome {}
        record Timeout(long afterMs) implements Outcome {}
        record Disconnected() implements Outcome {}
        record Lost() implements Outcome {}
    }

    private final Store store;

    public Receiver(Store store) {
        this.store = store;
    }

    /** Delivers one attempt to the receiver. Never performs network I/O. */
    public Outcome deliver(Model.Endpoint endpoint, Model.Attempt attempt, long timestampMs) {
        byte[] bodyBytes = attempt.payload().getBytes(StandardCharsets.UTF_8);
        String bodySha = Signer.sha256Hex(bodyBytes);
        Map<String, String> headers = Signer.buildHeaders(attempt, timestampMs, bodySha);
        List<String> signedHeaders = Signer.defaultSignedHeaders();
        String signatureInput = Signer.signatureInput(attempt.id(), timestampMs, headers, signedHeaders);
        String signature = Signer.hmacBase64(endpoint.secret(), signatureInput);

        // Receiver-side independent verification of what it "received".
        boolean signatureValid = Signer.verify(endpoint.secret(), signatureInput, signature);

        boolean duplicate = store.isProcessed(endpoint.id(), attempt.eventId());
        String behavior = duplicate ? "duplicate" : nextBehavior(endpoint);

        Outcome outcome;
        Integer status = null;
        String responseBody = null;
        String responseHeaders = null;
        String outcomeName;

        if (duplicate) {
            status = 200;
            responseBody = "{\"duplicate\":true}";
            outcome = new Outcome.Responded(status, responseBody, Map.of());
            outcomeName = "RESPONDED";
        } else {
            switch (behavior) {
                case "timeout" -> {
                    outcome = new Outcome.Timeout(endpoint.timeoutMs());
                    outcomeName = "TIMEOUT";
                }
                case "disconnect" -> {
                    outcome = new Outcome.Disconnected();
                    outcomeName = "DISCONNECTED";
                }
                case "lost" -> {
                    // Process durably, then "lose" the response.
                    store.markProcessed(endpoint.id(), attempt.eventId());
                    outcome = new Outcome.Lost();
                    outcomeName = "LOST";
                }
                case "success" -> {
                    status = 200;
                    responseBody = "{\"ok\":true}";
                    outcome = new Outcome.Responded(status, responseBody, Map.of());
                    outcomeName = "RESPONDED";
                }
                default -> {
                    if (behavior.startsWith("status:")) {
                        StatusSpec spec = parseStatus(behavior, timestampMs);
                        status = spec.status;
                        responseBody = "{\"status\":" + spec.status + "}";
                        responseHeaders = spec.retryAfterHeader;
                        outcome = new Outcome.Responded(spec.status, responseBody,
                                spec.retryAfterHeader == null ? Map.of() : Map.of("retry-after", spec.retryAfterHeader));
                        outcomeName = "RESPONDED";
                    } else {
                        status = 200;
                        responseBody = "{\"ok\":true}";
                        outcome = new Outcome.Responded(status, responseBody, Map.of());
                        outcomeName = "RESPONDED";
                    }
                }
            }
            // Idempotency mark is recorded once the receiver has fully handled
            // the event (success, terminal failure, or lost response).
            if (outcomeName.equals("RESPONDED") && status != null && isTerminalForReceiver(status)) {
                store.markProcessed(endpoint.id(), attempt.eventId());
            }
        }

        Model.ReceiverEntry entry = new Model.ReceiverEntry(
                store.nextReceiverSeq,
                timestampMs,
                endpoint.id(),
                attempt.eventId(),
                attempt.id(),
                attempt.chainId(),
                behavior,
                duplicate,
                signatureValid,
                signatureInput,
                signature,
                signature,
                bodySha,
                outcomeName,
                status,
                responseBody,
                responseHeaders);
        store.putReceiverEntry(entry);
        return outcome;
    }

    private static boolean isTerminalForReceiver(int status) {
        if (status >= 200 && status < 300) return true;
        if (status == 408 || status == 429) return false;
        if (status >= 500) return false;
        return status >= 300; // 3xx and non-retryable 4xx are terminal
    }

    /** Returns the scripted behavior for the next contact with this endpoint. */
    private String nextBehavior(Model.Endpoint endpoint) {
        String script = endpoint.behavior() == null ? "success" : endpoint.behavior().trim();
        if (script.isEmpty()) return "success";
        String[] parts = script.split(";");
        long contacts = 0;
        for (Model.ReceiverEntry e : store.receiverLog) {
            if (e.endpointId().equals(endpoint.id())) contacts++;
        }
        if (contacts >= parts.length) {
            // A single-element script is "sticky": it models a receiver that
            // keeps behaving that way. A multi-element sequence, once fully
            // consumed, falls back to success.
            return parts.length == 1 ? parts[0].trim() : "success";
        }
        return parts[(int) contacts].trim();
    }

    private record StatusSpec(int status, String retryAfterHeader) {}

    private static StatusSpec parseStatus(String behavior, long nowMs) {
        // status:<code>[:retry-after=<seconds>|httpdate|httpdate+<ms>]
        String[] tokens = behavior.split(":");
        int status = 200;
        String retryAfter = null;
        if (tokens.length >= 2) {
            try {
                status = Integer.parseInt(tokens[1].trim());
            } catch (NumberFormatException ignored) {
                status = 200;
            }
        }
        for (int i = 2; i < tokens.length; i++) {
            String t = tokens[i].trim();
            if (t.startsWith("retry-after=")) {
                String v = t.substring("retry-after=".length()).trim();
                if (v.equals("httpdate")) {
                    retryAfter = Backoff.httpDate(nowMs + 3000);
                } else if (v.startsWith("httpdate+")) {
                    long delta = Long.parseLong(v.substring("httpdate+".length()));
                    retryAfter = Backoff.httpDate(nowMs + delta);
                } else {
                    retryAfter = v;
                }
            }
        }
        return new StatusSpec(status, retryAfter);
    }

    /** Human-readable behavior catalog for the UI. */
    public static List<Map<String, Object>> behaviorCatalog() {
        List<Map<String, Object>> out = new ArrayList<>();
        out.add(Map.of("value", "success", "label", "成功 (200)"));
        out.add(Map.of("value", "timeout", "label", "响应超时"));
        out.add(Map.of("value", "disconnect", "label", "连接断开"));
        out.add(Map.of("value", "lost", "label", "已处理但响应丢失"));
        out.add(Map.of("value", "status:200", "label", "2xx 成功"));
        out.add(Map.of("value", "status:301", "label", "3xx 重定向（终态失败）"));
        out.add(Map.of("value", "status:400", "label", "4xx 客户端错误（终态失败）"));
        out.add(Map.of("value", "status:410", "label", "410 Gone（终态失败）"));
        out.add(Map.of("value", "status:429:retry-after=2", "label", "429 + Retry-After 秒数"));
        out.add(Map.of("value", "status:429:retry-after=httpdate+3000", "label", "429 + Retry-After HTTP 日期"));
        out.add(Map.of("value", "status:500", "label", "5xx 服务端错误（可重试）"));
        return out;
    }
}
