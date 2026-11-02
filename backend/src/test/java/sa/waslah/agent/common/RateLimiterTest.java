package sa.waslah.agent.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
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
        limits.record("w");
        Thread.sleep(30);
        assertThat(limits.allowed("w", 1, Duration.ofMillis(10))).isTrue();
        assertThat(limits.allowed("w", 1, Duration.ofMinutes(1))).isFalse();
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
