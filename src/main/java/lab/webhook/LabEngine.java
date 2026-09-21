package lab.webhook;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Core delivery engine: virtual clock, deterministic scheduler, signing,
 * in-process delivery, retry/backoff, dead-letter, replay and crash recovery.
 *
 * Everything is rebuilt from the append-only {@link LogStore}, so exiting after
 * a schedule write, before a send, or before the response is persisted yields
 * a deterministic, loss-free result on restart.
 */
public final class LabEngine implements AutoCloseable {

    public static final long EPOCH_START = 1_700_000_000_000L;

    // crash injection points
    public static final String CRASH_AFTER_SCHEDULE = "after_schedule";
    public static final String CRASH_BEFORE_SEND = "before_send";
    public static final String CRASH_AFTER_SEND = "after_send";

    private final Path dataDir;
    private final LogStore log;
    private final Receiver receiver;
    private final IdGenerator ids;

    private final Map<String, Models.Endpoint> endpoints = new LinkedHashMap<>();
    private final Map<String, Models.Event> events = new LinkedHashMap<>();       // chainId -> event
    private final Map<String, String> eventIdToChain = new LinkedHashMap<>();
    private final Map<String, List<Models.Attempt>> attempts = new LinkedHashMap<>(); // chainId -> attempts
    private final Set<String> processed = ConcurrentHashMap.newKeySet();
    private final TreeSet<Scheduled> schedule = new TreeSet<>();

    private long clock = EPOCH_START;
    private long jitterSeed = 42L;
    private long seqCounter;
    private String nextCrash;
    private boolean realExitOnCrash = false;

    private record Scheduled(long at, String chainId, String attemptId) implements Comparable<Scheduled> {
        @Override public int compareTo(Scheduled o) {
            int c = Long.compare(at, o.at);
            if (c != 0) return c;
            c = chainId.compareTo(o.chainId);
            if (c != 0) return c;
            return attemptId.compareTo(o.attemptId);
        }
    }

    public LabEngine(Path dataDir) {
        this(dataDir, new IdGenerator());
    }

    public LabEngine(Path dataDir, IdGenerator idGen) {
        this.dataDir = dataDir;
        this.ids = idGen;
        this.log = new LogStore(dataDir.resolve("lab.log"));
        this.receiver = new Receiver(this::commitProcessed);
        recover();
    }

    // ------------------------------------------------------------------ config

    public synchronized long getClock() { return clock; }
    public synchronized long getJitterSeed() { return jitterSeed; }
    public synchronized void setJitterSeed(long seed) { this.jitterSeed = seed; }
    public Path dataDir() { return dataDir; }

    public void armCrash(String point) { this.nextCrash = point; }
    public void clearCrash() { this.nextCrash = null; }
    public void setRealExitOnCrash(boolean v) { this.realExitOnCrash = v; }
    public String crashPoint() { return nextCrash; }

    private void crashIf(String point) {
        if (Objects.equals(nextCrash, point)) {
            nextCrash = null;
            if (realExitOnCrash) {
                System.err.println("[lab] simulated hard exit at " + point);
                Runtime.getRuntime().halt(3);
            }
            throw new CrashException(point);
        }
    }

    public static final class CrashException extends RuntimeException {
        public CrashException(String point) { super("simulated crash: " + point); }
    }

    // ---------------------------------------------------------------- endpoints

    public synchronized Models.Endpoint createEndpoint(Map<String, Object> req) {
        String id = orNew(Json.str(req, "id"), "ep");
        if (endpoints.containsKey(id)) throw new ApiException(409, "endpoint id exists: " + id);
        Models.Endpoint ep = new Models.Endpoint(
                id,
                Json.str(req, "name") == null ? "endpoint-" + id : Json.str(req, "name"),
                Json.str(req, "secret") == null ? "secret-" + id : Json.str(req, "secret"),
                Json.str(req, "keyId") == null ? "key-" + id : Json.str(req, "keyId"),
                Json.str(req, "coveredHeaders"),
                Json.lng(req, "initialBackoffMillis", 1000),
                req.get("backoffMultiplier") instanceof Number n ? n.doubleValue() : 2.0,
                Json.lng(req, "maxBackoffMillis", 60_000),
                Json.integer(req, "maxAttempts", 5),
                Json.bool(req, "paused", false),
                Json.str(req, "behavior") == null ? "SUCCESS"
                        : Json.str(req, "behavior").toUpperCase(),
                Json.integer(req, "statusCode", 500),
                Json.str(req, "retryAfter"),
                Instant.now().toString());
        log.append(LogStore.endpointRecord(ep));
        endpoints.put(ep.id(), ep);
        return ep;
    }

    public synchronized Models.Endpoint updateEndpoint(String id, Map<String, Object> req) {
        Models.Endpoint cur = requireEndpoint(id);
        Models.Endpoint ep = new Models.Endpoint(
                id,
                Json.str(req, "name") == null ? cur.name() : Json.str(req, "name"),
                Json.str(req, "secret") == null ? cur.secret() : Json.str(req, "secret"),
                Json.str(req, "keyId") == null ? cur.keyId() : Json.str(req, "keyId"),
                req.containsKey("coveredHeaders") ? Json.str(req, "coveredHeaders") : cur.coveredHeaders(),
                req.containsKey("initialBackoffMillis") ? Json.lng(req, "initialBackoffMillis", 1000)
                        : cur.initialBackoffMillis(),
                req.get("backoffMultiplier") instanceof Number n ? n.doubleValue() : cur.backoffMultiplier(),
                req.containsKey("maxBackoffMillis") ? Json.lng(req, "maxBackoffMillis", 60000)
                        : cur.maxBackoffMillis(),
                req.containsKey("maxAttempts") ? Json.integer(req, "maxAttempts", 5) : cur.maxAttempts(),
                cur.paused(), // pause changes only via /pause and /resume
                req.containsKey("behavior") ? Json.str(req, "behavior").toUpperCase() : cur.behavior(),
                req.containsKey("statusCode") ? Json.integer(req, "statusCode", 500) : cur.statusCode(),
                req.containsKey("retryAfter") ? Json.str(req, "retryAfter") : cur.retryAfter(),
                cur.createdAt());
        log.append(LogStore.endpointRecord(ep));
        endpoints.put(id, ep);
        return ep;
    }

    public synchronized Models.Endpoint setPaused(String id, boolean paused) {
        Models.Endpoint cur = requireEndpoint(id);
        Models.Endpoint ep = new Models.Endpoint(cur.id(), cur.name(), cur.secret(), cur.keyId(),
                cur.coveredHeaders(), cur.initialBackoffMillis(), cur.backoffMultiplier(),
                cur.maxBackoffMillis(), cur.maxAttempts(), paused, cur.behavior(), cur.statusCode(),
                cur.retryAfter(), cur.createdAt());
        log.append(LogStore.endpointRecord(ep));
        endpoints.put(id, ep);
        return ep;
    }

    public synchronized List<Models.Endpoint> listEndpoints() { return new ArrayList<>(endpoints.values()); }
    public synchronized Models.Endpoint requireEndpoint(String id) {
        Models.Endpoint ep = endpoints.get(id);
        if (ep == null) throw new ApiException(404, "no such endpoint: " + id);
        return ep;
    }

    // ------------------------------------------------------------------ events

    public synchronized Models.Event createEvent(Map<String, Object> req) {
        String endpointId = Json.str(req, "endpointId");
        requireEndpoint(endpointId);
        String eventId = Json.str(req, "eventId");
        if (eventId == null || eventId.isBlank()) eventId = ids.newId("evt");
        if (eventIdToChain.containsKey(eventId)) {
            throw new ApiException(409, "duplicate event id: " + eventId);
        }
        String payload = req.containsKey("payload") ? String.valueOf(req.get("payload")) : "";
        String contentType = Json.str(req, "contentType") == null
                ? "application/json" : Json.str(req, "contentType");
        String chainId = ids.newId("chain");
        Models.Event ev = new Models.Event(eventId, endpointId, chainId, payload, contentType,
                clock, Models.EventStatus.PENDING, new ArrayList<>(), null, 0);
        log.append(LogStore.eventRecord(ev));
        events.put(chainId, ev);
        eventIdToChain.put(eventId, chainId);
        attempts.put(chainId, new ArrayList<>());
        scheduleAttempt(chainId, clock, "initial dispatch");
        return ev;
    }

    public synchronized List<Models.Event> listEvents() { return new ArrayList<>(events.values()); }

    public synchronized Models.Event getChain(String chainId) {
        Models.Event ev = events.get(chainId);
        if (ev == null) throw new ApiException(404, "no such chain: " + chainId);
        return ev.withAttempts(List.copyOf(attempts.get(chainId)), ev.status());
    }

    public synchronized Models.Event replayDeadLetter(String chainId, Map<String, Object> req) {
        Models.Event original = getChain(chainId);
        if (original.status() != Models.EventStatus.DEAD_LETTER) {
            throw new ApiException(409, "only dead-letter chains can be replayed");
        }
        String newEventId = Json.str(req, "eventId");
        if (newEventId != null && eventIdToChain.containsKey(newEventId)) {
            throw new ApiException(409, "duplicate event id: " + newEventId);
        }
        if (newEventId == null || newEventId.isBlank()) {
            newEventId = ids.newId("evt");
        }
        String newChainId = ids.newId("chain");
        String payload = Json.str(req, "payload") == null ? original.payload()
                : String.valueOf(req.get("payload"));
        long depth = original.replayDepth() + 1;
        Models.Event ev = new Models.Event(newEventId, original.endpointId(), newChainId,
                payload, original.contentType(), clock, Models.EventStatus.PENDING,
                new ArrayList<>(), original.chainId(), depth);
        log.append(LogStore.eventRecord(ev));
        events.put(newChainId, ev);
        eventIdToChain.put(newEventId, newChainId);
        attempts.put(newChainId, new ArrayList<>());
        scheduleAttempt(newChainId, clock,
                "replay of dead-letter chain " + original.chainId());
        return ev;
    }

    // ------------------------------------------------------------- scheduling

    /** Build the signed pending attempt and durably schedule it. */
    private Models.Attempt scheduleAttempt(String chainId, long at, String reason) {
        Models.Event ev = events.get(chainId);
        Models.Endpoint ep = requireEndpoint(ev.endpointId());
        List<Models.Attempt> list = attempts.get(chainId);
        int seq = list.size() + 1;
        String attemptId = ids.newId("att");
        byte[] body = ev.payload().getBytes(StandardCharsets.UTF_8);
        String path = "/in-process/" + ep.id() + "/webhook";

        Map<String, String> headers = buildHeaders(ep, ev.eventId(), attemptId, at);
        Signer.Signed signed = Signer.sign("POST", path, body, headers, ep.covered(),
                ev.eventId(), attemptId, at, ep.secret());
        headers.put(Signer.HEADER_SIGNATURE, signed.signature());

        Models.Attempt a = new Models.Attempt(
                attemptId, seq, at, 0L, 0L, "POST", path,
                new LinkedHashMap<>(headers), Base64.getEncoder().encodeToString(body),
                body.length, Signer.sha256Hex(body), signed.signingInput(),
                signed.signature(), Models.AttemptStatus.PENDING,
                null, null, null, null, false,
                new LinkedHashMap<>(), null, reason, null, ev.replayOfChain(), null);
        list.add(a);
        log.append(LogStore.attemptRecord(chainId, a));
        schedule.add(new Scheduled(at, chainId, attemptId));
        markEventStatus(chainId, Models.EventStatus.PENDING);
        return a;
    }

    private Map<String, String> buildHeaders(Models.Endpoint ep, String eventId,
                                             String attemptId, long timestamp) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("content-type", "application/json");
        h.put(Signer.HEADER_KEY_ID, ep.keyId());
        h.put(Signer.HEADER_EVENT_ID, eventId);
        h.put(Signer.HEADER_ATTEMPT_ID, attemptId);
        h.put(Signer.HEADER_TIMESTAMP, String.valueOf(timestamp));
        h.put("host", "receiver.in-process.local");
        return h;
    }

    private void commitProcessed(String endpointId, String eventId, String attemptId, long atVirtual) {
        String key = endpointId + "|" + eventId;
        if (processed.contains(key)) return;
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("type", "processed");
        rec.put("endpointId", endpointId);
        rec.put("eventId", eventId);
        rec.put("attemptId", attemptId);
        rec.put("atVirtual", atVirtual);
        log.append(rec);
        processed.add(key);
    }

    private void replaceAttempt(String chainId, Models.Attempt updated) {
        List<Models.Attempt> list = attempts.get(chainId);
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).attemptId().equals(updated.attemptId())) {
                list.set(i, updated);
            }
        }
        log.append(LogStore.attemptRecord(chainId, updated));
    }

    private void markEventStatus(String chainId, Models.EventStatus status) {
        Models.Event ev = events.get(chainId);
        if (ev != null && ev.status() != status) {
            ev = ev.withStatus(status);
            events.put(chainId, ev);
            log.append(LogStore.eventRecord(ev));
        }
    }

    // ----------------------------------------------------------- virtual clock

    public record TickResult(long previousClock, long newClock, int dispatched,
                             List<String> chainIds) {}

    /** Advance the clock by an explicit delta (millis), then dispatch due work. */
    public synchronized TickResult advance(long deltaMillis) {
        if (deltaMillis < 0) throw new ApiException(400, "delta must be >= 0");
        return runTick(clock + deltaMillis, "advance(" + deltaMillis + "ms)");
    }

    /** Advance to the next scheduled attempt (or stay if none), then dispatch. */
    public synchronized TickResult step() {
        long target = clock;
        // step to the earliest attempt whose endpoint is not paused; paused
        // attempts stay frozen at their original instant and never pull the
        // virtual clock forward.
        boolean found = false;
        for (Scheduled sItem : new TreeSet<>(schedule)) {
            Models.Attempt a = findAttempt(sItem.chainId(), sItem.attemptId());
            if (a == null || a.status().terminal() || a.status() == Models.AttemptStatus.IN_FLIGHT) {
                continue;
            }
            Models.Endpoint ep = endpoints.get(events.get(sItem.chainId()).endpointId());
            if (ep.paused() && a.dispatchedAt() == 0L) continue;
            target = sItem.at();
            found = true;
            break;
        }
        if (!found) {
            // only paused work exists: make no virtual progress
            return runTick(clock, "step");
        }
        if (target < clock) target = clock;
        return runTick(target, "step");
    }

    private TickResult runTick(long target, String mode) {
        // clock movement is itself committed so recovery cannot rewind time
        clock = target;
        Map<String, Object> clockRec = new LinkedHashMap<>();
        clockRec.put("type", "clock");
        clockRec.put("now", clock);
        log.append(clockRec);

        int dispatched = 0;
        List<String> touched = new ArrayList<>();
        // dispatch every due attempt; a paused endpoint keeps its attempt in the
        // tree at its original instant and is simply skipped (never rescheduled).
        List<Scheduled> due = new ArrayList<>();
        for (Scheduled sItem : new ArrayList<>(schedule)) {
            if (sItem.at() <= clock) due.add(sItem); else break;
        }
        for (Scheduled next : due) {
            Models.Attempt a = findAttempt(next.chainId(), next.attemptId());
            if (a == null || a.status().terminal() || a.status() == Models.AttemptStatus.IN_FLIGHT) {
                schedule.remove(next);
                continue;
            }
            Models.Endpoint ep = endpoints.get(events.get(next.chainId()).endpointId());
            if (ep.paused() && a.dispatchedAt() == 0L) {
                continue; // stays in tree at original time
            }
            schedule.remove(next);
            try {
                if (nextCrash != null && nextCrash.equals(CRASH_AFTER_SCHEDULE)) {
                    nextCrash = null;
                    if (realExitOnCrash) Runtime.getRuntime().halt(3);
                    throw new CrashException(CRASH_AFTER_SCHEDULE);
                }
                dispatch(next.chainId(), a);
            } catch (CrashException ce) {
                if (!touched.contains(next.chainId())) touched.add(next.chainId());
                throw ce;
            }
            dispatched++;
            if (!touched.contains(next.chainId())) touched.add(next.chainId());
        }
        return new TickResult(target, clock, dispatched, touched);
    }

    private Models.Attempt findAttempt(String chainId, String attemptId) {
        for (Models.Attempt a : attempts.getOrDefault(chainId, List.of())) {
            if (a.attemptId().equals(attemptId)) return a;
        }
        return null;
    }

    // --------------------------------------------------------------- delivery

    private void dispatch(String chainId, Models.Attempt pending) {
        Models.Event ev = events.get(chainId);
        Models.Endpoint ep = requireEndpoint(ev.endpointId());
        long now = clock;

        // phase 1: committed in-flight record, still no send
        Models.Attempt inFlight = new Models.Attempt(
                pending.attemptId(), pending.seq(), pending.scheduledAt(), now, 0L,
                pending.method(), pending.path(), pending.requestHeaders(),
                pending.bodyBase64(), pending.bodyLength(), pending.sha256(),
                pending.signingInput(), pending.signature(),
                Models.AttemptStatus.IN_FLIGHT, null, null, null, null, false,
                new LinkedHashMap<>(), null, pending.nextReason(), null,
                pending.replayOfChain(), null);
        replaceAttempt(chainId, inFlight);
        markEventStatus(chainId, Models.EventStatus.IN_FLIGHT);

        crashIf(CRASH_BEFORE_SEND);

        byte[] body = Base64.getDecoder().decode(pending.bodyBase64());
        Receiver.Request req = new Receiver.Request(
                ep.id(), pending.method(), pending.path(), body,
                pending.requestHeaders(), ep.covered(),
                ev.eventId(), pending.attemptId(), pending.scheduledAt(),
                pending.signature());

        Models.ReceiverOutcome outcome = receiver.deliver(req, ep, now, processed);
        crashIf(CRASH_AFTER_SEND);
        resolve(chainId, inFlight, ep, outcome, now);
    }

    private void resolve(String chainId, Models.Attempt inFlight, Models.Endpoint ep,
                         Models.ReceiverOutcome outcome, long now) {
        Models.Attempt a = inFlight;
        String retryParsed = null;
        Long retryDelay = null;
        if (outcome.retryAfter() != null) {
            Backoff.RetryAfter ra = Backoff.parseRetryAfter(outcome.retryAfter(), now);
            if (ra != null) { retryDelay = ra.delayMillis(); retryParsed = ra.display(); }
        }

        boolean success = outcome.statusCode() != null && outcome.statusCode() == 200;
        boolean retryable = outcome.statusCode() == null
                || outcome.statusCode() == 429
                || (outcome.statusCode() >= 500 && outcome.statusCode() < 600);

        Models.AttemptStatus status;
        Long nextAt = null;
        String nextReason = null;

        if (success) {
            status = Models.AttemptStatus.SUCCEEDED;
        } else if (!retryable) {
            status = Models.AttemptStatus.DEAD_LETTER;
            nextReason = "terminal status " + outcome.statusCode();
        } else {
            String eventId = events.get(chainId).eventId();
            boolean isRate = outcome.statusCode() != null && outcome.statusCode() == 429;
            long delay;
            if (isRate && retryDelay != null) {
                delay = retryDelay;
                nextReason = "429 Retry-After (" + retryParsed + ") => " + delay + "ms";
            } else if (outcome.statusCode() == null) {
                delay = computeBackoff(ep, eventId, a.seq());
                nextReason = outcome.error() + "; backoff => " + delay + "ms";
            } else {
                delay = computeBackoff(ep, eventId, a.seq());
                nextReason = "status " + outcome.statusCode() + "; backoff => " + delay + "ms";
            }
            if (delay > ep.maxBackoffMillis() || a.seq() >= ep.maxAttempts()) {
                status = Models.AttemptStatus.DEAD_LETTER;
                nextAt = null;
                nextReason = (delay > ep.maxBackoffMillis()
                        ? "delay " + delay + "ms exceeds endpoint cap " + ep.maxBackoffMillis()
                        : "max attempts (" + ep.maxAttempts() + ") exhausted")
                        + (isRate ? " [Retry-After]" : "");
            } else {
                status = Models.AttemptStatus.FAILED_RETRY;
                nextAt = now + delay;
            }
        }

        Map<String, String> respHeaders = new LinkedHashMap<>();
        if (outcome.statusCode() != null) {
            respHeaders.put("content-type", "application/json");
            if (outcome.retryAfter() != null) respHeaders.put("retry-after", outcome.retryAfter());
            respHeaders.put("x-lab-receiver", "in-process");
            if (outcome.duplicate()) respHeaders.put("x-lab-duplicate", "true");
        }

        Models.Attempt resolved = new Models.Attempt(
                a.attemptId(), a.seq(), a.scheduledAt(), a.dispatchedAt(), now,
                a.method(), a.path(), a.requestHeaders(), a.bodyBase64(), a.bodyLength(),
                a.sha256(), a.signingInput(), a.signature(),
                status, outcome.statusCode(), respHeaders, outcome.body(), outcome.error(),
                outcome.processed(), outcome.verification(),
                nextAt, nextReason, null, a.replayOfChain(), retryParsed);
        replaceAttempt(chainId, resolved);

        if (status == Models.AttemptStatus.SUCCEEDED) {
            markEventStatus(chainId, Models.EventStatus.SUCCEEDED);
        } else if (status == Models.AttemptStatus.DEAD_LETTER) {
            markEventStatus(chainId, Models.EventStatus.DEAD_LETTER);
        } else {
            markEventStatus(chainId, Models.EventStatus.PENDING);
            scheduleAttempt(chainId, nextAt, "attempt " + a.seq() + ": " + nextReason);
        }
    }

    private long computeBackoff(Models.Endpoint ep, String eventId, long failureCount) {
        long seed = Backoff.chainSeed(jitterSeed, eventId);
        return Backoff.jitteredDelay(ep.initialBackoffMillis(), ep.backoffMultiplier(),
                ep.maxBackoffMillis(), seed, failureCount);
    }

    // --------------------------------------------------------------- recovery

    private void recover() {
        List<Map<String, Object>> records = LogStore.readAll(dataDir.resolve("lab.log"));

        // pass 1: rebuild endpoints, events and ordered attempts
        for (Map<String, Object> r : records) {
            String type = Json.str(r, "type");
            switch (type == null ? "" : type) {
                case "endpoint" -> {
                    Models.Endpoint ep = LogStore.decodeEndpoint(r);
                    endpoints.put(ep.id(), ep);
                }
                case "event" -> {
                    Models.Event ev = LogStore.decodeEvent(r);
                    events.put(ev.chainId(), ev);
                    eventIdToChain.put(ev.eventId(), ev.chainId());
                    attempts.putIfAbsent(ev.chainId(), new ArrayList<>());
                }
                case "attempt" -> {
                    String chainId = LogStore.chainIdOf(r);
                    Models.Attempt a = LogStore.decodeAttempt(r);
                    List<Models.Attempt> list = attempts.computeIfAbsent(chainId, k -> new ArrayList<>());
                    upsert(list, a);
                }
                case "processed" -> processed.add(Json.str(r, "endpointId") + "|" + Json.str(r, "eventId"));
                case "clock" -> clock = Json.lng(r, "now", clock);
                default -> { }
            }
        }
        if (clock == 0) clock = EPOCH_START;

        // pass 2: find non-terminal tails and rebuild the schedule
        for (Map.Entry<String, List<Models.Attempt>> e : attempts.entrySet()) {
            String chainId = e.getKey();
            List<Models.Attempt> list = e.getValue();
            if (list.isEmpty()) continue;
            Models.Attempt tail = list.get(list.size() - 1);

            if (tail.status() == Models.AttemptStatus.IN_FLIGHT) {
                // crashed after dispatch (before response persisted): outcome unknown
                Models.Attempt recovered = new Models.Attempt(
                        tail.attemptId(), tail.seq(), tail.scheduledAt(), tail.dispatchedAt(), clock,
                        tail.method(), tail.path(), tail.requestHeaders(), tail.bodyBase64(),
                        tail.bodyLength(), tail.sha256(), tail.signingInput(), tail.signature(),
                        Models.AttemptStatus.RECOVERED, null, tail.responseHeaders(), null,
                        "recovered: outcome unknown after restart", tail.receiverProcessed(),
                        tail.verification(), null, "crash recovery: resending",
                        CRASH_AFTER_SEND, tail.replayOfChain(), null);
                list.set(list.size() - 1, recovered);
                log.append(LogStore.attemptRecord(chainId, recovered));
                markEventStatus(chainId, Models.EventStatus.PENDING);
                scheduleAttempt(chainId, clock, "recovered in-flight attempt; immediate retry");
            } else if (tail.status() == Models.AttemptStatus.PENDING) {
                // crashed after schedule write or before send; schedule survives
                schedule.add(new Scheduled(tail.scheduledAt(), chainId, tail.attemptId()));
                markEventStatus(chainId, Models.EventStatus.PENDING);
            } else if (tail.status() == Models.AttemptStatus.FAILED_RETRY) {
                // retry decision committed but its schedule write crashed
                if (tail.nextScheduledAt() == null) {
                    scheduleAttempt(chainId, clock, "retry schedule lost; immediate retry");
                } else {
                    schedule.add(new Scheduled(tail.nextScheduledAt(), chainId, tail.attemptId()));
                }
                markEventStatus(chainId, Models.EventStatus.PENDING);
            } else {
                markEventStatus(chainId, tail.status() == Models.AttemptStatus.SUCCEEDED
                        ? Models.EventStatus.SUCCEEDED : Models.EventStatus.DEAD_LETTER);
            }
            seqCounter = Math.max(seqCounter, tail.seq());
        }
    }

    private static void upsert(List<Models.Attempt> list, Models.Attempt a) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).attemptId().equals(a.attemptId())) { list.set(i, a); return; }
        }
        list.add(a);
    }

    // --------------------------------------------------------------- snapshot

    public synchronized Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("clock", clock);
        m.put("clockIso", java.time.Instant.ofEpochMilli(clock).toString());
        m.put("jitterSeed", jitterSeed);
        m.put("crashPoint", nextCrash);
        List<Object> eps = new ArrayList<>();
        endpoints.values().forEach(ep -> {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("id", ep.id()); e.put("name", ep.name()); e.put("keyId", ep.keyId());
            e.put("coveredHeaders", ep.coveredHeaders());
            e.put("initialBackoffMillis", ep.initialBackoffMillis());
            e.put("backoffMultiplier", ep.backoffMultiplier());
            e.put("maxBackoffMillis", ep.maxBackoffMillis());
            e.put("maxAttempts", ep.maxAttempts());
            e.put("paused", ep.paused()); e.put("behavior", ep.behavior());
            e.put("statusCode", ep.statusCode()); e.put("retryAfter", ep.retryAfter());
            eps.add(e);
        });
        m.put("endpoints", eps);

        List<Object> evs = new ArrayList<>();
        for (Models.Event ev : events.values()) {
            Map<String, Object> view = ev.toView();
            List<Object> ats = new ArrayList<>();
            for (Models.Attempt a : attempts.get(ev.chainId())) ats.add(a.toView());
            view.put("attempts", ats);
            evs.add(view);
        }
        m.put("chains", evs);

        List<Object> pending = new ArrayList<>();
        for (Scheduled s : new TreeSet<>(schedule)) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("at", s.at()); p.put("chainId", s.chainId()); p.put("attemptId", s.attemptId());
            pending.add(p);
        }
        m.put("schedule", pending);
        m.put("processedCount", processed.size());
        return m;
    }

    public synchronized void close() { log.close(); }

    private String orNew(String v, String prefix) {
        return (v == null || v.isBlank()) ? ids.newId(prefix) : v;
    }

    public static final class ApiException extends RuntimeException {
        private final int status;
        public ApiException(int status, String message) { super(message); this.status = status; }
        public int status() { return status; }
    }
}
