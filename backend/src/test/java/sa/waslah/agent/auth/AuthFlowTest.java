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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import sa.waslah.agent.IntegrationTest;

class AuthFlowTest extends IntegrationTest {

    @Autowired TestRestTemplate http;

    @Test
    void signupThenMeWithCookie() {
        String email = "owner" + UUID.randomUUID().toString().substring(0, 8) + "@test.sa";
        ResponseEntity<Map> res = http.postForEntity("/api/auth/signup", Map.of(
                "officeName", "مكتب الأفق العقاري", "falLicense", "1200014587",
                "fullName", "فيصل", "email", email, "password", "password123"), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        String cookie = res.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertThat(cookie).contains("waslah_session=").contains("HttpOnly");

        HttpHeaders h = new HttpHeaders();
        h.add(HttpHeaders.COOKIE, cookie.split(";")[0]);
        ResponseEntity<Map> me = http.exchange("/api/me", HttpMethod.GET, new HttpEntity<>(h), Map.class);
        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Map<?, ?>) me.getBody().get("office")).get("name")).isEqualTo("مكتب الأفق العقاري");
        assertThat(((Map<?, ?>) me.getBody().get("whatsapp")).get("connected")).isEqualTo(false);
    }

    @Test
    void meWithoutSessionIsRejected() {
        assertThat(http.getForEntity("/api/me", String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void duplicateEmailAndWrongPassword() {
        String email = "dup" + UUID.randomUUID().toString().substring(0, 8) + "@test.sa";
        Map<String, String> body = Map.of("officeName", "مكتب", "falLicense", "12345678",
                "fullName", "مالك", "email", email, "password", "password123");
        assertThat(http.postForEntity("/api/auth/signup", body, String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(http.postForEntity("/api/auth/signup", body, String.class).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(http.postForEntity("/api/auth/login", Map.of("email", email, "password", "wrong-password"), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(http.postForEntity("/api/auth/login", Map.of("email", email, "password", "password123"), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void invalidSignupIsBadRequest() {
        ResponseEntity<String> res = http.postForEntity("/api/auth/signup", Map.of(
                "officeName", "", "falLicense", "abc", "fullName", "x", "email", "not-an-email", "password", "short"), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
