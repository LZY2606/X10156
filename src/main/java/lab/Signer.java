package lab;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Canonical request + HMAC-SHA256 signing.
 * The signature covers the exact bytes sent (method, path, body) plus the
 * selected headers (event id, attempt id, timestamp).
 */
public final class Signer {
    private Signer() {}

    public static final String SIGNED_HEADERS = "x-wh-attempt;x-wh-event;x-wh-timestamp";

    /** The exact bytes that would be put on the wire to the receiver. */
    public static byte[] canonicalRequestBytes(String eventId, String attemptId, long timestamp, String payload) {
        String text = "POST /webhooks/deliver HTTP/1.1\n"
                + "x-wh-attempt: " + attemptId + "\n"
                + "x-wh-event: " + eventId + "\n"
                + "x-wh-timestamp: " + timestamp + "\n"
                + "\n"
                + payload;
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** Signing input: version, timestamp, signed header list, sha256 of the sent bytes. */
    public static String signatureInput(long timestamp, byte[] sentBytes) {
        return "v1\n" + timestamp + "\n" + SIGNED_HEADERS + "\n" + sha256Hex(sentBytes);
    }

    public static String sign(String secret, String signatureInput) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return hex(mac.doFinal(signatureInput.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String sha256Hex(byte[] bytes) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        return sb.toString();
    }
}
