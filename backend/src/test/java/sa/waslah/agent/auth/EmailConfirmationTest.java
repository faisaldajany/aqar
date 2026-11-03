package sa.waslah.agent.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import sa.waslah.agent.IntegrationTest;
import sa.waslah.agent.common.ApiException;

/** A company email gets a one-time confirmation link at signup; other emails get nothing. */
class EmailConfirmationTest extends IntegrationTest {

    @Autowired TestRestTemplate http;
    @Autowired EmailConfirmationService confirmations;
    @Autowired JdbcClient jdbc;

    UUID signup(String email) {
        Map<?, ?> body = http.postForEntity("/api/auth/signup", Map.of("officeName", "مكتب " + email, "falLicense", "12345678",
                "fullName", "فيصل", "email", email, "password", "password123"), Map.class).getBody();
        return jdbc.sql("select id from users where email = ?").param(email.toLowerCase()).query(UUID.class).single();
    }

    long links(UUID user) {
        return jdbc.sql("select count(*) from email_confirmations where user_id = ?").param(user).query(Long.class).single();
    }

    @Test
    void companyEmailIsConfirmedByItsLinkOnce() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        UUID team = signup("team-" + tag + "@hidaaco.com");
        assertThat(links(team)).as("a link was sent at signup").isEqualTo(1);

        UUID customer = signup("owner-" + tag + "@gmail.com");
        assertThat(links(customer)).isZero();
        assertThat(confirmations.sendIfInternal(customer)).isFalse();

        String link = confirmations.issue(team);
        assertThat(link).contains("/confirm-email?token=");
        String token = link.substring(link.indexOf("token=") + 6);
        assertThat(http.postForEntity("/api/auth/confirm-email", Map.of("token", token), Map.class).getStatusCode().value()).isEqualTo(200);
        assertThat(jdbc.sql("select email_confirmed_at is not null from users where id = ?").param(team).query(Boolean.class).single()).isTrue();
        assertThatThrownBy(() -> confirmations.confirm(token)).isInstanceOf(ApiException.class).hasMessageContaining("غير صالح");
        assertThat(confirmations.sendIfInternal(team)).as("already confirmed").isFalse();
    }
}
