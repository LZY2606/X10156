package webhooklab;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** In-process test harness around a fresh data directory. */
final class LabHarness implements AutoCloseable {

    final Path dir;
    Store store;
    Receiver receiver;
    Engine engine;

    LabHarness(Path dir) {
        this.dir = dir;
        try {
            open();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    void open() throws IOException {
        store = Store.open(dir);
        receiver = new Receiver(store);
        engine = new Engine(store, receiver);
        engine.recoverInDoubtAttempts();
    }

    @Override public void close() {
        store.close();
    }

    Model.Endpoint endpoint(String id, String behavior, long base, long cap, long seed, long maxAttempts) {
        Map<String, Object> ep = Json.obj();
        ep.put("id", id);
        ep.put("secret", "secret-" + id);
        ep.put("maxAttempts", maxAttempts);
        ep.put("baseBackoffMs", base);
        ep.put("maxBackoffMs", cap);
        ep.put("jitterSeed", seed);
        ep.put("timeoutMs", 5000);
        ep.put("behavior", behavior);
        ep.put("paused", false);
        return engine.upsertEndpoint(ep);
    }

    String send(String eventId, String endpointId, String payload) {
        Map<String, Object> ev = Json.obj();
        ev.put("eventId", eventId);
        ev.put("endpointId", endpointId);
        ev.put("payload", payload);
        ev.put("contentType", "application/json");
        return engine.sendEvent(ev).id();
    }

    /** Steps the clock until every non-paused chain settles or the guard trips. */
    void drain() {
        int guard = 0;
        while (guard++ < 1000) {
            boolean work = false;
            for (String id : store.pendingAttemptIds) {
                Model.Attempt a = store.attempts.get(id);
                Model.Endpoint ep = a == null ? null : store.endpoints.get(a.endpointId());
                if (ep != null && !ep.paused()) { work = true; break; }
            }
            if (!work) return;
            engine.stepNext();
        }
        throw new IllegalStateException("drain did not settle");
    }

    Model.Chain chain(String id) { return store.chains.get(id); }
    List<Model.Attempt> attempts(String chainId) { return store.attemptsOf(chainId); }
    Model.AttemptResult resultOf(String attemptId) { return store.results.get(attemptId); }

    /** Simulates a hard crash mid-fire (before the response is persisted). */
    static final class CrashBeforeResult extends RuntimeException {}

    void crashBeforeResultOnAttempt(String attemptId) {
        engine.setCrashHook(new Engine.CrashHook() {
            @Override public void beforeResultPersisted(String id) {
                if (id.equals(attemptId)) throw new CrashBeforeResult();
            }
        });
    }
}
