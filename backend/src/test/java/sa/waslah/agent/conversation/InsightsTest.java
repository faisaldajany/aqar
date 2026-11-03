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
import sa.waslah.agent.tenant.TenantContext;
import sa.waslah.agent.whatsapp.EmbeddedSignupService;
import sa.waslah.agent.whatsapp.WebhookProcessor;

/** The office dashboard: daily numbers from real customers only, and demand next to the office's listings. */
class InsightsTest extends IntegrationTest {

    @Autowired AuthService auth;
    @Autowired EmbeddedSignupService signup;
    @Autowired WebhookProcessor processor;
    @Autowired InsightsController insights;
    @Autowired JdbcClient jdbc;

    UUID office;
    String pn;

    @BeforeEach
    void setUp() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        office = auth.signup(new AuthService.Signup("مكتب " + tag, "12345678", "مالك", tag + "@ins.sa", "password123")).officeId();
        pn = "PN-I-" + tag;
        TenantContext.run(office, () -> signup.complete(new EmbeddedSignupService.Request(null, null, pn, true)));
    }

    UUID customer(String waId, String text) {
        String body = """
                {"object":"whatsapp_business_account","entry":[{"id":"W","changes":[{"field":"messages","value":{
                "messaging_product":"whatsapp","metadata":{"phone_number_id":"%s"},
                "contacts":[{"profile":{"name":"عميل"},"wa_id":"%s"}],
                "messages":[{"from":"%s","id":"wamid.%s","timestamp":"%d","type":"text","text":{"body":"%s"}}]}}]}]}"""
                .formatted(pn, waId, waId, UUID.randomUUID(), Instant.now().getEpochSecond(), text);
        processor.process(body.getBytes(StandardCharsets.UTF_8));
        return TenantContext.call(office, () -> jdbc.sql("""
                select c.id from conversations c join contacts k on k.id = c.contact_id where k.wa_id = ?""")
                .param(waId).query(UUID.class).single());
    }

    void lead(UUID conversation, String purpose, String type, String districts, Integer budget, String payment) {
        TenantContext.run(office, () -> jdbc.sql("""
                insert into leads (conversation_id, office_id, purpose, property_type, districts, budget_max, payment)
                values (?, ?, ?, ?, ?, ?, ?)""").params(conversation, office, purpose, type, districts, budget, payment).update());
    }

    @Test
    void countsCustomersNotTheTestChatAndShowsDemandAgainstListings() {
        UUID a = customer("966550000001", "أبي فيلا في النرجس");
        UUID b = customer("966550000002", "شقة للإيجار");
        lead(a, "SALE", "فيلا", "حي النرجس، الملقا", 2_000_000, "CASH");
        lead(b, "RENT", "شقة", "النرجس", null, null);
        TenantContext.run(office, () -> {
            UUID contact = jdbc.sql("insert into contacts (office_id, wa_id) values (?, ?) returning id")
                    .params(office, ConversationStore.SANDBOX).query(UUID.class).single();
            jdbc.sql("insert into conversations (id, office_id, contact_id) values (?, ?, ?)")
                    .params(UUID.randomUUID(), office, contact).update();
            jdbc.sql("""
                    insert into listings (office_id, title, district, property_type, purpose, price, ad_license, license_expiry)
                    values (?, 'فيلا', 'الملقا', 'فيلا', 'SALE', 1900000, '7100000001', current_date + 90)""").param(office).update();
        });

        Map<String, Object> r = TenantContext.call(office, () -> insights.insights(30));
        assertThat(r.get("days")).isEqualTo(30);
        assertThat((List<?>) r.get("series")).hasSize(30);
        @SuppressWarnings("unchecked") Map<String, Integer> totals = (Map<String, Integer>) r.get("totals");
        assertThat(totals.get("inquiries")).as("the test chat is not a customer").isEqualTo(2);
        assertThat(totals.get("serious")).isEqualTo(1);

        @SuppressWarnings("unchecked") List<Map<String, Object>> districts = (List<Map<String, Object>>) r.get("topDistricts");
        assertThat(districts.get(0)).containsEntry("name", "النرجس").containsEntry("requests", 2).containsEntry("listings", 0);
        assertThat(districts).anySatisfy(d -> assertThat(d).containsEntry("name", "الملقا").containsEntry("listings", 1));
        @SuppressWarnings("unchecked") Map<String, Integer> purpose = (Map<String, Integer>) r.get("purpose");
        assertThat(purpose).containsEntry("SALE", 1).containsEntry("RENT", 1);
        @SuppressWarnings("unchecked") Map<String, Integer> temp = (Map<String, Integer>) r.get("temperature");
        assertThat(temp.get("HOT") + temp.get("WARM") + temp.get("COLD")).isEqualTo(2);
        @SuppressWarnings("unchecked") Map<String, Object> hours = (Map<String, Object>) r.get("hours");
        assertThat(hours.get("messages")).isEqualTo(2);

        assertThat(TenantContext.call(office, () -> insights.insights(5)).get("days")).as("rounded to 7, 30 or 90").isEqualTo(7);
    }

    @Test
    void namesAreMatchedLooselyLikeTheAgentsSearch() {
        assertThat(InsightsController.norm("حي النرجس")).isEqualTo(InsightsController.norm("نرجس"));
        assertThat(InsightsController.norm("الملقا")).isEqualTo(InsightsController.norm("ملقا"));
        assertThat(InsightsController.norm("الروضة")).isEqualTo(InsightsController.norm("الروضه"));
    }
}
