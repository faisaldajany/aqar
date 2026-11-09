package sa.waslah.agent.whatsapp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
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
import sa.waslah.agent.IntegrationTest;
import sa.waslah.agent.auth.AuthService;

/** The team links a number by hand (Meta's test number): operator only, inputs checked, refused in demo WhatsApp mode. */
@SuppressWarnings({"rawtypes", "unchecked"})
class ManualLinkTest extends IntegrationTest {

    static final String KEY = "test-admin-token-0123456789";

    @Autowired TestRestTemplate http;
    @Autowired AuthService auth;

    UUID office() {
        String t = UUID.randomUUID().toString().substring(0, 8);
        return auth.signup(new AuthService.Signup("مكتب " + t, "1100012345", "مالك", t + "@link.sa", "password123")).officeId();
    }

    ResponseEntity<Map> link(UUID office, Map<String, Object> body, boolean operator) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (operator) h.add("X-Admin-Token", KEY);
        return http.exchange("/api/ops/offices/" + office + "/whatsapp/manual", HttpMethod.POST, new HttpEntity<>(body, h), Map.class);
    }

    static final Map<String, Object> GOOD = Map.of("wabaId", "4402536303360170", "phoneNumberId", "1060136380514681",
            "accessToken", "EAAB" + "x".repeat(40));

    @Test
    void onlyTheTeamCanLinkANumber() {
        assertThat(link(office(), GOOD, false).getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void theIdsAndTokenAreChecked() {
        UUID office = office();
        ResponseEntity<Map> r = link(office, Map.of("wabaId", "abc", "phoneNumberId", "1060136380514681", "accessToken", "EAAB" + "x".repeat(40)), true);
        assertThat(r.getStatusCode().value()).isEqualTo(400);
        assertThat(r.getBody().get("error")).isEqualTo("bad_waba");
        r = link(office, Map.of("wabaId", "4402536303360170", "phoneNumberId", "12", "accessToken", "EAAB" + "x".repeat(40)), true);
        assertThat(r.getBody().get("error")).isEqualTo("bad_phone_id");
        r = link(office, Map.of("wabaId", "4402536303360170", "phoneNumberId", "1060136380514681", "accessToken", "short"), true);
        assertThat(r.getBody().get("error")).isEqualTo("bad_token");
    }

    @Test
    void demoWhatsAppModeRefusesARealNumber() {
        ResponseEntity<Map> r = link(office(), GOOD, true);
        assertThat(r.getStatusCode().value()).isEqualTo(409);
        assertThat(r.getBody().get("error")).isEqualTo("wa_mock");
    }

    @Test
    void theOfficePageListsItsNumbers() {
        UUID office = office();
        HttpHeaders h = new HttpHeaders();
        h.add("X-Admin-Token", KEY);
        ResponseEntity<Map> r = http.exchange("/api/ops/offices/" + office, HttpMethod.GET, new HttpEntity<>(h), Map.class);
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat((List) r.getBody().get("waAccounts")).isEmpty();
    }
}
