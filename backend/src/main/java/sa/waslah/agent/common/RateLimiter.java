package sa.waslah.agent.common;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Sliding-window limits on sign-in, sign-up, password reset and the operator login. Kept in memory: the API runs as
 * one instance (see docs/deploy-cloud-run.md); a restart clears the counts, which only makes it more lenient.
 */
@Component
public class RateLimiter {

    static final Duration KEEP = Duration.ofHours(2);

    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();
    private final AtomicInteger calls = new AtomicInteger();
    private final boolean enabled;

    /** {@code waslah.rate-limits.enabled=false} only in tests, which sign up many offices from one address. */
    public RateLimiter(@Value("${waslah.rate-limits.enabled:true}") boolean enabled) {
        this.enabled = enabled;
    }

    /** Whether fewer than {@code max} hits were recorded for {@code key} within {@code window}. Records nothing. */
    public boolean allowed(String key, int max, Duration window) {
        if (!enabled) return true;
        sweepSometimes();
        Deque<Long> q = hits.get(key);
        if (q == null) return true;
        long cutoff = System.currentTimeMillis() - window.toMillis();
        synchronized (q) {
            return q.stream().filter(t -> t >= cutoff).count() < max;
        }
    }

    public void record(String key) {
        Deque<Long> q = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (q) {
            q.addLast(System.currentTimeMillis());
            while (q.size() > 1000) q.pollFirst();
        }
    }

    /** Counts this request and throws 429 if the key is over its limit. */
    public void check(String key, int max, Duration window) {
        if (!allowed(key, max, window)) throw tooMany();
        record(key);
    }

    public void reset(String key) {
        hits.remove(key);
    }

    public static ApiException tooMany() {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "too_many", "محاولات كثيرة. حاول مرة أخرى بعد قليل.");
    }

    private void sweepSometimes() {
        if (calls.incrementAndGet() % 500 != 0) return;
        long cutoff = System.currentTimeMillis() - KEEP.toMillis();
        hits.entrySet().removeIf(e -> {
            synchronized (e.getValue()) {
                return e.getValue().isEmpty() || e.getValue().peekLast() < cutoff;
            }
        });
    }
}
