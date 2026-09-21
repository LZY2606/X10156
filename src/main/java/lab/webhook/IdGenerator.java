package lab.webhook;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** Generates short, collision-resistant ids for endpoints, chains and attempts. */
public final class IdGenerator {

    private final AtomicLong counter = new AtomicLong();

    public String newId(String prefix) {
        String uuid = UUID.randomUUID().toString().substring(0, 12);
        return prefix + "_" + uuid + "_" + counter.incrementAndGet();
    }
}
