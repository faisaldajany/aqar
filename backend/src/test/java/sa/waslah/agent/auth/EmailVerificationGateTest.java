package sa.waslah.agent.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import sa.waslah.agent.IntegrationTest;

/** With verification on (as in production), a new account can only reach the confirmation screen until it confirms. */
@TestPropertySource(properties = "waslah.email-verification.required=true")
class EmailVerificationGateTest extends IntegrationTest {

    @Autowired TestRestTemplate http;
    @Autowired EmailConfirmationService confirmations;
    @Autowired JdbcClient jdbc;

    ResponseEntity<Map> call(HttpMethod m, String path, Object body, String cookie) {
        HttpHeaders h = new HttpHeaders();
        h.add(HttpHeaders.COOKIE, cookie);
        h.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(path, m, new HttpEntity<>(body, h), Map.class);
    }

    @Test
    void anUnverifiedAccountSeesOnlyTheConfirmationScreen() {
        String email = "gate-" + UUID.randomUUID().toString().substring(0, 8) + "@gmail.com";
        ResponseEntity<Map> res = http.postForEntity("/api/auth/signup", Map.of("officeName", "مكتب البوابة", "falLicense", "12345678",
                "fullName", "فيصل", "email", email, "password", "password123"), Map.class);
        String cookie = res.getHeaders().get(HttpHeaders.SET_COOKIE).stream().filter(c -> c.startsWith("waslah_session="))
                .map(c -> c.split(";")[0]).findFirst().orElseThrow();

        ResponseEntity<Map> me = call(HttpMethod.GET, "/api/me", null, cookie);
        assertThat(me.getStatusCode().value()).isEqualTo(200);
        assertThat(((Map<?, ?>) me.getBody().get("user")).get("emailVerified")).isEqualTo(false);
        ResponseEntity<Map> blocked = call(HttpMethod.GET, "/api/billing", null, cookie);
        assertThat(blocked.getStatusCode().value()).isEqualTo(403);
        assertThat(blocked.getBody().get("error")).isEqualTo("email_unverified");

        assertThat(call(HttpMethod.POST, "/api/verify-email", Map.of("code", "12"), cookie).getStatusCode().value()).isEqualTo(400);
        UUID user = jdbc.sql("select id from users where email = ?").param(email).query(UUID.class).single();
        String code = confirmations.issue(user).code();
        assertThat(call(HttpMethod.POST, "/api/verify-email", Map.of("code", code), cookie).getStatusCode().value()).isEqualTo(200);

        assertThat(call(HttpMethod.GET, "/api/billing", null, cookie).getStatusCode().value()).as("verified: the portal opens").isEqualTo(200);
        assertThat(((Map<?, ?>) call(HttpMethod.GET, "/api/me", null, cookie).getBody().get("user")).get("emailVerified")).isEqualTo(true);
        assertThat(call(HttpMethod.POST, "/api/verify-email/resend", null, cookie).getBody()).containsEntry("sent", false);
    }
}
