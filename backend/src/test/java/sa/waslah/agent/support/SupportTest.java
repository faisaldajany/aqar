package sa.waslah.agent.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import sa.waslah.agent.IntegrationTest;

/** Public support form: stored, validated, bot-filtered and rate-limited without a session. */
class SupportTest extends IntegrationTest {

    @Autowired TestRestTemplate rest;
    @Autowired JdbcClient jdbc;

    private ResponseEntity<Map> post(String ip, Map<String, Object> body) {
        HttpHeaders h = new HttpHeaders();
        h.set("X-Forwarded-For", ip);
        return rest.postForEntity("/api/public/support", new HttpEntity<>(body, h), Map.class);
    }

    private Map<String, Object> valid(String marker) {
        Map<String, Object> m = new HashMap<>();
        m.put("name", "أبو فهد");
        m.put("email", "abu@example.com");
        m.put("topic", "ربط واتساب");
        m.put("message", "ما قدرت أربط رقم المكتب " + marker);
        return m;
    }

    @Test
    void storesTheMessageWithoutASession() {
        String marker = UUID.randomUUID().toString();
        ResponseEntity<Map> r = post("10.0.0.1", valid(marker));
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(r.getBody().get("reference")).isNotNull();
        String err = jdbc.sql("select forward_error from support_messages where message like ?").param("%" + marker).query(String.class).single();
        assertThat(err).isEqualTo("mail not configured");
    }

    @Test
    void honeypotIsDroppedSilently() {
        String marker = UUID.randomUUID().toString();
        Map<String, Object> b = valid(marker);
        b.put("website", "http://spam");
        assertThat(post("10.0.0.2", b).getStatusCode().value()).isEqualTo(200);
        assertThat(jdbc.sql("select count(*) from support_messages where message like ?").param("%" + marker)
                .query(Integer.class).single()).isZero();
    }

    @Test
    void needsAWayToReply() {
        Map<String, Object> b = valid("x");
        b.remove("email");
        assertThat(post("10.0.0.3", b).getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void rateLimitedPerSender() {
        String ip = "10.9." + (int) (Math.random() * 200) + ".7";
        for (int i = 0; i < SupportService.MAX_PER_HOUR; i++) {
            assertThat(post(ip, valid("r" + i)).getStatusCode().value()).isEqualTo(200);
        }
        assertThat(post(ip, valid("over")).getStatusCode().value()).isEqualTo(429);
    }
}
