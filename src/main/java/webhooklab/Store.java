package webhooklab;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Durable state store: an in-memory model rebuilt by replaying the journal,
 * with every mutation appended (and fsync'd) before it is applied in memory.
 */
public final class Store implements AutoCloseable {

    private final Journal journal;

    public long nowMs;
    public long nextChainSeq = 1;
    public long nextAttemptSeq = 1;
    public long nextReceiverSeq = 1;

    public final Map<String, Model.Endpoint> endpoints = new LinkedHashMap<>();
    public final Map<String, Model.Event> events = new LinkedHashMap<>();
    public final Map<String, Model.Chain> chains = new LinkedHashMap<>();
    public final Map<String, Model.Attempt> attempts = new LinkedHashMap<>();
    public final Map<String, Model.AttemptResult> results = new LinkedHashMap<>();
    public final List<Model.ReceiverEntry> receiverLog = new ArrayList<>();
    /** eventId -> chainId of the chain that first claimed this event id. */
    public final Map<String, String> eventChains = new LinkedHashMap<>();
    /** endpointId -> set of eventIds the receiver has durably processed. */
    public final Map<String, TreeSet<String>> processed = new LinkedHashMap<>();
    /** attempts recorded as scheduled but with no result yet (in-doubt after crash). */
    public final List<String> pendingAttemptIds = new ArrayList<>();

    private Store(Journal journal) {
        this.journal = journal;
    }

    public static Store open(Path dataDir) throws IOException {
        Journal j = Journal.open(dataDir.resolve("journal.log"));
        Store s = new Store(j);
        for (Map<String, Object> rec : Journal.readMaps(dataDir.resolve("journal.log"))) {
            s.apply(Json.str(rec, "type"), Json.obj(rec, "data"));
        }
        return s;
    }

    public void close() {
        journal.close();
    }

    // ---- durable mutation helpers (append first, then apply) ----

    public void setClock(long now) {
        Map<String, Object> d = Json.obj();
        d.put("now", now);
        journal.append("clock", d);
        apply("clock", d);
    }

    public void putEndpoint(Model.Endpoint ep) {
        journal.append("endpoint", ep.toJson());
        apply("endpoint", ep.toJson());
    }

    public void putEvent(Model.Event ev) {
        journal.append("event", ev.toJson());
        apply("event", ev.toJson());
    }

    public void putChain(Model.Chain c) {
        journal.append("chain", c.toJson());
        apply("chain", c.toJson());
    }

    public void putChainStatus(String chainId, Model.ChainStatus status, long endedAt, String endReason) {
        Map<String, Object> d = Json.obj();
        d.put("chainId", chainId);
        d.put("status", status.name());
        d.put("endedAt", endedAt);
        d.put("endReason", endReason);
        journal.append("chain_status", d);
        apply("chain_status", d);
    }

    public void putAttempt(Model.Attempt a) {
        journal.append("attempt", a.toJson());
        apply("attempt", a.toJson());
    }

    public void putResult(Model.AttemptResult r) {
        journal.append("result", r.toJson());
        apply("result", r.toJson());
    }

    public void putReceiverEntry(Model.ReceiverEntry e) {
        journal.append("receiver", e.toJson());
        apply("receiver", e.toJson());
    }

    public void markProcessed(String endpointId, String eventId) {
        Map<String, Object> d = Json.obj();
        d.put("endpointId", endpointId);
        d.put("eventId", eventId);
        journal.append("processed", d);
        apply("processed", d);
    }

    // ---- journal replay ----

    private void apply(String type, Map<String, Object> d) {
        switch (type) {
            case "clock" -> nowMs = Json.lng(d, "now", nowMs);
            case "endpoint" -> {
                Model.Endpoint ep = Model.Endpoint.fromJson(d);
                endpoints.put(ep.id(), ep);
            }
            case "event" -> {
                Model.Event ev = Model.Event.fromJson(d);
                events.put(ev.id(), ev);
            }
            case "chain" -> {
                Model.Chain c = Model.Chain.fromJson(d);
                chains.put(c.id(), c);
                eventChains.putIfAbsent(c.eventId(), c.id());
                nextChainSeq = Math.max(nextChainSeq, numericSuffix(c.id(), "chain-") + 1);
            }
            case "chain_status" -> {
                String chainId = Json.str(d, "chainId");
                Model.Chain c = chains.get(chainId);
                if (c != null) {
                    chains.put(chainId, new Model.Chain(c.id(), c.eventId(), c.endpointId(),
                            Model.ChainStatus.valueOf(Json.str(d, "status")),
                            c.createdAt(), c.originChainId(), c.replayOfChainId(),
                            Json.lng(d, "endedAt", nowMs), Json.str(d, "endReason")));
                }
            }
            case "attempt" -> {
                Model.Attempt a = Model.Attempt.fromJson(d);
                attempts.put(a.id(), a);
                if (!pendingAttemptIds.contains(a.id())) pendingAttemptIds.add(a.id());
                nextAttemptSeq = Math.max(nextAttemptSeq, numericSuffix(a.id(), "att-") + 1);
            }
            case "result" -> {
                Model.AttemptResult r = Model.AttemptResult.fromJson(d);
                results.put(r.attemptId(), r);
                pendingAttemptIds.remove(r.attemptId());
            }
            case "receiver" -> {
                Model.ReceiverEntry e = Model.ReceiverEntry.fromJson(d);
                receiverLog.add(e);
                nextReceiverSeq = Math.max(nextReceiverSeq, e.seq() + 1);
            }
            case "processed" -> {
                String ep = Json.str(d, "endpointId");
                processed.computeIfAbsent(ep, k -> new TreeSet<>()).add(Json.str(d, "eventId"));
            }
            default -> { /* forward-compatible: ignore unknown record types */ }
        }
    }

    private static long numericSuffix(String id, String prefix) {
        if (id == null || !id.startsWith(prefix)) return 0;
        try {
            return Long.parseLong(id.substring(prefix.length()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public String newChainId() { return "chain-" + (nextChainSeq++); }

    public String newAttemptId() { return "att-" + (nextAttemptSeq++); }

    public boolean isProcessed(String endpointId, String eventId) {
        TreeSet<String> set = processed.get(endpointId);
        return set != null && set.contains(eventId);
    }

    public List<Model.Attempt> attemptsOf(String chainId) {
        List<Model.Attempt> out = new ArrayList<>();
        for (Model.Attempt a : attempts.values()) if (a.chainId().equals(chainId)) out.add(a);
        return out;
    }
}
