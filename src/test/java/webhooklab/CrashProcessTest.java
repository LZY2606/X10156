package webhooklab;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real process-exit/restart tests: the app is launched as a separate JVM
 * that hard-halts at a named durability boundary, then re-opened to verify
 * deterministic recovery with no lost or duplicated deliveries.
 */
class CrashProcessTest {

    private String javaBin() {
        String home = System.getProperty("java.home");
        Path p = Path.of(home, "bin", isWindows() ? "java.exe" : "java");
        return p.toString();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    private String classpath() throws Exception {
        List<String> cp = new ArrayList<>();
        Path classes = Path.of("build", "classes", "java", "main").toAbsolutePath();
        assertTrue(Files.isDirectory(classes), "main classes must be built; run in a Gradle test JVM");
        cp.add(classes.toString());
        Path resources = Path.of("build", "resources", "main").toAbsolutePath();
        if (Files.isDirectory(resources)) cp.add(resources.toString());
        return String.join(System.getProperty("path.separator"), cp);
    }

    private int runApp(Path dir, String... extra) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(javaBin());
        cmd.add("-cp");
        cmd.add(classpath());
        cmd.add("webhooklab.Main");
        cmd.add("--data");
        cmd.add(dir.toAbsolutePath().toString());
        cmd.addAll(List.of(extra));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        p.getInputStream().transferTo(out);
        int code = p.waitFor();
        if (code != 77 && code != 78) {
            fail("expected hard halt 77/78 but got " + code + "\n" + out.toString(StandardCharsets.UTF_8));
        }
        return code;
    }

    private Map<String, Object> verify(Path dir) throws Exception {
        List<String> cmd = List.of(javaBin(), "-cp", classpath(), "webhooklab.Main",
                "--crash-verify", dir.toAbsolutePath().toString());
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        p.getInputStream().transferTo(out);
        assertEquals(0, p.waitFor());
        String json = out.toString(StandardCharsets.UTF_8).trim();
        return Json.parseObject(json);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> onlyChain(Map<String, Object> state) {
        List<Object> chains = (List<Object>) state.get("chains");
        assertEquals(1, chains.size());
        return (Map<String, Object>) chains.get(0);
    }

    @Test
    void crashAfterSchedulePersistedBeforeExit(@TempDir Path dir) throws Exception {
        int code = runApp(dir, "--crash-test", "schedule", "--endpoint", "ep1", "--event", "evt-sched");
        assertEquals(77, code);
        // Receiver must NOT have seen the attempt: it never reached the send.
        Map<String, Object> state = verify(dir);
        List<?> log = (List<?>) state.get("receiverLog");
        assertTrue(log.isEmpty(), "no delivery before the send boundary");
        Map<String, Object> chain = onlyChain(state);
        assertEquals("SUCCEEDED", chain.get("status"), "recovery drains to success");
        List<?> attempts = (List<?>) chain.get("attempts");
        assertEquals(1, attempts.size(), "original pending attempt simply fired after restart");
    }

    @Test
    void crashBeforeResponseIsRecoveredWithNewAttempt(@TempDir Path dir) throws Exception {
        int code = runApp(dir, "--crash-test", "response", "--endpoint", "ep1", "--event", "evt-resp");
        assertEquals(78, code);
        Map<String, Object> state = verify(dir);
        List<?> log = (List<?>) state.get("receiverLog");
        assertEquals(2, log.size(), "original delivery reached receiver; recovered retry is the 2nd contact");
        Map<String, Object> chain = onlyChain(state);
        assertEquals("SUCCEEDED", chain.get("status"));
        List<?> attempts = (List<?>) chain.get("attempts");
        assertEquals(2, attempts.size(), "in-doubt attempt + recovered retry");
        Map<String, Object> first = (Map<String, Object>) attempts.get(0);
        Map<String, Object> result = (Map<String, Object>) first.get("result");
        assertEquals("RECOVERED", result.get("outcome"));
        assertEquals("RECOVERED", result.get("nextReasonCode"));
    }
}
