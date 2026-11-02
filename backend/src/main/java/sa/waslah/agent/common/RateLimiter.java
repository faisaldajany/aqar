package sa.waslah.agent.common;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Sliding-window limits on sign-in, sign-up, password reset, promo codes and the operator key. Kept in memory: the API
 * runs as one instance (see docs/deploy-cloud-run.md); a restart clears the counts, which only makes it more lenient.
 * These endpoints see a few requests a minute, so one lock is enough, and it makes check-and-count a single step:
 * a burst of parallel requests cannot all pass the check before any of them is counted.
 */
@Component
public class RateLimiter {

    static final Duration KEEP = Duration.ofHours(2);
    static final int MAX_PER_KEY = 1000;

    private final Map<String, Deque<Long>> hits = new HashMap<>();
    private final boolean enabled;
    private int calls;

    /** {@code waslah.rate-limits.enabled=false} only in tests, which sign up many offices from one address. */
    public RateLimiter(@Value("${waslah.rate-limits.enabled:true}") boolean enabled) {
        this.enabled = enabled;
    }

    /** Whether fewer than {@code max} hits were counted for {@code key} within {@code window}. Counts nothing. */
    public synchronized boolean allowed(String key, int max, Duration window) {
        if (!enabled) return true;
        return recent(hits.get(key), window) < max;
    }

    /** Counts a hit and returns true, or returns false (counting nothing) if {@code key} already has {@code max}. */
    public synchronized boolean tryAcquire(String key, int max, Duration window) {
        return tryAcquireAll(window, Map.of(key, max));
    }

    /** {@link #tryAcquire} for several keys at once: a hit is counted on every key, or on none if any is at its limit. */
    public synchronized boolean tryAcquireAll(Duration window, Map<String, Integer> maxByKey) {
        if (!enabled) return true;
        sweepSometimes();
        for (Map.Entry<String, Integer> e : maxByKey.entrySet()) {
            if (recent(hits.get(e.getKey()), window) >= e.getValue()) return false;
        }
        long now = System.currentTimeMillis();
        for (String key : maxByKey.keySet()) {
            Deque<Long> q = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
            q.addLast(now);
            while (q.size() > MAX_PER_KEY) q.pollFirst();
        }
        return true;
    }

    /** Takes back one hit: a request counted up front that turned out not to count (the password was right). */
    public synchronized void undo(String key) {
        Deque<Long> q = hits.get(key);
        if (q != null) q.pollLast();
    }

    /** Counts this request, or throws 429 if the key is over its limit. */
    public void check(String key, int max, Duration window) {
        if (!tryAcquire(key, max, window)) throw tooMany();
    }

    public synchronized void reset(String key) {
        hits.remove(key);
    }

    public static ApiException tooMany() {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "too_many", "محاولات كثيرة. حاول مرة أخرى بعد قليل.");
    }

    private static long recent(Deque<Long> q, Duration window) {
        if (q == null) return 0;
        long cutoff = System.currentTimeMillis() - window.toMillis();
        return q.stream().filter(t -> t >= cutoff).count();
    }

    /** Drops keys with no hit in the last two hours (longer than any window), every 500 counted requests. */
    private void sweepSometimes() {
        if (++calls % 500 != 0) return;
        long cutoff = System.currentTimeMillis() - KEEP.toMillis();
        hits.values().removeIf(q -> q.isEmpty() || q.peekLast() < cutoff);
    }
}
