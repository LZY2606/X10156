package webhooklab;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Core delivery engine driven by a manual virtual clock.
 *
 * All state transitions are persisted before side effects (an in-process
 * "send"), and the event loop is fully deterministic: stepping the clock
 * always produces the same chain of attempts on a fresh process as in the
 * one that crashed.
 */
public final class Engine {

    /** Crash injection points for the restart/recovery tests. */
    public enum CrashPoint { NONE, AFTER_SCHEDULE, BEFORE_RESULT }

    public static class CrashHook {
        public void onAttemptScheduled(String attemptId) {}
        /** After the durable sent marker, immediately before the in-process send. */
        public void beforeSend(String attemptId) {}
        public void beforeResultPersisted(String attemptId) {}
    }

    public static final class HardExitHook extends CrashHook {
        private final CrashPoint point;
        public HardExitHook(CrashPoint point) { this.point = point; }
        @Override public void onAttemptScheduled(String attemptId) {
            if (point == CrashPoint.AFTER_SCHEDULE) Runtime.getRuntime().halt(77);
        }
        @Override public void beforeResultPersisted(String attemptId) {
            if (point == CrashPoint.BEFORE_RESULT) Runtime.getRuntime().halt(78);
        }
    }

    public static class ApiError extends RuntimeException {
        public final int status;
        public ApiError(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    private final Store store;
    private final Receiver receiver;
    private CrashHook crashHook = new CrashHook();

    public Engine(Store store, Receiver receiver) {
        this.store = store;
        this.receiver = receiver;
    }

    public void setCrashHook(CrashHook hook) {
        this.crashHook = hook == null ? new CrashHook() : hook;
    }

    public Store store() { return store; }
    public long now() { return store.nowMs; }

    // ---- endpoints ----

    public Model.Endpoint upsertEndpoint(Map<String, Object> body) {
        String id = Json.str(body, "id");
        if (id == null || id.isBlank()) throw new ApiError(400, "endpoint id is required");
        Model.Endpoint existing = store.endpoints.get(id);
        Model.Endpoint d = existing != null ? existing : Model.Endpoint.defaults(id);
        boolean paused = Json.bool(body, "paused", d.paused());
        Model.Endpoint ep = new Model.Endpoint(
                id,
                Json.str(body, "secret", d.secret()),
                Json.lng(body, "maxAttempts", d.maxAttempts()),
                Json.lng(body, "baseBackoffMs", d.baseBackoffMs()),
                Json.lng(body, "maxBackoffMs", d.maxBackoffMs()),
                Json.lng(body, "jitterSeed", d.jitterSeed()),
                Json.lng(body, "timeoutMs", d.timeoutMs()),
                Json.str(body, "behavior", d.behavior()),
                paused);
        if (ep.maxAttempts() < 1) throw new ApiError(400, "maxAttempts must be >= 1");
        if (ep.baseBackoffMs() < 0 || ep.maxBackoffMs() < ep.baseBackoffMs()) {
            throw new ApiError(400, "invalid backoff configuration");
        }
        store.putEndpoint(ep);
        return ep;
    }

    public void setPaused(String endpointId, boolean paused) {
        Model.Endpoint ep = store.endpoints.get(endpointId);
        if (ep == null) throw new ApiError(404, "no such endpoint: " + endpointId);
        store.putEndpoint(new Model.Endpoint(ep.id(), ep.secret(), ep.maxAttempts(), ep.baseBackoffMs(),
                ep.maxBackoffMs(), ep.jitterSeed(), ep.timeoutMs(), ep.behavior(), paused));
    }

    // ---- events ----

    public Model.Chain sendEvent(Map<String, Object> body) {
        String eventId = Json.str(body, "eventId");
        if (eventId == null || eventId.isBlank()) throw new ApiError(400, "eventId is required");
        String endpointId = Json.str(body, "endpointId");
        if (endpointId == null) throw new ApiError(400, "endpointId is required");
        Model.Endpoint ep = store.endpoints.get(endpointId);
        if (ep == null) throw new ApiError(404, "no such endpoint: " + endpointId);
        if (store.eventChains.containsKey(eventId)) {
            throw new ApiError(409, "duplicate eventId: " + eventId);
        }
        String payload = Json.str(body, "payload", "");
        String contentType = Json.str(body, "contentType", "application/json");

        Model.Event ev = new Model.Event(eventId, endpointId, payload, contentType, store.nowMs);
        store.putEvent(ev);
        String chainId = store.newChainId();
        Model.Chain chain = new Model.Chain(chainId, eventId, endpointId, Model.ChainStatus.ACTIVE,
                store.nowMs, chainId, null, null, null);
        store.putChain(chain);
        scheduleAttempt(chain, ev, 1, 0, "INITIAL", "新事件，首次投递");
        return chain;
    }

    private Model.Attempt scheduleAttempt(Model.Chain chain, Model.Event ev, int attemptNo, long delayMs,
                                          String reasonCode, String reason) {
        long scheduledAt = store.nowMs + Math.max(0, delayMs);
        String attemptId = store.newAttemptId();
        byte[] bodyBytes = ev.payload().getBytes(StandardCharsets.UTF_8);
        String bodySha = Signer.sha256Hex(bodyBytes);
        Map<String, String> headerValues = new LinkedHashMap<>();
        headerValues.put(Signer.HDR_EVENT, ev.id());
        headerValues.put(Signer.HDR_ATTEMPT, attemptId);
        headerValues.put(Signer.HDR_CHAIN, chain.id());
        headerValues.put(Signer.HDR_TIMESTAMP, String.valueOf(scheduledAt));
        headerValues.put(Signer.HDR_DIGEST, bodySha);
        headerValues.put(Signer.HDR_CONTENT_TYPE, ev.contentType());
        String signatureInput = Signer.signatureInput(attemptId, scheduledAt, headerValues,
                Signer.defaultSignedHeaders());
        String signature = Signer.hmacBase64(store.endpoints.get(ev.endpointId()).secret(), signatureInput);
        Model.Attempt attempt = new Model.Attempt(attemptId, chain.id(), ev.id(), ev.endpointId(),
                attemptNo, scheduledAt, reasonCode, reason, ev.payload(), ev.contentType(),
                null, signatureInput, signature, Signer.defaultSignedHeaders(), bodySha);
        store.putAttempt(attempt);
        crashHook.onAttemptScheduled(attemptId);
        return attempt;
    }

    // ---- replay of dead/failed chains ----

    public Model.Chain replayChain(String chainId) {
        Model.Chain original = store.chains.get(chainId);
        if (original == null) throw new ApiError(404, "no such chain: " + chainId);
        if (original.status() == Model.ChainStatus.ACTIVE) {
            throw new ApiError(409, "chain is still active, cannot replay");
        }
        Model.Event ev = store.events.get(original.eventId());
        Model.Chain root = original.originChainId() != null ? store.chains.get(original.originChainId()) : original;
        String rootId = root != null ? root.id() : original.id();

        int suffix = 1;
        String newEventId;
        do {
            newEventId = ev.id() + "#replay" + suffix;
            suffix++;
        } while (store.eventChains.containsKey(newEventId));

        Model.Event replayEvent = new Model.Event(newEventId, ev.endpointId(), ev.payload(),
                ev.contentType(), store.nowMs);
        store.putEvent(replayEvent);
        String newChainId = store.newChainId();
        Model.Chain replay = new Model.Chain(newChainId, newEventId, ev.endpointId(), Model.ChainStatus.ACTIVE,
                store.nowMs, rootId, chainId, null, null);
        store.putChain(replay);
        scheduleAttempt(replay, replayEvent, 1, 0, "REPLAY",
                "重放死信链 " + chainId + "（原始链不可修改）");
        return replay;
    }

    // ---- virtual clock ----

    /** Returns pending (scheduled, no result yet) attempts ordered by schedule time. */
    private List<Model.Attempt> pendingSorted() {
        List<Model.Attempt> out = new ArrayList<>();
        for (String id : store.pendingAttemptIds) {
            Model.Attempt a = store.attempts.get(id);
            if (a != null) out.add(a);
        }
        out.sort(Comparator.comparingLong(Model.Attempt::scheduledAt).thenComparing(Model.Attempt::id));
        return out;
    }

    /** Advances the clock to the next scheduled attempt and fires it. */
    public Map<String, Object> stepNext() {
        List<Model.Attempt> pending = pendingSorted();
        if (pending.isEmpty()) {
            Map<String, Object> out = Json.obj();
            out.put("advancedTo", store.nowMs);
            out.put("fired", List.of());
            return out;
        }
        long target = pending.get(0).scheduledAt();
        if (target > store.nowMs) store.setClock(target);
        fireDue();
        Map<String, Object> out = Json.obj();
        out.put("advancedTo", store.nowMs);
        out.put("fired", firedSummary());
        return out;
    }

    /** Advances the clock by a fixed number of virtual milliseconds. */
    public Map<String, Object> advance(long deltaMs) {
        long target = store.nowMs + Math.max(0, deltaMs);
        store.setClock(target);
        fireDue();
        Map<String, Object> out = Json.obj();
        out.put("advancedTo", store.nowMs);
        out.put("fired", firedSummary());
        return out;
    }

    private List<String> firedNow = new ArrayList<>();

    private List<Object> firedSummary() {
        List<Object> out = new ArrayList<>();
        for (String id : firedNow) out.add(id);
        return out;
    }

    /**
     * Fires all pending attempts whose schedule time has arrived, in original
     * schedule order. Attempts of paused endpoints stay parked at their
     * original schedule time and are not rescheduled.
     */
    private void fireDue() {
        firedNow = new ArrayList<>();
        while (true) {
            List<Model.Attempt> pending = pendingSorted();
            Model.Attempt next = null;
            for (Model.Attempt a : pending) {
                if (a.scheduledAt() <= store.nowMs) { next = a; break; }
            }
            if (next == null) return;
            Model.Endpoint ep = store.endpoints.get(next.endpointId());
            if (ep.paused()) {
                // Pause blocks new attempts but never cancels or reschedules.
                // Skip past this parked attempt and look for other due work.
                long later = Long.MAX_VALUE;
                boolean anyDue = false;
                for (Model.Attempt a : pending) {
                    if (a.id().equals(next.id())) continue;
                    Model.Endpoint other = store.endpoints.get(a.endpointId());
                    if (other != null && other.paused()) continue;
                    if (a.scheduledAt() <= store.nowMs) { anyDue = true; break; }
                    later = Math.min(later, a.scheduledAt());
                }
                if (anyDue) continue;
                return;
            }
            fireAttempt(next, ep);
        }
    }

    private void fireAttempt(Model.Attempt attempt, Model.Endpoint ep) {
        // Record send metadata on the attempt itself (sentAt is the virtual
        // time the bytes go out; signatureInput/signature were frozen at schedule time).
        store.putAttempt(new Model.Attempt(attempt.id(), attempt.chainId(), attempt.eventId(),
                attempt.endpointId(), attempt.attemptNo(), attempt.scheduledAt(),
                attempt.reasonCode(), attempt.reason(), attempt.payload(), attempt.contentType(),
                store.nowMs, attempt.signatureInput(), attempt.signature(),
                attempt.signedHeaders(), attempt.bodySha256()));
        firedNow.add(attempt.id());
        crashHook.beforeSend(attempt.id());

        Receiver.Outcome outcome = receiver.deliver(ep, attempt, store.nowMs);
        crashHook.beforeResultPersisted(attempt.id());
        recordOutcome(attempt, ep, outcome);
    }

    private void recordOutcome(Model.Attempt attempt, Model.Endpoint ep, Receiver.Outcome outcome) {
        Model.Chain chain = store.chains.get(attempt.chainId());
        Model.Event ev = store.events.get(attempt.eventId());

        String outcomeName;
        Integer status = null;
        String body = null;
        String responseHeaders = null;
        String failureKind = null;

        if (outcome instanceof Receiver.Outcome.Responded r) {
            outcomeName = "RESPONDED";
            status = r.status();
            body = r.body();
            responseHeaders = r.headers().isEmpty() ? null : Json.of(r.headers());
        } else if (outcome instanceof Receiver.Outcome.Timeout t) {
            outcomeName = "TIMEOUT";
            failureKind = "TIMEOUT after " + t.afterMs() + "ms";
        } else if (outcome instanceof Receiver.Outcome.Disconnected) {
            outcomeName = "DISCONNECTED";
            failureKind = "CONNECTION_RESET";
        } else {
            outcomeName = "LOST";
            failureKind = "RESPONSE_LOST (receiver marked event processed)";
        }

        boolean success = status != null && status >= 200 && status < 300;
        if (success) {
            store.putResult(new Model.AttemptResult(attempt.id(), attempt.chainId(), attempt.endpointId(),
                    store.nowMs, outcomeName, status, body, responseHeaders, failureKind,
                    null, "2xx 成功，链结束", null, null));
            store.putChainStatus(chain.id(), Model.ChainStatus.SUCCEEDED, store.nowMs, "投递成功");
            return;
        }

        boolean retryable = isRetryable(status, outcomeName);
        String retryAfterHeader = null;
        if (status != null && status == 429 && outcome instanceof Receiver.Outcome.Responded rr) {
            retryAfterHeader = rr.headers().get("retry-after");
        }

        if (!retryable) {
            String reason = "状态码 " + status + " 不可重试，直接失败";
            store.putResult(new Model.AttemptResult(attempt.id(), attempt.chainId(), attempt.endpointId(),
                    store.nowMs, outcomeName, status, body, responseHeaders, failureKind,
                    null, reason, null, null));
            store.putChainStatus(chain.id(), Model.ChainStatus.FAILED, store.nowMs, reason);
            return;
        }

        int nextNo = attempt.attemptNo() + 1;
        long delay;
        String reasonCode;
        String reason;
        if (status != null && status == 429 && retryAfterHeader != null) {
            Long parsed = Backoff.parseRetryAfter(retryAfterHeader, store.nowMs);
            long requested = parsed == null ? 0 : parsed;
            delay = requested;
            reasonCode = "RETRY_AFTER";
            reason = "429 Retry-After=" + retryAfterHeader;
            if (delay > ep.maxBackoffMs()) {
                deadLetter(attempt, chain, outcomeName, status, body, responseHeaders, failureKind,
                        "Retry-After 延迟 " + delay + "ms 超过端点上限 " + ep.maxBackoffMs() + "ms",
                        "RETRY_AFTER_CAP");
                return;
            }
        } else if (status != null && status == 429) {
            delay = Backoff.delayMs(ep.baseBackoffMs(), ep.maxBackoffMs(), ep.jitterSeed(), attempt.attemptNo());
            reasonCode = "BACKOFF_429";
            reason = "429 无有效 Retry-After，使用指数退避";
        } else {
            delay = Backoff.delayMs(ep.baseBackoffMs(), ep.maxBackoffMs(), ep.jitterSeed(), attempt.attemptNo());
            reasonCode = "BACKOFF";
            reason = "可重试失败" + (status != null ? "（" + status + "）" : "")
                    + "，指数退避 + 确定性抖动(seed=" + ep.jitterSeed() + ")";
        }

        if (nextNo > ep.maxAttempts()) {
            deadLetter(attempt, chain, outcomeName, status, body, responseHeaders, failureKind,
                    "已达最大尝试次数 " + ep.maxAttempts(), "MAX_ATTEMPTS");
            return;
        }

        Model.Attempt next = scheduleAttempt(chain, ev, nextNo, delay, reasonCode, reason);
        store.putResult(new Model.AttemptResult(attempt.id(), attempt.chainId(), attempt.endpointId(),
                store.nowMs, outcomeName, status, body, responseHeaders, failureKind,
                reasonCode, reason, numericPart(next.id()), delay));
    }

    private static Long numericPart(String attemptId) {
        try {
            return Long.parseLong(attemptId.substring("att-".length()));
        } catch (Exception e) {
            return null;
        }
    }

    private void deadLetter(Model.Attempt attempt, Model.Chain chain, String outcomeName, Integer status,
                            String body, String responseHeaders, String failureKind,
                            String endReason, String code) {
        store.putResult(new Model.AttemptResult(attempt.id(), attempt.chainId(), attempt.endpointId(),
                store.nowMs, outcomeName, status, body, responseHeaders, failureKind,
                code, endReason + "，进入死信", null, null));
        store.putChainStatus(chain.id(), Model.ChainStatus.DEAD, store.nowMs, endReason);
    }

    private static boolean isRetryable(Integer status, String outcomeName) {
        if (outcomeName.equals("TIMEOUT") || outcomeName.equals("DISCONNECTED") || outcomeName.equals("LOST")) {
            return true;
        }
        if (status == null) return true;
        if (status == 408 || status == 429) return true;
        return status >= 500 && status <= 599;
    }

    /**
     * One-time recovery after restart: every scheduled attempt whose result
     * never reached durable storage is treated as outcome-unknown and gets a
     * fresh retry with a new attempt id and standard backoff. The original
     * attempt is never mutated. Idempotent via the pending set.
     */
    public void recoverInDoubtAttempts() {
        List<Model.Attempt> inDoubt = new ArrayList<>();
        for (String id : new ArrayList<>(store.pendingAttemptIds)) {
            Model.Attempt a = store.attempts.get(id);
            if (a == null) continue;
            Model.Chain chain = store.chains.get(a.chainId());
            if (chain != null && chain.status() == Model.ChainStatus.ACTIVE) inDoubt.add(a);
        }
        inDoubt.sort(Comparator.comparingLong(Model.Attempt::scheduledAt).thenComparing(Model.Attempt::id));
        for (Model.Attempt a : inDoubt) {
            Model.Endpoint ep = store.endpoints.get(a.endpointId());
            Model.Chain chain = store.chains.get(a.chainId());
            Model.Event ev = store.events.get(a.eventId());
            long delay = ep == null ? 0
                    : Backoff.delayMs(ep.baseBackoffMs(), ep.maxBackoffMs(), ep.jitterSeed(), a.attemptNo());
            if (ep != null && a.attemptNo() + 1 > ep.maxAttempts()) {
                deadLetter(a, chain, "RECOVERED", null, null, null,
                        "重启时该尝试已发出但结果未知", "崩溃后恢复：超过最大尝试次数", "RECOVERY_MAX_ATTEMPTS");
                continue;
            }
            int nextNo = a.attemptNo() + 1;
            Model.Attempt next = scheduleAttempt(chain, ev, nextNo, delay, "RECOVERED",
                    "重启恢复：尝试 " + a.id() + " 结果未知，重新投递（旧尝试不可篡改）");
            store.putResult(new Model.AttemptResult(a.id(), a.chainId(), a.endpointId(),
                    store.nowMs, "RECOVERED", null, null, null,
                    "IN_DOUBT_AFTER_RESTART", "RECOVERED",
                    "结果未知，已安排新尝试", numericPart(next.id()), delay));
        }
    }
}
