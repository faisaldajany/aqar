package sa.waslah.agent.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import sa.waslah.agent.IntegrationTest;
import sa.waslah.agent.auth.AuthService;
import sa.waslah.agent.listing.MarketerStore;
import sa.waslah.agent.tenant.TenantContext;
import sa.waslah.agent.whatsapp.EmbeddedSignupService;
import sa.waslah.agent.whatsapp.WebhookProcessor;

/** The lead card, handing a chat to a marketer, the handoff queue and dashboard numbers. */
class WorkspaceTest extends IntegrationTest {

    @Autowired AuthService auth;
    @Autowired EmbeddedSignupService signup;
    @Autowired WebhookProcessor processor;
    @Autowired MarketerStore marketers;
    @Autowired WorkspaceController workspace;
    @Autowired ConversationStore conversations;
    @Autowired JdbcClient jdbc;
    @Autowired sa.waslah.agent.privacy.PrivacyService privacy;

    UUID office, conversation, marketer;

    @BeforeEach
    void setUp() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        office = auth.signup(new AuthService.Signup("مكتب " + tag, "12345678", "مالك", tag + "@ws.sa", "password123")).officeId();
        String pn = "PN-" + tag;
        TenantContext.run(office, () -> {
            signup.complete(new EmbeddedSignupService.Request(null, null, pn, true));
            marketer = marketers.insert(office, "سارة", "966551840033", List.of("الملقا"));
        });
        String body = """
                {"object":"whatsapp_business_account","entry":[{"id":"W","changes":[{"field":"messages","value":{
                "messaging_product":"whatsapp","metadata":{"phone_number_id":"%s"},
                "contacts":[{"profile":{"name":"أم خالد"},"wa_id":"966553334444"}],
                "messages":[{"from":"966553334444","id":"wamid.%s","timestamp":"%d","type":"text","text":{"body":"أبي شقة"}}]}}]}]}"""
                .formatted(pn, tag, Instant.now().getEpochSecond());
        processor.process(body.getBytes(StandardCharsets.UTF_8));
        conversation = TenantContext.call(office, () -> jdbc.sql("select id from conversations").query(UUID.class).single());
    }

    @Test
    void leadCardShowsTemperature() {
        TenantContext.run(office, () -> jdbc.sql("""
                insert into leads (conversation_id, office_id, purpose, budget_max, payment, timeline)
                values (?, ?, 'RENT', 70000, 'CASH', 'خلال شهر')""").params(conversation, office).update());
        Map<String, Object> d = TenantContext.call(office, () -> workspace.details(conversation));
        @SuppressWarnings("unchecked") Map<String, Object> lead = (Map<String, Object>) d.get("lead");
        assertThat(lead.get("temperature")).isEqualTo("HOT");
        assertThat(d.get("agentActive")).isEqualTo(true);
        assertThat(WorkspaceController.temperature(null, null, null, null)).isEqualTo("COLD");
    }

    @Test
    void assigningPausesTheAgentAndShowsInHandoffs() {
        TenantContext.run(office, () -> workspace.assign(conversation, new WorkspaceController.Assign(marketer, null)));
        assertThat(TenantContext.call(office, () -> conversations.find(conversation).orElseThrow().agentActive(Instant.now()))).isFalse();
        List<Map<String, Object>> claimed = TenantContext.call(office, () -> workspace.handoffList("CLAIMED"));
        assertThat(claimed).hasSize(1);
        assertThat(claimed.get(0).get("marketer")).isEqualTo("سارة");
        assertThat(claimed.get(0).get("customer")).isEqualTo("أم خالد");
        UUID id = (UUID) claimed.get(0).get("id");
        TenantContext.run(office, () -> workspace.handoffAction(id, "close"));
        assertThat(TenantContext.call(office, () -> workspace.handoffList("CLOSED"))).hasSize(1);
    }

    @Test
    void statsCountToday() {
        Map<String, Object> s = TenantContext.call(office, () -> workspace.stats());
        assertThat(s.get("inquiriesToday")).isEqualTo(1);
        assertThat(s.get("openHandoffs")).isEqualTo(0);
    }

    @Test
    void erasingACustomerRemovesTheirMessagesAndName() {
        TenantContext.run(office, () -> jdbc.sql("""
                insert into leads (conversation_id, office_id, customer_name) values (?, ?, 'أم خالد')""")
                .params(conversation, office).update());
        TenantContext.run(office, () -> privacy.erase(conversation));
        assertThat(TenantContext.call(office, () -> jdbc.sql("select count(*) from messages").query(Integer.class).single())).isZero();
        assertThat(TenantContext.call(office, () -> jdbc.sql("select count(*) from leads").query(Integer.class).single())).isZero();
        String wa = TenantContext.call(office, () -> jdbc.sql("select wa_id from contacts").query(String.class).single());
        assertThat(wa).startsWith("erased-");
        assertThat(TenantContext.call(office, () -> conversations.find(conversation).orElseThrow().agentActive(Instant.now()))).isFalse();
    }

    @Test
    void retentionDeletesOldMessagesOnly() {
        assertThat(TenantContext.call(office, () -> privacy.purge(Instant.now()))).isZero();
        assertThat(TenantContext.call(office, () -> privacy.purge(Instant.now().plusSeconds(731L * 86400)))).isEqualTo(1);
    }
}
