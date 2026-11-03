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

/** Every new account gets one email with a 6-digit code and a link; either confirms the address, once. */
class EmailConfirmationTest extends IntegrationTest {

    @Autowired TestRestTemplate http;
    @Autowired EmailConfirmationService confirmations;
    @Autowired JdbcClient jdbc;

    UUID signup(String email) {
        http.postForEntity("/api/auth/signup", Map.of("officeName", "مكتب " + email, "falLicense", "12345678",
                "fullName", "فيصل", "email", email, "password", "password123"), Map.class);
        return jdbc.sql("select id from users where email = ?").param(email.toLowerCase()).query(UUID.class).single();
    }

    long open(UUID user) {
        return jdbc.sql("select count(*) from email_confirmations where user_id = ? and used_at is null").param(user)
                .query(Long.class).single();
    }

    boolean confirmed(UUID user) {
        return jdbc.sql("select email_confirmed_at is not null from users where id = ?").param(user).query(Boolean.class).single();
    }

    @Test
    void theLinkConfirmsOnce() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        UUID team = signup("team-" + tag + "@hidaaco.com");
        assertThat(open(team)).as("an email was sent at signup").isEqualTo(1);
        UUID customer = signup("owner-" + tag + "@gmail.com");
        assertThat(open(customer)).as("every account verifies, not only company ones").isEqualTo(1);

        EmailConfirmationService.Issued issued = confirmations.issue(team);
        assertThat(open(team)).as("a new email replaces the previous one").isEqualTo(1);
        assertThat(issued.code()).matches("\\d{6}");
        String token = issued.link().substring(issued.link().indexOf("token=") + 6);
        assertThat(http.postForEntity("/api/auth/confirm-email", Map.of("token", token), Map.class).getStatusCode().value()).isEqualTo(200);
        assertThat(confirmed(team)).isTrue();
        assertThatThrownBy(() -> confirmations.confirm(token)).isInstanceOf(ApiException.class).hasMessageContaining("غير صالح");
        assertThat(confirmations.send(team)).as("already confirmed").isFalse();
    }

    @Test
    void theCodeConfirmsWithFiveTriesAndArabicDigits() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        UUID user = signup("code-" + tag + "@gmail.com");
        String code = confirmations.issue(user).code();
        String wrong = code.equals("000000") ? "111111" : "000000";
        assertThatThrownBy(() -> confirmations.verifyCode(user, wrong)).hasMessageContaining("بقي 4");
        assertThatThrownBy(() -> confirmations.verifyCode(user, "12")).hasMessageContaining("6 أرقام");
        String arabic = code.chars().mapToObj(c -> String.valueOf((char) ('٠' + (c - '0')))).reduce("", String::concat);
        confirmations.verifyCode(user, arabic.substring(0, 3) + " " + arabic.substring(3));
        assertThat(confirmed(user)).isTrue();

        UUID other = signup("lock-" + tag + "@gmail.com");
        String right = confirmations.issue(other).code();
        String bad = right.equals("999999") ? "888888" : "999999";
        for (int i = 0; i < 5; i++) assertThatThrownBy(() -> confirmations.verifyCode(other, bad)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> confirmations.verifyCode(other, right)).as("locked after 5 wrong tries").hasMessageContaining("رمزاً جديداً");
        assertThat(confirmed(other)).isFalse();
        String fresh = confirmations.issue(other).code();
        confirmations.verifyCode(other, fresh);
        assertThat(confirmed(other)).isTrue();
    }
}
