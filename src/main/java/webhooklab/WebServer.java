package webhooklab;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** Embedded HTTP UI + JSON API. Serves no external webhook targets. */
public final class WebServer {

    private static final long MAX_BODY = 1024 * 1024;

    private final Engine engine;
    private final HttpServer server;

    public WebServer(Engine engine, String host, int port) throws IOException {
        this.engine = engine;
        this.server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.createContext("/", this::route);
        server.setExecutor(java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "webhook-lab-http");
            t.setDaemon(true);
            return t;
        }));
    }

    public void start() { server.start(); }
    public void stop() { server.stop(0); }
    public int port() { return server.getAddress().getPort(); }

    private void route(HttpExchange ex) {
        try {
            String method = ex.getRequestMethod();
            String path = ex.getRequestURI().getRawPath();
            switch (path) {
                case "/" -> {
                    if ("GET".equals(method)) {
                        bytes(ex, 200, "text/html; charset=utf-8", readResource("/web/index.html"));
                        return;
                    }
                }
                case "/api/state" -> {
                    if ("GET".equals(method)) { json(ex, 200, StateView.full(engine.store())); return; }
                }
                case "/api/endpoints" -> {
                    if ("PUT".equals(method) || "POST".equals(method)) {
                        handle(ex, b -> { engine.upsertEndpoint(b); return StateView.full(engine.store()); });
                        return;
                    }
                }
                case "/api/events" -> {
                    if ("POST".equals(method)) {
                        handle(ex, b -> {
                            Model.Chain c = engine.sendEvent(b);
                            Map<String, Object> out = Json.obj();
                            out.put("chain", StateView.chain(engine.store(), c.id()));
                            return out;
                        });
                        return;
                    }
                }
                case "/api/clock/step" -> {
                    if ("POST".equals(method)) {
                        handle(ex, b -> engine.stepNext());
                        return;
                    }
                }
                default -> {
                    if (method.equals("POST") && path.startsWith("/api/clock/advance")) {
                        long ms = parseMs(ex.getRequestURI().getRawQuery());
                        handle(ex, b -> engine.advance(ms));
                        return;
                    }
                    String pauseSeg = match(path, "/api/endpoints/", "/pause");
                    if (method.equals("POST") && pauseSeg != null) {
                        final String seg = pauseSeg;
                        handle(ex, b -> { engine.setPaused(seg, true); return StateView.full(engine.store()); });
                        return;
                    }
                    String resumeSeg = match(path, "/api/endpoints/", "/resume");
                    if (method.equals("POST") && resumeSeg != null) {
                        final String seg = resumeSeg;
                        handle(ex, b -> { engine.setPaused(seg, false); return StateView.full(engine.store()); });
                        return;
                    }
                    String replaySeg = match(path, "/api/chains/", "/replay");
                    if (method.equals("POST") && replaySeg != null) {
                        final String seg = replaySeg;
                        handle(ex, b -> {
                            Model.Chain c = engine.replayChain(seg);
                            Map<String, Object> out = Json.obj();
                            out.put("chain", StateView.chain(engine.store(), c.id()));
                            return out;
                        });
                        return;
                    }
                    if (method.equals("GET") && path.startsWith("/api/chains/")) {
                        String id = path.substring("/api/chains/".length());
                        Map<String, Object> c = StateView.chain(engine.store(), id);
                        if (c == null) { error(ex, 404, "no such chain: " + id); return; }
                        json(ex, 200, c);
                        return;
                    }
                }
            }
            error(ex, 404, "not found: " + method + " " + path);
        } catch (Engine.ApiError e) {
            safeError(ex, e.status, e.getMessage());
        } catch (Exception e) {
            safeError(ex, 500, "internal error: " + e.getMessage());
        }
    }

    private static void safeError(HttpExchange ex, int status, String message) {
        try {
            error(ex, status, message);
        } catch (IOException ioe) {
            // connection already gone; nothing more to do
        }
    }

    private static String match(String path, String prefix, String suffix) {
        if (!path.startsWith(prefix) || !path.endsWith(suffix)) return null;
        String middle = path.substring(prefix.length(), path.length() - suffix.length());
        return middle.contains("/") ? null : middle;
    }

    private static long parseMs(String query) {
        if (query == null) return 1000;
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv[0].equals("ms") && kv.length == 2) {
                try { return Long.parseLong(kv[1]); } catch (NumberFormatException ignored) { }
            }
        }
        return 1000;
    }

    private interface BodyHandler {
        Map<String, Object> apply(Map<String, Object> body) throws Exception;
    }

    private void handle(HttpExchange ex, BodyHandler handler) throws IOException {
        Map<String, Object> body = Json.obj();
        byte[] raw;
        try {
            raw = readLimited(ex);
        } catch (Engine.ApiError e) {
            error(ex, e.status, e.getMessage());
            return;
        }
        if (raw.length > 0) {
            try {
                body = Json.parseObject(new String(raw, StandardCharsets.UTF_8));
            } catch (Exception e) {
                error(ex, 400, "invalid JSON body: " + e.getMessage());
                return;
            }
        }
        try {
            Map<String, Object> response;
            synchronized (engine) {
                response = handler.apply(body);
            }
            json(ex, 200, response);
        } catch (Engine.ApiError e) {
            error(ex, e.status, e.getMessage());
        } catch (Exception e) {
            error(ex, 500, e.getMessage());
        }
    }

    private static byte[] readLimited(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            byte[] buf = new byte[8192];
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            int n;
            long total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_BODY) throw new Engine.ApiError(413, "request body too large");
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    private static void json(HttpExchange ex, int status, Object body) throws IOException {
        bytes(ex, status, "application/json; charset=utf-8",
                Json.of(body).getBytes(StandardCharsets.UTF_8));
    }

    private static void error(HttpExchange ex, int status, String message) throws IOException {
        Map<String, Object> m = Json.obj();
        m.put("error", message);
        json(ex, status, m);
    }

    private static void bytes(HttpExchange ex, int status, String contentType, byte[] data) throws IOException {
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, data.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(data);
        }
    }

    private static byte[] readResource(String name) throws IOException {
        try (InputStream in = WebServer.class.getResourceAsStream(name)) {
            if (in == null) throw new IOException("missing resource " + name);
            return in.readAllBytes();
        }
    }

    /** Lists supported API routes (used by the UI help text). */
    public static List<String> routes() {
        return List.of(
                "GET  /api/state",
                "PUT  /api/endpoints",
                "POST /api/events",
                "POST /api/clock/step",
                "POST /api/clock/advance?ms=N",
                "POST /api/endpoints/{id}/pause",
                "POST /api/endpoints/{id}/resume",
                "POST /api/chains/{id}/replay",
                "GET  /api/chains/{id}");
    }
}
