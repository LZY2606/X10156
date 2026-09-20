package lab;

import lab.Model.Attempt;
import lab.Model.Endpoint;
import lab.Model.ReceiverMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SignatureTest {
    @TempDir Path dir;

    @Test
    void signatureCoversExactSentBytesAndSelectedHeaders() {
        byte[] sent = Signer.canonicalRequestBytes("evt-1", "at-1", 1_700_000_000_000L, "{\"a\":1}");
        assertEquals("POST /webhooks/deliver HTTP/1.1\n"
                + "x-wh-attempt: at-1\nx-wh-event: evt-1\nx-wh-timestamp: 1700000000000\n\n{\"a\":1}",
                new String(sent, java.nio.charset.StandardCharsets.UTF_8));
        String input = Signer.signatureInput(1_700_000_000_000L, sent);
        assertEquals("v1\n1700000000000\nx-wh-attempt;x-wh-event;x-wh-timestamp\n"
                + "2c26dad3107dfe39cea06a99ee91b4c50d3c648b7cfce567702d74b220862668", input);
        // Golden HMAC-SHA256 (computed independently) over the exact signature input bytes.
        assertEquals("c75ba3b179f73bea9ebecb848108766e486402432ddb63b0a422d9bc60f5a23f",
                Signer.sign("topsecret", input));
    }

    @Test
    void storedAttemptVerifiesAgainstEndpointSecret() {
        Lab lab = new Lab(dir, 7L);
        Endpoint ep = lab.createEndpoint("wh", "topsecret", 3, ReceiverMode.OK, "1");
        lab.sendEvent(ep.id, "evt-1", "{\"a\":1}");
        Attempt a = lab.state.attempts.values().iterator().next();
        assertEquals("SUCCESS", a.status);
        Map<String, Object> v = lab.verifyAttempt(a.attemptId);
        assertEquals(Boolean.TRUE, v.get("valid"));
        assertEquals(a.signature, v.get("expected"));
        // Tampering with the stored bytes breaks verification.
        a.signatureInput = a.signatureInput.replace("v1", "v2");
        assertEquals(Boolean.FALSE, lab.verifyAttempt(a.attemptId).get("valid"));
    }
}
