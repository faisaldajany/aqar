package sa.waslah.agent.whatsapp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import sa.waslah.agent.IntegrationTest;
import sa.waslah.agent.auth.AuthService;
import sa.waslah.agent.common.ApiException;
import sa.waslah.agent.tenant.TenantContext;

/** The WhatsApp setup steps, from no WhatsApp Business account to the first customer message. */
class WhatsAppSetupTest extends IntegrationTest {

    @Autowired AuthService auth;
    @Autowired WhatsAppSetup setup;
    @Autowired EmbeddedSignupService signup;
    @Autowired WebhookProcessor processor;

    UUID office;
    String tag;

    @BeforeEach
    void setUp() {
        tag = UUID.randomUUID().toString().substring(0, 8);
        office = auth.signup(new AuthService.Signup("مكتب " + tag, "1100012345", "مالك", tag + "@setup.sa", "password123")).officeId();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> step(Map<String, Object> state, String key) {
        return ((List<Map<String, Object>>) state.get("steps")).stream().filter(s -> key.equals(s.get("key"))).findFirst().orElseThrow();
    }

    <T> T as(java.util.function.Supplier<T> block) {
        return TenantContext.call(office, block);
    }

    @Test
    void stepsAreConfirmedInOrderAndHiddenFromOtherOffices() {
        Map<String, Object> s = as(() -> setup.state());
        assertThat(s.get("current")).isEqualTo("number");
        assertThat(s.get("done")).isEqualTo(0);
        assertThat(s.get("total")).isEqualTo(8);

        // The number is checked: a landline is refused, an Arabic-digit mobile is accepted and shown as 05….
        assertThatThrownBy(() -> as(() -> setup.confirm("number", "0112345678"))).isInstanceOf(ApiException.class);
        s = as(() -> setup.confirm("number", "٠٥٥١٢٣٤٥٦٧"));
        assertThat(s.get("phone")).isEqualTo("0551234567");
        assertThat(step(s, "number").get("done")).isEqualTo(true);
        assertThat(s.get("current")).isEqualTo("app");

        s = as(() -> setup.confirm("app", null));
        assertThat(s.get("current")).isEqualTo("registered");

        // The registration date cannot be in the future; 7 days of use are counted from it.
        LocalDate today = LocalDate.now(WhatsAppSetup.RIYADH);
        assertThatThrownBy(() -> as(() -> setup.confirm("registered", today.plusDays(1).toString(), today)))
                .isInstanceOf(ApiException.class).hasMessageContaining("بعد اليوم");
        s = as(() -> setup.confirm("registered", today.minusDays(2).toString(), today));
        assertThat(s.get("readyOn")).isEqualTo(today.plusDays(5).toString());
        assertThat(s.get("daysLeft")).isEqualTo(5L);

        as(() -> setup.confirm("profile", null));
        s = as(() -> setup.confirm("facebook", null));
        assertThat(step(s, "active").get("done")).isEqualTo(false);
        assertThat(s.get("current")).isEqualTo("active");
        // Hidaa checks this one itself.
        assertThatThrownBy(() -> as(() -> setup.confirm("active", null))).isInstanceOf(ApiException.class);
        assertThat(step(as(() -> setup.state(today.plusDays(5))), "active").get("done")).isEqualTo(true);

        // Another office sees none of it.
        UUID other = auth.signup(new AuthService.Signup("آخر " + tag, "1100012345", "مالك", "o" + tag + "@setup.sa", "password123")).officeId();
        Map<String, Object> theirs = TenantContext.call(other, () -> setup.state());
        assertThat(theirs.get("done")).isEqualTo(0);
        assertThat(theirs.get("phone")).isNull();
    }

    @Test
    void undoOpensTheStepAgain() {
        as(() -> setup.confirm("number", "0551234567"));
        as(() -> setup.confirm("registered", LocalDate.now(WhatsAppSetup.RIYADH).minusDays(10).toString()));
        Map<String, Object> s = as(() -> setup.undo("number"));
        assertThat(s.get("phone")).isNull();
        assertThat(step(s, "number").get("done")).isEqualTo(false);
        assertThat(s.get("businessAppSince")).isNotNull();
        s = as(() -> setup.undo("registered"));
        assertThat(s.get("businessAppSince")).isNull();
        assertThat(s.get("readyOn")).isNull();
        assertThat(as(() -> setup.choose(true)).get("hasBusinessApp")).isEqualTo(true);
    }

    @Test
    void connectionAndFirstCustomerMessageAreDetected() throws Exception {
        String phoneNumberId = "PN-S-" + tag;
        as(() -> signup.complete(new EmbeddedSignupService.Request(null, null, phoneNumberId, true)));
        Map<String, Object> s = as(() -> setup.state());
        // Connected: everything before the connection counts as done, even if it was never ticked here.
        assertThat(step(s, "number").get("done")).isEqualTo(true);
        assertThat(step(s, "active").get("done")).isEqualTo(true);
        assertThat(step(s, "connect").get("done")).isEqualTo(true);
        assertThat(s.get("current")).isEqualTo("test");

        String body = """
                {"object":"whatsapp_business_account","entry":[{"id":"W","changes":[{"field":"messages","value":{
                "messaging_product":"whatsapp","metadata":{"phone_number_id":"%s"},
                "contacts":[{"profile":{"name":"عميل"},"wa_id":"966557771111"}],
                "messages":[{"from":"966557771111","id":"wamid.s%s","timestamp":"%d","type":"text","text":{"body":"السلام عليكم"}}]}}]}]}"""
                .formatted(phoneNumberId, tag, Instant.now().getEpochSecond());
        processor.process(body.getBytes(StandardCharsets.UTF_8));
        s = as(() -> setup.state());
        assertThat(step(s, "test").get("done")).isEqualTo(true);
        assertThat(s.get("current")).isNull();
        assertThat(s.get("done")).isEqualTo(8);
    }
}
