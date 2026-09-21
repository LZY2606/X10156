package webhooklab;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

/**
 * Entry point.
 *
 * Normal:
 *   --host 127.0.0.1 --port 5232 [--data ./data]
 *
 * Crash/restart test modes (the process halts immediately at the named point,
 * after the durable record has been fsync'd):
 *   --crash-test schedule|response --data DIR --endpoint E --event V
 *   --crash-verify DATA_DIR [--advance N]
 */
public final class Main {

    private static String host = "127.0.0.1";
    private static int port = 5232;
    private static Path dataDir = Path.of("data");
    private static String crashTest = null;
    private static String crashEndpoint = "ep1";
    private static String crashEvent = "evt-crash";
    private static String crashVerify = null;
    private static long verifyAdvance = Long.MAX_VALUE;

    public static void main(String[] args) throws Exception {
        parseArgs(args);

        if (crashVerify != null) {
            runVerify();
            return;
        }

        Store store = Store.open(dataDir);
        Receiver receiver = new Receiver(store);
        Engine engine = new Engine(store, receiver);
        engine.recoverInDoubtAttempts();

        if (crashTest != null) {
            runCrashTest(engine);
            return; // halt happens inside
        }

        WebServer server = new WebServer(engine, host, port);
        server.start();
        System.out.println("Webhook 投递实验室已启动: http://" + host + ":" + server.port());
        System.out.println("数据目录: " + dataDir.toAbsolutePath());
        Thread.currentThread().join();
    }

    private static void runCrashTest(Engine engine) {
        Map<String, Object> ep = Json.obj();
        ep.put("id", crashEndpoint);
        ep.put("secret", "test-secret");
        ep.put("maxAttempts", 5);
        ep.put("baseBackoffMs", 100);
        ep.put("maxBackoffMs", 60000);
        ep.put("jitterSeed", 7);
        ep.put("timeoutMs", 1000);
        ep.put("behavior", crashTest.equals("response") ? "success" : "success");
        ep.put("paused", false);
        engine.upsertEndpoint(ep);

        if (crashTest.equals("schedule")) {
            engine.setCrashHook(new Engine.HardExitHook(Engine.CrashPoint.AFTER_SCHEDULE));
        } else {
            engine.setCrashHook(new Engine.HardExitHook(Engine.CrashPoint.BEFORE_RESULT));
        }

        Map<String, Object> ev = Json.obj();
        ev.put("eventId", crashEvent);
        ev.put("endpointId", crashEndpoint);
        ev.put("payload", "{\"hello\":\"crash\"}");
        engine.sendEvent(ev);

        if (crashTest.equals("response")) {
            // scheduled with delay 0; fire it to reach the crash point
            engine.stepNext();
        }
        System.err.println("crash hook did not fire");
        Runtime.getRuntime().halt(1);
    }

    private static void runVerify() throws Exception {
        Store store = Store.open(Path.of(crashVerify));
        Receiver receiver = new Receiver(store);
        Engine engine = new Engine(store, receiver);
        engine.recoverInDoubtAttempts();
        // Drain all pending work in virtual time.
        int guard = 0;
        while (!store.pendingAttemptIds.isEmpty() && guard++ < 1000) {
            engine.stepNext();
            boolean allParked = true;
            for (String id : store.pendingAttemptIds) {
                Model.Attempt a = store.attempts.get(id);
                Model.Endpoint ep = a != null ? store.endpoints.get(a.endpointId()) : null;
                if (ep != null && !ep.paused()) { allParked = false; break; }
            }
            if (allParked) break;
        }
        System.out.write(Json.of(StateView.full(store)).getBytes(StandardCharsets.UTF_8));
        System.out.println();
        store.close();
    }

    private static void parseArgs(String[] args) {
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--host" -> host = args[++i];
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--data" -> dataDir = Path.of(args[++i]);
                case "--crash-test" -> crashTest = args[++i];
                case "--crash-endpoint" -> crashEndpoint = args[++i];
                case "--crash-event" -> crashEvent = args[++i];
                case "--crash-verify" -> crashVerify = args[++i];
                default -> {
                    if (args[i].startsWith("--crash-verify=")) crashVerify = args[i].substring("--crash-verify=".length());
                    else throw new IllegalArgumentException("unknown argument: " + args[i]);
                }
            }
        }
    }
}
