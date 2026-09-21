package lab.webhook;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Entry point: serves the browser UI and JSON API, deliveries stay in-process. */
public final class Main {

    private final LabEngine engine;
    private final HttpServer server;

    public Main(LabEngine engine, String host, int port) throws IOException {
        this.engine = engine;
        this.server = HttpServer.create(new InetSocketAddress(host, port), 0);
        this.server.createContext("/", this::route);
        this.server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4));
    }

    public void start() { server.start(); }
    public void stop() { server.stop(0); }
    public int port() { return server.getAddress().getPort(); }

    public static void main(String[] args) throws Exception {
        Args a = Args.parse(args);
        Path data = Path.of(a.dataDir);
        Files.createDirectories(data);
        LabEngine engine = new LabEngine(data);
        engine.setRealExitOnCrash(a.exitOnCrash());
        Main app = new Main(engine, a.host, a.port);
        app.start();
        System.out.println("Webhook 投递实验室 listening on http://" + a.host + ":" + app.port());
        System.out.println("data dir: " + data.toAbsolutePath());
    }

    record Args(String host, int port, String dataDir, boolean exitOnCrash) {
        static Args parse(String[] args) {
            String host = "127.0.0.1";
            int port = 5232;
            String dataDir = "lab-data";
            boolean exitOnCrash = false;
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--host" -> host = args[++i];
                    case "--port" -> port = Integer.parseInt(args[++i]);
                    case "--data" -> dataDir = args[++i];
                    case "--exit-on-crash" -> exitOnCrash = true;
                    default -> { }
                }
            }
            return new Args(host, port, dataDir, exitOnCrash);
        }
    }

    private void route(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            if ("GET".equals(ex.getRequestMethod()) && ("/".equals(path) || "/index.html".equals(path))) {
                serveStatic(ex, "/web/index.html", "text/html; charset=utf-8");
                return;
            }
            if ("GET".equals(ex.getRequestMethod()) && path.startsWith("/web/")) {
                serveStatic(ex, path, staticContentType(path));
                return;
            }
            if (path.startsWith("/api/")) {
                api(ex, path);
                return;
            }
            send(ex, 404, Json.write(Map.of("error", "not found")), "application/json");
        } catch (LabEngine.ApiException e) {
            send(ex, e.status(), Json.write(Map.of("error", e.getMessage())), "application/json");
        } catch (Exception e) {
            e.printStackTrace();
            send(ex, 500, Json.write(Map.of("error", String.valueOf(e.getMessage()))),
                    "application/json");
        }
    }

    private String staticContentType(String path) {
        if (path.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (path.endsWith(".css")) return "text/css; charset=utf-8";
        return "text/plain; charset=utf-8";
    }

    private void serveStatic(HttpExchange ex, String resource, String contentType) throws IOException {
        byte[] body;
        try (var in = Main.class.getResourceAsStream(resource)) {
            if (in == null) {
                send(ex, 404, "not found", "text/plain");
                return;
            }
            body = in.readAllBytes();
        }
        sendBytes(ex, 200, body, contentType);
    }

    // -------------------------------------------------------------- JSON API

    private void api(HttpExchange ex, String path) throws IOException {
        String method = ex.getRequestMethod();
        Map<String, Object> body = readJson(ex);
        Object result = switch (method + " " + path) {
            case "GET /api/state" -> engine.snapshot();
            case "POST /api/endpoints" -> engine.createEndpoint(body);
            default -> routeDynamic(ex, path, method, body);
        };
        if (result == null) return; // dynamic handler already responded
        send(ex, 200, Json.write(result), "application/json");
    }

    private Object routeDynamic(HttpExchange ex, String path, String method,
                                Map<String, Object> body) throws IOException {
        String[] parts = path.split("/");
        // /api/endpoints/{id}, /api/endpoints/{id}/pause ...
        if (path.startsWith("/api/endpoints/") && parts.length >= 4) {
            String id = parts[3];
            if ("PUT".equals(method) && parts.length == 4) return engine.updateEndpoint(id, body);
            if ("POST".equals(method) && parts.length == 5 && "pause".equals(parts[4])) {
                return engine.setPaused(id, true);
            }
            if ("POST".equals(method) && parts.length == 5 && "resume".equals(parts[4])) {
                return engine.setPaused(id, false);
            }
        }
        if ("POST /api/events".equals(method + " " + path)) return engine.createEvent(body);
        if ("POST /api/clock/step".equals(method + " " + path)) return engine.step();
        if ("POST /api/clock/advance".equals(method + " " + path))
            return engine.advance(Json.lng(body, "millis", 0));
        if (path.startsWith("/api/chains/") && parts.length == 4 && "GET".equals(method)) {
            return engine.getChain(parts[3]).toView();
        }
        if (path.startsWith("/api/chains/") && parts.length == 5 && "replay".equals(parts[4])
                && "POST".equals(method)) {
            return engine.replayDeadLetter(parts[3], body);
        }
        if ("POST /api/admin/jitter-seed".equals(method + " " + path)) {
            engine.setJitterSeed(Json.lng(body, "seed", 42));
            return Map.of("jitterSeed", engine.getJitterSeed());
        }
        if ("POST /api/admin/crash".equals(method + " " + path)) {
            String point = Json.str(body, "point");
            engine.armCrash(point);
            return Map.of("crashPoint", String.valueOf(point));
        }
        if ("POST /api/admin/clear-crash".equals(method + " " + path)) {
            engine.clearCrash();
            return Map.of("crashPoint", "");
        }
        throw new LabEngine.ApiException(404, "no such api route: " + method + " " + path);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJson(HttpExchange ex) {
        try {
            byte[] raw = ex.getRequestBody().readAllBytes();
            if (raw.length == 0) return new java.util.LinkedHashMap<>();
            Object v = Json.parse(new String(raw, StandardCharsets.UTF_8));
            return v instanceof Map ? (Map<String, Object>) v
                    : new java.util.LinkedHashMap<>();
        } catch (Exception e) {
            throw new LabEngine.ApiException(400, "invalid JSON body: " + e.getMessage());
        }
    }

    private void send(HttpExchange ex, int status, String text, String contentType) throws IOException {
        sendBytes(ex, status, text.getBytes(StandardCharsets.UTF_8), contentType);
    }

    private void sendBytes(HttpExchange ex, int status, byte[] bytes, String contentType)
            throws IOException {
        ex.getResponseHeaders().set("content-type", contentType);
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }
}
