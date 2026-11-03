package sa.waslah.agent.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class RateLimiterTest {

    @Test
    void blocksAfterTheLimitAndResets() {
        RateLimiter limits = new RateLimiter(true);
        for (int i = 0; i < 3; i++) limits.check("k", 3, Duration.ofMinutes(1));
        assertThatThrownBy(() -> limits.check("k", 3, Duration.ofMinutes(1)))
                .isInstanceOf(ApiException.class).hasMessageContaining("محاولات كثيرة");
        assertThat(limits.allowed("other", 3, Duration.ofMinutes(1))).isTrue();
        limits.reset("k");
        assertThat(limits.allowed("k", 3, Duration.ofMinutes(1))).isTrue();
    }

    @Test
    void oldHitsFallOutOfTheWindow() throws Exception {
        RateLimiter limits = new RateLimiter(true);
        assertThat(limits.tryAcquire("w", 5, Duration.ofMinutes(1))).isTrue();
        Thread.sleep(30);
        assertThat(limits.allowed("w", 1, Duration.ofMillis(10))).isTrue();
        assertThat(limits.allowed("w", 1, Duration.ofMinutes(1))).isFalse();
    }

    @Test
    void aBurstOfParallelRequestsCannotOvershootTheLimit() throws Exception {
        RateLimiter limits = new RateLimiter(true);
        int n = 32;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger passed = new AtomicInteger();
        List<Future<?>> all = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            all.add(pool.submit(() -> {
                go.await();
                if (limits.tryAcquire("burst", 5, Duration.ofMinutes(1))) passed.incrementAndGet();
                return null;
            }));
        }
        go.countDown();
        for (Future<?> f : all) f.get();
        pool.shutdown();
        assertThat(passed.get()).isEqualTo(5);
    }

    @Test
    void severalKeysAreCountedTogetherOrNotAtAll() {
        RateLimiter limits = new RateLimiter(true);
        Duration w = Duration.ofMinutes(1);
        assertThat(limits.tryAcquire("ip", 1, w)).isTrue();
        assertThat(limits.tryAcquireAll(w, Map.of("email", 5, "ip", 1))).isFalse();
        assertThat(limits.allowed("email", 1, w)).as("nothing counted when one key is full").isTrue();
        limits.undo("ip");
        assertThat(limits.tryAcquireAll(w, Map.of("email", 5, "ip", 1))).isTrue();
        assertThat(limits.allowed("email", 1, w)).isFalse();
    }

    @Test
    void ipv6AddressesAreGroupedByTheirSlash64() {
        assertThat(ClientIp.group("2001:db8:1:2:aaaa::1")).isEqualTo(ClientIp.group("2001:db8:1:2:ffff:1:2:3"))
                .isEqualTo("20010db800010002/64");
        assertThat(ClientIp.group("2001:db8:1:3::1")).isNotEqualTo(ClientIp.group("2001:db8:1:2::1"));
        assertThat(ClientIp.group("[2001:db8::1]")).isEqualTo("20010db800000000/64");
        assertThat(ClientIp.group("::ffff:1.2.3.4")).isEqualTo("1.2.3.4");
        assertThat(ClientIp.group("5.6.7.8")).isEqualTo("5.6.7.8");
        assertThat(ClientIp.group("not-an-address")).isEqualTo("not-an-address");
        MockHttpServletRequest a = new MockHttpServletRequest();
        MockHttpServletRequest b = new MockHttpServletRequest();
        a.addHeader("X-Forwarded-For", "2001:db8:1:2::10, 35.191.0.1");
        b.addHeader("X-Forwarded-For", "2001:db8:1:2:9::99, 35.191.0.1");
        assertThat(ClientIp.hash(a, "s")).isEqualTo(ClientIp.hash(b, "s"));
    }

    @Test
    void disabledAllowsEverything() {
        RateLimiter limits = new RateLimiter(false);
        for (int i = 0; i < 10; i++) limits.check("k", 1, Duration.ofMinutes(1));
    }

    @Test
    void clientIpIgnoresForgedForwardedEntries() {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.setRemoteAddr("10.0.0.9");
        assertThat(ClientIp.of(r)).isEqualTo("10.0.0.9");
        r.addHeader("X-Forwarded-For", "1.2.3.4");
        assertThat(ClientIp.of(r)).as("single entry: set by our own proxy").isEqualTo("1.2.3.4");
        MockHttpServletRequest lb = new MockHttpServletRequest();
        lb.addHeader("X-Forwarded-For", "6.6.6.6, 5.5.5.5, 35.191.0.1");
        assertThat(ClientIp.of(lb)).as("Google LB appends client, then itself").isEqualTo("5.5.5.5");
        assertThat(ClientIp.hash(lb, "s")).hasSize(24).isEqualTo(ClientIp.hash(lb, "s"));
    }
}
