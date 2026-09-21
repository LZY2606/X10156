package webhooklab;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RecoveryTest {

    @Test
    void inDoubtAttemptAfterCrashIsRetriedAndSucceeds(@TempDir Path dir) {
        String chainId;
        try (LabHarness h = new LabHarness(dir)) {
            h.endpoint("ep", "success", 100, 1000, 1, 5);
            chainId = h.send("evt-crash1", "ep", "{}");
            // Simulate: sent marker persisted, then process dies before result.
            h.crashBeforeResultOnAttempt("att-1");
            assertThrows(LabHarness.CrashBeforeResult.class, () -> h.engine.stepNext());
        }
        // Fresh process opens the same data directory.
        try (LabHarness h2 = new LabHarness(dir)) {
            Model.Attempt old = h2.store.attempts.get("att-1");
            assertNotNull(old.sentAt(), "sent marker survived the crash");
            // recoverInDoubtAttempts already ran in the constructor: the old
            // attempt is marked outcome-unknown and a fresh retry is scheduled.
            List<Model.Attempt> scheduled = h2.attempts(chainId);
            assertEquals(2, scheduled.size());
            assertEquals("RECOVERED", scheduled.get(1).reasonCode());
            assertNotEquals("att-1", scheduled.get(1).id());
            h2.drain();
            assertEquals(Model.ChainStatus.SUCCEEDED, h2.chain(chainId).status());
            // Old attempt never mutated; a RECOVERED result now exists for it.
            Model.AttemptResult oldResult = h2.resultOf("att-1");
            assertEquals("RECOVERED", oldResult.outcome());
            assertEquals(scheduled.get(1).id().substring(4), String.valueOf(oldResult.nextAttemptId()));
        }
    }

    @Test
    void nothingAutoFiresOnRestartBeforeStepping(@TempDir Path dir) {
        try (LabHarness h = new LabHarness(dir)) {
            h.endpoint("ep", "success", 100, 1000, 1, 5);
            h.send("evt-wait", "ep", "{}");
        }
        try (LabHarness h2 = new LabHarness(dir)) {
            assertTrue(h2.store.receiverLog.isEmpty(), "restart must not auto-deliver");
            // scheduled attempt still pending at its original time
            assertEquals(1, h2.store.pendingAttemptIds.size());
            h2.drain();
            assertEquals(1, h2.store.receiverLog.size());
        }
    }

    @Test
    void tornJournalTailIsIgnored(@TempDir Path dir) throws Exception {
        try (LabHarness h = new LabHarness(dir)) {
            h.endpoint("ep", "success", 100, 1000, 1, 5);
            h.send("evt-torn", "ep", "{}");
        }
        Path journal = dir.resolve("journal.log");
        byte[] good = java.nio.file.Files.readAllBytes(journal);
        // Simulate a torn tail write: corrupt the final 20 bytes of the last
        // complete line (checksum then fails and the line is ignored).
        byte[] damaged = good.clone();
        for (int i = damaged.length - 20; i < damaged.length; i++) damaged[i] = (byte) 'X';
        java.nio.file.Files.write(journal, damaged);

        try (Store store = Store.open(dir)) {
            assertNotNull(store.endpoints.get("ep"));
            assertNotNull(store.events.get("evt-torn"));
            assertNotNull(store.chains.get("chain-1"));
            // The torn attempt line is dropped: nothing to fire.
            assertEquals(0, store.pendingAttemptIds.size());
        }
    }

    @Test
    void inDoubtWithIdempotentReceiverDoesNotDuplicate(@TempDir Path dir) {
        String chainId;
        try (LabHarness h = new LabHarness(dir)) {
            h.endpoint("ep", "lost", 100, 1000, 1, 5);
            chainId = h.send("evt-lostcrash", "ep", "{}");
            h.crashBeforeResultOnAttempt("att-1");
            assertThrows(LabHarness.CrashBeforeResult.class, () -> h.engine.stepNext());
        }
        try (LabHarness h2 = new LabHarness(dir)) {
            h2.drain();
            assertEquals(Model.ChainStatus.SUCCEEDED, h2.chain(chainId).status());
            boolean sawDuplicate = false;
            for (Model.ReceiverEntry e : h2.store.receiverLog) {
                if (e.duplicate()) sawDuplicate = true;
            }
            assertTrue(sawDuplicate, "recovered retry should hit the idempotency mark");
        }
    }

    @Test
    void restartKeepsDeterministicSchedule(@TempDir Path dir) throws Exception {
        String chainId;
        try (LabHarness h = new LabHarness(dir)) {
            h.endpoint("ep", "status:500", 100, 1000, 7, 4);
            chainId = h.send("evt-restart", "ep", "{}");
            h.engine.stepNext(); // attempt 1 fails at t=0; attempt 2 scheduled
        }
        long scheduledBefore;
        try (Store s = Store.open(dir)) {
            scheduledBefore = s.attempts.get("att-2").scheduledAt();
        }
        try (LabHarness h2 = new LabHarness(dir)) {
            assertEquals(scheduledBefore, h2.store.attempts.get("att-2").scheduledAt());
            h2.drain();
            assertEquals(Model.ChainStatus.DEAD, h2.chain(chainId).status());
        }
    }
}
