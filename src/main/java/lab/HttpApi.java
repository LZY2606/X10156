package lab;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import lab.Model.Endpoint;
import lab.Model.ReceiverMode;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** JSON API + single-page UI. Deliveries only ever go to the in-process receiver; no outbound URLs exist. */
public final class HttpApi {
    private final Lab lab;
    private HttpServer server;

    public HttpApi(Lab lab) {
        this.lab = lab;
    }

    public void start(String host, int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.createContext("/", this::handle);
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public void stop() {
        if (server != null) server.stop(0);
    }

    private void handle(HttpExchange ex) throws IOException {
        try {
            route(ex);
        } catch (IllegalArgumentException e) {
            send(ex, 400, errorJson(e.getMessage()), "application/json");
        } catch (Exception e) {
            send(ex, 500, errorJson(String.valueOf(e)), "application/json");
        } finally {
            ex.close();
        }
    }

    private static String errorJson(String message) {
        Map<String, Object> m = Json.obj();
        m.put("error", message);
        return Json.write(m);
    }

    private void route(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath();

        if ("GET".equals(method) && "/".equals(path)) {
            byte[] html;
            try (InputStream in = HttpApi.class.getResourceAsStream("/index.html")) {
                html = in.readAllBytes();
            }
            send(ex, 200, html, "text/html; charset=utf-8");
            return;
        }
        if ("GET".equals(method) && "/api/state".equals(path)) {
            sendJson(ex, lab.snapshot());
            return;
        }
        if ("POST".equals(method) && "/api/endpoints".equals(path)) {
            Map<String, Object> b = body(ex);
            Endpoint e = lab.createEndpoint(
                    Json.str(b, "name"),
                    Json.str(b, "secret"),
                    (int) Json.num(b, "maxAttempts"),
                    ReceiverMode.valueOf(Json.str(b, "mode")),
                    Json.str(b, "retryAfter"));
            sendJson(ex, e.toJson());
            return;
        }
        Matcher ep = Pattern.compile("^/api/endpoints/([^/]+)$").matcher(path);
        if ("POST".equals(method) && ep.matches()) {
            Map<String, Object> b = body(ex);
            Endpoint e = lab.updateEndpoint(ep.group(1),
                    Json.str(b, "name"), Json.str(b, "secret"),
                    b.get("maxAttempts") == null ? null : (int) Json.num(b, "maxAttempts"),
                    b.get("mode") == null ? null : ReceiverMode.valueOf(Json.str(b, "mode")),
                    Json.str(b, "retryAfter"));
            sendJson(ex, e.toJson());
            return;
        }
        Matcher pause = Pattern.compile("^/api/endpoints/([^/]+)/(pause|resume)$").matcher(path);
        if ("POST".equals(method) && pause.matches()) {
            lab.setPaused(pause.group(1), "pause".equals(pause.group(2)));
            sendJson(ex, lab.snapshot());
            return;
        }
        if ("POST".equals(method) && "/api/events".equals(path)) {
            Map<String, Object> b = body(ex);
            Lab.SendResult r = lab.sendEvent(Json.str(b, "endpointId"), Json.str(b, "eventId"), Json.str(b, "payload"));
            Map<String, Object> out = Json.obj();
            out.put("duplicate", r.duplicate);
            out.put("chainId", r.chainId);
            sendJson(ex, out);
            return;
        }
        if ("POST".equals(method) && "/api/clock/step".equals(path)) {
            Map<String, Object> b = body(ex);
            lab.stepClock(Json.num(b, "seconds"));
            sendJson(ex, lab.snapshot());
            return;
        }
        if ("POST".equals(method) && "/api/clock/next".equals(path)) {
            lab.stepToNext();
            sendJson(ex, lab.snapshot());
            return;
        }
        Matcher replay = Pattern.compile("^/api/chains/([^/]+)/replay$").matcher(path);
        if ("POST".equals(method) && replay.matches()) {
            sendJson(ex, lab.replay(replay.group(1)).toJson());
            return;
        }
        Matcher verify = Pattern.compile("^/api/attempts/([^/]+)/verify$").matcher(path);
        if ("GET".equals(method) && verify.matches()) {
            sendJson(ex, lab.verifyAttempt(verify.group(1)));
            return;
        }
        send(ex, 404, "{\"error\":\"not found\"}", "application/json");
    }

    private Map<String, Object> body(HttpExchange ex) throws IOException {
        String text = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (text.isBlank()) return Json.obj();
        return Json.readObject(text);
    }

    private void sendJson(HttpExchange ex, Object value) throws IOException {
        send(ex, 200, Json.write(value), "application/json");
    }

    private void send(HttpExchange ex, int status, String body, String contentType) throws IOException {
        send(ex, status, body.getBytes(StandardCharsets.UTF_8), contentType);
    }

    private void send(HttpExchange ex, int status, byte[] body, String contentType) throws IOException {
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }
}
