package webhooklab;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SignerTest {

    @Test
    void signatureInputIsCanonicalAndSorted() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("X-Webhook-Timestamp", "1700000000000");
        h.put("x-webhook-event-id", "evt-1");
        h.put("x-webhook-attempt-id", "att-3");
        h.put("x-webhook-chain-id", "chain-1");
        h.put("x-webhook-content-sha256", "abc");
        h.put("content-type", "application/json");

        String input = Signer.signatureInput("att-3", 1700000000000L, h, List.of(
                "x-webhook-event-id", "x-webhook-attempt-id", "x-webhook-chain-id",
                "x-webhook-timestamp", "x-webhook-content-sha256", "content-type"));

        assertEquals("""
                v1
                att-3
                1700000000000
                content-type:application/json
                x-webhook-attempt-id:att-3
                x-webhook-chain-id:chain-1
                x-webhook-content-sha256:abc
                x-webhook-event-id:evt-1
                x-webhook-timestamp:1700000000000""", input);
    }

    @Test
    void signatureCoversExactBodyBytes() {
        // Same logical JSON, different bytes -> different digest -> different signature input.
        byte[] bodyA = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
        byte[] bodyB = "{ \"a\": 1 }".getBytes(StandardCharsets.UTF_8);
        String digestA = Signer.sha256Hex(bodyA);
        String digestB = Signer.sha256Hex(bodyB);
        assertNotEquals(digestA, digestB);

        Map<String, String> hA = headers("att-1", 1000, digestA);
        String sigA = Signer.hmacBase64("s3cr3t",
                Signer.signatureInput("att-1", 1000, hA, Signer.defaultSignedHeaders()));
        Map<String, String> hB = headers("att-1", 1000, digestB);
        String sigB = Signer.hmacBase64("s3cr3t",
                Signer.signatureInput("att-1", 1000, hB, Signer.defaultSignedHeaders()));
        assertNotEquals(sigA, sigB);

        // Known-answer check for the canonical string.
        String expected = "v1\natt-1\n1000\ncontent-type:application/json\n"
                + "x-webhook-attempt-id:att-1\nx-webhook-chain-id:ch\nx-webhook-content-sha256:" + digestA
                + "\nx-webhook-event-id:ev\nx-webhook-timestamp:1000";
        assertEquals(expected, Signer.signatureInput("att-1", 1000, hA, Signer.defaultSignedHeaders()));
        assertTrue(Signer.verify("s3cr3t", expected, sigA));
        assertFalse(Signer.verify("s3cr3t", expected, sigA + "x"));
        assertFalse(Signer.verify("wrong", expected, sigA));
    }

    private static Map<String, String> headers(String attempt, long ts, String digest) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put(Signer.HDR_EVENT, "ev");
        h.put(Signer.HDR_ATTEMPT, attempt);
        h.put(Signer.HDR_CHAIN, "ch");
        h.put(Signer.HDR_TIMESTAMP, String.valueOf(ts));
        h.put(Signer.HDR_DIGEST, digest);
        h.put(Signer.HDR_CONTENT_TYPE, "application/json");
        return h;
    }
}
