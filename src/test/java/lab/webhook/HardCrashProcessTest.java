package lab.webhook;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spins up the real HTTP server as a child JVM and kills it via the
 * "response landed? no — halt" path, proving durability is not merely an
 * in-process exception simulation. Deliveries stay in-process; only localhost
 * control calls are made.
 */
class HardCrashProcessTest {

    private static final String JAVA = System.getProperty("java.home") + "/bin/java";
    private static final String CP = System.getProperty("java.class.path");

    private Process start(Path dataDir, int port) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(JAVA, "-cp", CP, "lab.webhook.Main",
                "--host", "127.0.0.1", "--port", String.valueOf(port),
                "--data", dataDir.toString(), "--exit-on-crash");
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.to(dataDir.resolve("server.log").toFile()));
        Process p = pb.start();
        waitForPort(port, 30);
        return p;
    }

    private void waitForPort(int port, int tries) throws Exception {
        HttpClient c = HttpClient.newHttpClient();
        for (int i = 0; i < tries * 10; i++) {
            try {
                HttpResponse<String> r = c.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/api/state")).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() == 200) return;
            } catch (Exception ignored) { }
            Thread.sleep(100);
        }
        throw new IllegalStateException("server never came up on " + port);
    }

    private HttpResponse<String> post(int port, String path, String body) throws Exception {
        HttpClient c = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body == null ? "{}" : body)).build();
        return c.send(req, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void hardExitBeforeResponsePersistedIsRecoveredOnRestart(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir);
        int port1 = 5301;
        Process p1 = start(dir, port1);
        try {
            assertEquals(200, post(port1, "/api/endpoints",
                    """
                    {"id":"ep1","secret":"s3cret","behavior":"SUCCESS",
                     "initialBackoffMillis":1000,"maxAttempts":5,"maxBackoffMillis":60000}
                    """).statusCode());
            assertEquals(200, post(port1, "/api/events",
                    """
                    {"endpointId":"ep1","eventId":"evt-hard","payload":"durable"}
                    """).statusCode());
            assertEquals(200, post(port1, "/api/admin/crash",
                    "{\"point\":\"after_send\"}").statusCode());
            // the step triggers receiver processing (fsync), then halt(3);
            // the HTTP connection is torn down before any response is written
            boolean connectionTorn = false;
            try {
                HttpResponse<String> r = post(port1, "/api/clock/step", "{}");
                connectionTorn = r.statusCode() != 200;
            } catch (java.io.IOException torn) {
                connectionTorn = true;
            }
            assertTrue(connectionTorn, "connection should be torn down by halt");
            boolean exited = p1.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(exited, "first process must hard-exit");
            assertEquals(3, p1.exitValue());
        } finally {
            p1.destroyForcibly();
        }

        int port2 = 5302;
        Process p2 = start(dir, port2);
        try {
            // recovered attempt is pending; step resends -> idempotent duplicate success
            HttpResponse<String> step = post(port2, "/api/clock/step", "{}");
            assertEquals(200, step.statusCode());
            String state = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + port2 + "/api/state")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()).body();
            Map<?, ?> s = (Map<?, ?>) Json.parse(state);
            assertEquals(1.0, ((Number) s.get("processedCount")).doubleValue());
            @SuppressWarnings("unchecked")
            java.util.List<Map<String, Object>> chains =
                    (java.util.List<Map<String, Object>>) s.get("chains");
            Map<String, Object> chain = chains.get(0);
            assertEquals("SUCCEEDED", chain.get("status"));
            @SuppressWarnings("unchecked")
            java.util.List<Map<String, Object>> attempts =
                    (java.util.List<Map<String, Object>>) chain.get("attempts");
            assertEquals("RECOVERED", attempts.get(0).get("status"));
            Map<String, Object> ok = attempts.get(1);
            assertEquals("SUCCEEDED", ok.get("status"));
            @SuppressWarnings("unchecked")
            Map<String, String> headers = (Map<String, String>) ok.get("responseHeaders");
            assertEquals("true", headers.get("x-lab-duplicate"));
        } finally {
            p2.destroyForcibly();
            p2.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
        }
    }
}
