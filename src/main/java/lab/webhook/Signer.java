package lab.webhook;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Builds the canonical signing input and computes HMAC-SHA256 signatures.
 *
 * Canonical signing input (exact bytes):
 *   <method>\n
 *   <path>\n
 *   <sha256-hex of body bytes>\n
 *   <sorted lowercase "name: value" lines>\n
 *   <eventId>\n
 *   <attemptId>\n
 *   <unix millis timestamp>
 *
 * The covered headers are the transport headers carrying delivery metadata;
 * the signature header itself is never part of the signed input.
 */
public final class Signer {

    public static final String HEADER_SIGNATURE = "x-lab-signature";
    public static final String HEADER_KEY_ID = "x-lab-key-id";
    public static final String HEADER_EVENT_ID = "x-lab-event-id";
    public static final String HEADER_ATTEMPT_ID = "x-lab-attempt-id";
    public static final String HEADER_TIMESTAMP = "x-lab-timestamp";

    public static final List<String> COVERED_HEADERS = List.of(
            HEADER_KEY_ID, HEADER_EVENT_ID, HEADER_ATTEMPT_ID, HEADER_TIMESTAMP, "content-type");

    private Signer() {}

    public record Signed(String signingInput, String signature) {}

    public static String sha256Hex(byte[] body) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(body);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String canonicalInput(String method, String path, byte[] body,
                                        Map<String, String> headers, List<String> covered,
                                        String eventId, String attemptId, long timestamp) {
        TreeMap<String, String> signed = new TreeMap<>();
        Map<String, String> lower = new LinkedHashMap<>();
        headers.forEach((k, v) -> lower.merge(k.toLowerCase(), v, (a, b) -> a + ", " + b));
        for (String name : covered) {
            String n = name.toLowerCase();
            String v = lower.get(n);
            if (v != null) signed.put(n, v.trim());
        }
        StringBuilder sb = new StringBuilder();
        sb.append(method).append('\n');
        sb.append(path).append('\n');
        sb.append(sha256Hex(body)).append('\n');
        for (Map.Entry<String, String> e : signed.entrySet()) {
            sb.append(e.getKey()).append(": ").append(e.getValue()).append('\n');
        }
        sb.append(eventId).append('\n');
        sb.append(attemptId).append('\n');
        sb.append(timestamp);
        return sb.toString();
    }

    public static Signed sign(String method, String path, byte[] body, Map<String, String> headers,
                              List<String> covered, String eventId, String attemptId,
                              long timestamp, String secret) {
        String input = canonicalInput(method, path, body, headers, covered, eventId, attemptId, timestamp);
        return new Signed(input, hmac(input, secret));
    }

    public static String hmac(String input, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal(input.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean verify(String method, String path, byte[] body, Map<String, String> headers,
                                 List<String> covered, String eventId, String attemptId, long timestamp,
                                 String secret, String expectedSignature) {
        String input = canonicalInput(method, path, body, headers, covered, eventId, attemptId, timestamp);
        String actual = hmac(input, secret);
        return constantTimeEquals(actual, expectedSignature == null ? "" : expectedSignature);
    }

    private static boolean constantTimeEquals(String a, String b) {
        byte[] x = a.getBytes(StandardCharsets.UTF_8);
        byte[] y = b.getBytes(StandardCharsets.UTF_8);
        int diff = x.length ^ y.length;
        for (int i = 0; i < Math.min(x.length, y.length); i++) diff |= x[i] ^ y[i];
        return diff == 0;
    }

    public static List<String> coveredHeaderNames(String coveredCsv) {
        if (coveredCsv == null || coveredCsv.isBlank()) return new ArrayList<>(COVERED_HEADERS);
        List<String> out = new ArrayList<>();
        for (String part : coveredCsv.split(",")) {
            String n = part.trim().toLowerCase();
            if (!n.isEmpty() && !n.equals(HEADER_SIGNATURE)) out.add(n);
        }
        return out;
    }
}
