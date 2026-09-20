package lab;

import lab.Model.Attempt;
import lab.Model.Chain;
import lab.Model.Endpoint;
import lab.Model.EventRec;
import lab.Model.LabState;
import lab.Model.ReceiverMode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Delivery lab facade: endpoints, events, virtual clock, scheduling, dispatch,
 * dead letters and replay. All state is persisted after every mutation so a
 * crash at any point recovers deterministically without losing events.
 */
public final class Lab {
    public static final long EPOCH_START = 1_700_000_000_000L;

    /** Crash-injection points for tests: "afterSchedulePersist", "beforeResponsePersist". */
    public static final class CrashException extends RuntimeException {
        public CrashException(String point) { super("simulated crash at " + point); }
    }

    private final StateStore store;
    private final long seed;
    final LabState state;
    final Receiver receiver;
    private Consumer<String> crashHook = point -> {};

    public Lab(Path dir, long seed) {
        this.seed = seed;
        this.store = new StateStore(dir.resolve("lab-state.json"));
        this.receiver = new Receiver(new StateStore(dir.resolve("receiver-state.json")));
        if (store.exists()) {
            state = LabState.fromJson(Json.readObject(store.load()));
        } else {
            state = new LabState();
            state.now = EPOCH_START;
            persist();
        }
    }

    public void setCrashHook(Consumer<String> hook) {
        this.crashHook = hook == null ? p -> {} : hook;
    }

    public Receiver receiver() {
        return receiver;
    }

    public long now() {
        return state.now;
    }

    // ---- endpoints ----

    public synchronized Endpoint createEndpoint(String name, String secret, int maxAttempts,
                                                ReceiverMode mode, String retryAfter) {
        Endpoint e = new Endpoint();
        e.id = "ep-" + (++state.seq);
        e.name = name;
        e.secret = secret;
        e.maxAttempts = Math.max(1, maxAttempts);
        e.mode = mode;
        e.retryAfter = retryAfter == null ? "1" : retryAfter;
        state.endpoints.put(e.id, e);
        persist();
        return e;
    }

    public synchronized Endpoint updateEndpoint(String id, String name, String secret, Integer maxAttempts,
                                                ReceiverMode mode, String retryAfter) {
        Endpoint e = endpoint(id);
        if (name != null) e.name = name;
        if (secret != null) e.secret = secret;
        if (maxAttempts != null) e.maxAttempts = Math.max(1, maxAttempts);
        if (mode != null) e.mode = mode;
        if (retryAfter != null) e.retryAfter = retryAfter;
        persist();
        return e;
    }

    public synchronized void setPaused(String id, boolean paused) {
        endpoint(id).paused = paused;
        persist();
    }

    public Endpoint endpoint(String id) {
        Endpoint e = state.endpoints.get(id);
        if (e == null) throw new IllegalArgumentException("unknown endpoint " + id);
        return e;
    }

    // ---- events ----

    public static final class SendResult {
        public boolean duplicate;
        public String chainId;
    }

    /** Enqueues an event; duplicate (endpointId, eventId) is a no-op returning the original chain. */
    public synchronized SendResult sendEvent(String endpointId, String eventId, String payload) {
        Endpoint ep = endpoint(endpointId);
        SendResult result = new SendResult();
        String key = endpointId + "|" + eventId;
        if (state.events.containsKey(key)) {
            result.duplicate = true;
            result.chainId = findChainFor(endpointId, eventId);
            return result;
        }
        EventRec ev = new EventRec();
        ev.eventId = eventId;
        ev.endpointId = endpointId;
        ev.payload = payload;
        ev.createdAt = state.now;
        state.events.put(key, ev);

        Chain chain = newChain(ev, null);
        scheduleAttempt(chain, ev, 1, state.now);
        persist();
        crashHook.accept("afterSchedulePersist");
        result.chainId = chain.chainId;
        processDue();
        return result;
    }

    private String findChainFor(String endpointId, String eventId) {
        for (Chain c : state.chains.values()) {
            if (c.endpointId.equals(endpointId) && c.eventId.equals(eventId)) return c.chainId;
        }
        return null;
    }

    // ---- dead letters & replay ----

    /** Replays a dead chain: creates a brand-new delivery chain pointing back to the old one. */
    public synchronized Chain replay(String chainId) {
        Chain old = chain(chainId);
        if (!"DEAD".equals(old.status)) throw new IllegalArgumentException("chain " + chainId + " is not dead");
        EventRec ev = state.events.get(old.endpointId + "|" + old.eventId);
        Chain fresh = newChain(ev, old.chainId);
        scheduleAttempt(fresh, ev, 1, state.now);
        persist();
        crashHook.accept("afterSchedulePersist");
        processDue();
        return fresh;
    }

    public Chain chain(String id) {
        Chain c = state.chains.get(id);
        if (c == null) throw new IllegalArgumentException("unknown chain " + id);
        return c;
    }

    // ---- virtual clock ----

    /** Advances the virtual clock and processes everything that becomes due. */
    public synchronized void stepClock(long seconds) {
        state.now += seconds * 1_000L;
        persist();
        processDue();
    }

    /** Advances the virtual clock to the next scheduled attempt and processes what is due. */
    public synchronized void stepToNext() {
        Long next = null;
        for (Attempt a : state.attempts.values()) {
            if (isPending(a) && (next == null || a.scheduledAt < next)) next = a.scheduledAt;
        }
        if (next != null && next > state.now) {
            state.now = next;
            persist();
        }
        processDue();
    }

    // ---- signature verification ----

    public synchronized Map<String, Object> verifyAttempt(String attemptId) {
        Attempt a = attempt(attemptId);
        Endpoint ep = endpoint(a.endpointId);
        String expected = Signer.sign(ep.secret, a.signatureInput);
        Map<String, Object> out = Json.obj();
        out.put("attemptId", attemptId);
        out.put("valid", expected.equals(a.signature));
        out.put("expected", expected);
        out.put("actual", a.signature);
        out.put("signatureInput", a.signatureInput);
        return out;
    }

    public Attempt attempt(String id) {
        Attempt a = state.attempts.get(id);
        if (a == null) throw new IllegalArgumentException("unknown attempt " + id);
        return a;
    }

    // ---- scheduling & dispatch ----

    private Chain newChain(EventRec ev, String replayedFrom) {
        Chain c = new Chain();
        c.chainId = "ch-" + (++state.seq);
        c.eventId = ev.eventId;
        c.endpointId = ev.endpointId;
        c.replayedFrom = replayedFrom;
        c.status = "ACTIVE";
        c.createdAt = state.now;
        state.chains.put(c.chainId, c);
        return c;
    }

    private Attempt scheduleAttempt(Chain chain, EventRec ev, int number, long scheduledAt) {
        Attempt a = new Attempt();
        a.attemptId = "at-" + (++state.seq);
        a.chainId = chain.chainId;
        a.endpointId = ev.endpointId;
        a.eventId = ev.eventId;
        a.number = number;
        a.scheduledAt = scheduledAt;
        a.timestamp = scheduledAt;
        state.attempts.put(a.attemptId, a);
        return a;
    }

    private boolean isPending(Attempt a) {
        return "SCHEDULED".equals(a.status) && "ACTIVE".equals(state.chains.get(a.chainId).status);
    }

    /** Processes every due attempt in original schedule order; paused endpoints only block new attempts. */
    private void processDue() {
        while (true) {
            Attempt next = null;
            for (Attempt a : state.attempts.values()) {
                if (!isPending(a) || a.scheduledAt > state.now) continue;
                if (endpoint(a.endpointId).paused) continue;
                if (next == null || a.scheduledAt < next.scheduledAt
                        || (a.scheduledAt == next.scheduledAt && seqOf(a) < seqOf(next))) {
                    next = a;
                }
            }
            if (next == null) return;
            dispatch(next);
        }
    }

    private long seqOf(Attempt a) {
        return Long.parseLong(a.attemptId.substring(3));
    }

    private void dispatch(Attempt a) {
        Endpoint ep = endpoint(a.endpointId);
        EventRec ev = state.events.get(a.endpointId + "|" + a.eventId);
        Chain chain = chain(a.chainId);

        byte[] sentBytes = Signer.canonicalRequestBytes(a.eventId, a.attemptId, a.timestamp, ev.payload);
        a.canonicalRequest = new String(sentBytes, StandardCharsets.UTF_8);
        a.signatureInput = Signer.signatureInput(a.timestamp, sentBytes);
        a.signature = Signer.sign(ep.secret, a.signatureInput);

        Receiver.Response response = null;
        String error = null;
        try {
            response = receiver.deliver(ep, ev, a);
        } catch (Receiver.ReceiverException ex) {
            error = ex.getMessage();
        }

        crashHook.accept("beforeResponsePersist");

        a.completedAt = state.now;
        if (response != null) {
            a.responseStatus = response.status;
            a.responseHeaders = response.headers;
            a.responseBody = response.body;
        }
        a.errorKind = error;

        if (response != null && response.status >= 200 && response.status < 300) {
            a.status = "SUCCESS";
            a.nextReason = "NONE_SUCCESS";
            chain.status = "SUCCEEDED";
        } else if (a.number >= ep.maxAttempts) {
            a.status = "FAILED";
            a.nextReason = "NONE_DEAD";
            chain.status = "DEAD";
        } else {
            a.status = "FAILED";
            long delay;
            if (response != null && response.status == 429 && response.headers != null
                    && response.headers.startsWith("Retry-After: ")) {
                delay = RetryAfter.delayMillis(response.headers.substring("Retry-After: ".length()), state.now);
                a.nextReason = "RETRY_AFTER";
            } else {
                delay = Backoff.delayMillis(seed, a.eventId, a.number);
                a.nextReason = "BACKOFF";
            }
            scheduleAttempt(chain, ev, a.number + 1, state.now + delay);
        }
        persist();
    }

    private void persist() {
        store.save(Json.write(state.toJson()));
    }

    // ---- views for the HTTP API ----

    public synchronized Map<String, Object> snapshot() {
        Map<String, Object> m = Json.obj();
        m.put("now", state.now);
        List<Object> eps = Json.arr();
        state.endpoints.values().forEach(e -> eps.add(e.toJson()));
        m.put("endpoints", eps);
        List<Object> evs = Json.arr();
        state.events.values().forEach(e -> evs.add(e.toJson()));
        m.put("events", evs);
        List<Object> chains = Json.arr();
        state.chains.values().forEach(c -> chains.add(c.toJson()));
        m.put("chains", chains);
        List<Object> attempts = Json.arr();
        state.attempts.values().forEach(a -> attempts.add(a.toJson()));
        m.put("attempts", attempts);
        List<Object> dead = Json.arr();
        state.chains.values().stream().filter(c -> "DEAD".equals(c.status)).forEach(c -> dead.add(c.chainId));
        m.put("deadLetters", dead);
        return m;
    }

    public synchronized List<Attempt> attemptsOf(String chainId) {
        List<Attempt> out = new ArrayList<>();
        for (Attempt a : state.attempts.values()) if (a.chainId.equals(chainId)) out.add(a);
        return out;
    }
}
