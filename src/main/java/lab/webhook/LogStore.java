package lab.webhook;

import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Append-only JSONL durability log. Each append is flushed and fsynced before
 * returning, so a committed record survives a hard JVM kill.
 *
 * All delivery state (endpoints, events, attempts, clock, receiver dedup set)
 * is rebuilt by replaying this single ordered log on startup.
 */
public final class LogStore implements AutoCloseable {

    private final Path file;
    private final OutputStream out;

    public LogStore(Path file) {
        try {
            Files.createDirectories(file.getParent());
            this.file = file;
            this.out = Files.newOutputStream(file,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new IllegalStateException("cannot open log " + file, e);
        }
    }

    public synchronized void append(Map<String, Object> record) {
        try {
            byte[] line = (Json.write(record) + "\n").getBytes(StandardCharsets.UTF_8);
            out.write(line);
            out.flush();
            if (out instanceof FileOutputStream fos) {
                fos.getFD().sync();
            }
        } catch (IOException e) {
            throw new IllegalStateException("append failed", e);
        }
    }

    public static List<Map<String, Object>> readAll(Path file) {
        List<Map<String, Object>> records = new ArrayList<>();
        if (!Files.exists(file)) return records;
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            int n = 0;
            while ((line = r.readLine()) != null) {
                n++;
                if (line.isBlank()) continue;
                try {
                    records.add(Json.parseObject(line));
                } catch (Exception e) {
                    throw new IllegalStateException("corrupt log line " + n + " in " + file, e);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot read log " + file, e);
        }
        return records;
    }

    @Override
    public void close() {
        try { out.close(); } catch (IOException ignored) { }
    }

    // ---- typed record codecs -------------------------------------------------

    public static Map<String, Object> endpointRecord(Models.Endpoint ep) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "endpoint");
        m.put("id", ep.id());
        m.put("name", ep.name());
        m.put("secret", ep.secret());
        m.put("keyId", ep.keyId());
        m.put("coveredHeaders", ep.coveredHeaders());
        m.put("initialBackoffMillis", ep.initialBackoffMillis());
        m.put("backoffMultiplier", ep.backoffMultiplier());
        m.put("maxBackoffMillis", ep.maxBackoffMillis());
        m.put("maxAttempts", ep.maxAttempts());
        m.put("paused", ep.paused());
        m.put("behavior", ep.behavior());
        m.put("statusCode", ep.statusCode());
        m.put("retryAfter", ep.retryAfter());
        m.put("createdAt", ep.createdAt());
        return m;
    }

    public static Models.Endpoint decodeEndpoint(Map<String, Object> m) {
        return new Models.Endpoint(
                Json.str(m, "id"), Json.str(m, "name"), Json.str(m, "secret"),
                Json.str(m, "keyId"), Json.str(m, "coveredHeaders"),
                Json.lng(m, "initialBackoffMillis", 1000),
                m.get("backoffMultiplier") instanceof Number n ? n.doubleValue() : 2.0,
                Json.lng(m, "maxBackoffMillis", 60000),
                Json.integer(m, "maxAttempts", 5),
                Json.bool(m, "paused", false),
                Json.str(m, "behavior"), Json.integer(m, "statusCode", 500),
                Json.str(m, "retryAfter"), Json.str(m, "createdAt"));
    }

    public static Map<String, Object> eventRecord(Models.Event ev) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "event");
        m.put("eventId", ev.eventId());
        m.put("endpointId", ev.endpointId());
        m.put("chainId", ev.chainId());
        m.put("payload", ev.payload());
        m.put("contentType", ev.contentType());
        m.put("createdAt", ev.createdAt());
        m.put("status", ev.status().name());
        m.put("replayOfChain", ev.replayOfChain());
        m.put("replayDepth", ev.replayDepth());
        return m;
    }

    public static Models.Event decodeEvent(Map<String, Object> m) {
        return new Models.Event(
                Json.str(m, "eventId"), Json.str(m, "endpointId"), Json.str(m, "chainId"),
                Json.str(m, "payload"), Json.str(m, "contentType"),
                Json.lng(m, "createdAt", 0),
                Models.EventStatus.valueOf(Json.str(m, "status")),
                new ArrayList<>(), Json.str(m, "replayOfChain"),
                Json.lng(m, "replayDepth", 0));
    }

    public static Map<String, Object> attemptRecord(String chainId, Models.Attempt a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "attempt");
        m.put("chainId", chainId);
        m.put("attemptId", a.attemptId());
        m.put("seq", a.seq());
        m.put("scheduledAt", a.scheduledAt());
        m.put("dispatchedAt", a.dispatchedAt());
        m.put("resolvedAt", a.resolvedAt());
        m.put("method", a.method());
        m.put("path", a.path());
        m.put("requestHeaders", a.requestHeaders());
        m.put("bodyBase64", a.bodyBase64());
        m.put("bodyLength", a.bodyLength());
        m.put("sha256", a.sha256());
        m.put("signingInput", a.signingInput());
        m.put("signature", a.signature());
        m.put("status", a.status().name());
        m.put("responseStatus", a.responseStatus());
        m.put("responseHeaders", a.responseHeaders());
        m.put("responseBody", a.responseBody());
        m.put("error", a.error());
        m.put("receiverProcessed", a.receiverProcessed());
        m.put("verification", a.verification());
        m.put("nextScheduledAt", a.nextScheduledAt());
        m.put("nextReason", a.nextReason());
        m.put("crashedAt", a.crashedAt());
        m.put("replayOfChain", a.replayOfChain());
        m.put("retryAfterParsed", a.retryAfterParsed());
        return m;
    }

    @SuppressWarnings("unchecked")
    public static Models.Attempt decodeAttempt(Map<String, Object> m) {
        Map<String, String> req = new LinkedHashMap<>();
        if (m.get("requestHeaders") instanceof Map<?, ?> mm)
            mm.forEach((k, v) -> req.put(String.valueOf(k), String.valueOf(v)));
        Map<String, String> rsp = new LinkedHashMap<>();
        if (m.get("responseHeaders") instanceof Map<?, ?> mm)
            mm.forEach((k, v) -> rsp.put(String.valueOf(k), String.valueOf(v)));
        Map<String, Object> ver = new LinkedHashMap<>();
        if (m.get("verification") instanceof Map<?, ?> mm)
            mm.forEach((k, v) -> ver.put(String.valueOf(k), v));
        Integer respStatus = m.get("responseStatus") instanceof Number n ? n.intValue() : null;
        Long nextAt = m.get("nextScheduledAt") instanceof Number n ? n.longValue() : null;
        return new Models.Attempt(
                Json.str(m, "attemptId"), Json.integer(m, "seq", 0),
                Json.lng(m, "scheduledAt", 0), Json.lng(m, "dispatchedAt", 0),
                Json.lng(m, "resolvedAt", 0),
                Json.str(m, "method"), Json.str(m, "path"),
                req, Json.str(m, "bodyBase64"), Json.lng(m, "bodyLength", 0),
                Json.str(m, "sha256"), Json.str(m, "signingInput"), Json.str(m, "signature"),
                Models.AttemptStatus.valueOf(Json.str(m, "status")),
                respStatus, rsp,
                Json.str(m, "responseBody"), Json.str(m, "error"),
                Json.bool(m, "receiverProcessed", false), ver,
                nextAt, Json.str(m, "nextReason"), Json.str(m, "crashedAt"),
                Json.str(m, "replayOfChain"), Json.str(m, "retryAfterParsed"));
    }

    public static String chainIdOf(Map<String, Object> m) {
        return Json.str(m, "chainId");
    }
}
