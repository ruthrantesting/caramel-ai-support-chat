package ai.caramel.support.web;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * At most {@code limit} admissions for each key in any rolling window (research A-11). Extracted from
 * the chat rate limit so the chat and auth limits share one tested implementation. State is in process
 * memory, which matches the MVP scope.
 */
public final class SlidingWindowLimiter {

    private static final int PURGE_EVERY_N_ADMITS = 256;

    private final int limit;
    private final Duration window;
    private final Map<String, Deque<Instant>> windows = new ConcurrentHashMap<>();
    private final AtomicInteger admitsSincePurge = new AtomicInteger();

    public SlidingWindowLimiter(int limit, Duration window) {
        this.limit = limit;
        this.window = window;
    }

    /**
     * Records the request if {@code key} is under the limit. Returns 0 when admitted, or the number of
     * whole seconds (at least 1) until the key may send again.
     */
    public long admit(String key, Instant now) {
        Deque<Instant> deque = windows.computeIfAbsent(key, k -> new ArrayDeque<>());
        long retryAfterSeconds;
        synchronized (deque) {
            Instant cutoff = now.minus(window);
            while (!deque.isEmpty() && !deque.peekFirst().isAfter(cutoff)) {
                deque.pollFirst();
            }
            if (deque.size() >= limit) {
                long millisUntilFree = Duration.between(now, deque.peekFirst().plus(window)).toMillis();
                retryAfterSeconds = Math.max(1L, (millisUntilFree + 999L) / 1000L);
            } else {
                deque.addLast(now);
                retryAfterSeconds = 0L;
            }
        }
        if (admitsSincePurge.incrementAndGet() >= PURGE_EVERY_N_ADMITS) {
            admitsSincePurge.set(0);
            purgeIdle(now);
        }
        return retryAfterSeconds;
    }

    /** Drops keys with no admission inside the window, so the map cannot grow without bound. */
    void purgeIdle(Instant now) {
        Instant cutoff = now.minus(window);
        windows.entrySet().removeIf(entry -> {
            synchronized (entry.getValue()) {
                return entry.getValue().isEmpty() || !entry.getValue().peekLast().isAfter(cutoff);
            }
        });
    }
}
