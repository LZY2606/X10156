package webhooklab;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * HMAC-SHA256 request signing.
 *
 * The signature covers the exact body bytes being sent (via their SHA-256,
 * carried in the signed x-webhook-content-sha256 header) plus the selected
 * request headers. The signature input is a canonical string:
 *
 *   v1\n
 *   &lt;attemptId&gt;\n
 *   &lt;timestampMs&gt;\n
 *   &lt;lowercase-header-name&gt;:&lt;trimmed-value&gt;\n   (sorted by name)
 *   &lt;lowercase-header-name&gt;:&lt;trimmed-value&gt;
 */
public final class Signer {

    public static final String VERSION = "v1";
    public static final String HDR_EVENT = "x-webhook-event-id";
    public static final String HDR_ATTEMPT = "x-webhook-attempt-id";
    public static final String HDR_CHAIN = "x-webhook-chain-id";
    public static final String HDR_TIMESTAMP = "x-webhook-timestamp";
    public static final String HDR_DIGEST = "x-webhook-content-sha256";
    public static final String HDR_SIGNATURE = "x-webhook-signature";
    public static final String HDR_SIGNED_HEADERS = "x-webhook-signed-headers";
    public static final String HDR_CONTENT_TYPE = "content-type";

    private Signer() {}

    public static String sha256Hex(byte[] body) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(body);
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String hmacBase64(String secret, String input) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Default set of headers covered by the signature. */
    public static List<String> defaultSignedHeaders() {
        return List.of(HDR_EVENT, HDR_ATTEMPT, HDR_CHAIN, HDR_TIMESTAMP, HDR_DIGEST, HDR_CONTENT_TYPE);
    }

    /**
     * Builds the canonical signature input from the selected headers.
     * Header names are lower-cased and sorted; values are trimmed.
     */
    public static String signatureInput(String attemptId, long timestampMs, Map<String, String> headers,
                                        List<String> signedHeaderNames) {
        TreeMap<String, String> sorted = new TreeMap<>();
        for (Map.Entry<String, String> e : headers.entrySet()) {
            sorted.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue());
        }
        // Header lines are emitted in sorted header-name order (the signed
        // header *list* selects which headers are covered; it never changes
        // the canonical order).
        List<String> selected = new ArrayList<>();
        for (String name : signedHeaderNames) {
            String key = name.toLowerCase(Locale.ROOT);
            if (sorted.containsKey(key)) selected.add(key);
        }
        Collections.sort(selected);
        StringBuilder sb = new StringBuilder();
        sb.append(VERSION).append('\n');
        sb.append(attemptId).append('\n');
        sb.append(timestampMs);
        for (String key : selected) {
            sb.append('\n').append(key).append(':').append(sorted.get(key).trim());
        }
        return sb.toString();
    }

    /** Builds the full set of request headers for an attempt (deterministic order). */
    public static Map<String, String> buildHeaders(Model.Attempt attempt, long timestampMs, String bodySha256) {
        Map<String, String> h = new java.util.LinkedHashMap<>();
        h.put(HDR_EVENT, attempt.eventId());
        h.put(HDR_ATTEMPT, attempt.id());
        h.put(HDR_CHAIN, attempt.chainId());
        h.put(HDR_TIMESTAMP, String.valueOf(timestampMs));
        h.put(HDR_DIGEST, bodySha256);
        h.put(HDR_CONTENT_TYPE, attempt.contentType());
        return h;
    }

    public static boolean verify(String secret, String signatureInput, String presentedSignature) {
        if (presentedSignature == null) return false;
        String expected = hmacBase64(secret, signatureInput);
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                presentedSignature.getBytes(StandardCharsets.UTF_8));
    }

    public static List<String> parseSignedHeaders(String csv) {
        List<String> out = new ArrayList<>();
        if (csv == null) return out;
        for (String p : csv.split(",")) {
            String t = p.trim().toLowerCase(Locale.ROOT);
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }
}
