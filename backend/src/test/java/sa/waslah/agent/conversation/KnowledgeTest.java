package sa.waslah.agent.conversation;

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
import org.springframework.jdbc.core.simple.JdbcClient;
import sa.waslah.agent.IntegrationTest;
import sa.waslah.agent.agent.AgentPrompt;
import sa.waslah.agent.auth.AuthService;
import sa.waslah.agent.common.ApiException;
import sa.waslah.agent.tenant.TenantContext;
import sa.waslah.agent.whatsapp.EmbeddedSignupService;
import sa.waslah.agent.whatsapp.WebhookProcessor;

/** What the agent did not know, what the office taught it, and how much interest each listing gets. */
class KnowledgeTest extends IntegrationTest {

    @Autowired AuthService auth;
    @Autowired EmbeddedSignupService signup;
    @Autowired WebhookProcessor processor;
    @Autowired AgentKnowledgeController knowledge;
    @Autowired JdbcClient jdbc;

    UUID office;
    String pn;

    @BeforeEach
    void setUp() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        office = auth.signup(new AuthService.Signup("مكتب " + tag, "12345678", "مالك", tag + "@know.sa", "password123")).officeId();
        pn = "PN-K-" + tag;
        TenantContext.run(office, () -> signup.complete(new EmbeddedSignupService.Request(null, null, pn, true)));
    }

    <T> T as(java.util.function.Supplier<T> block) {
        return TenantContext.call(office, block);
    }

    UUID conversation(String waId, String text) throws Exception {
        String body = """
                {"object":"whatsapp_business_account","entry":[{"id":"W","changes":[{"field":"messages","value":{
                "messaging_product":"whatsapp","metadata":{"phone_number_id":"%s"},
                "contacts":[{"profile":{"name":"عميل"},"wa_id":"%s"}],
                "messages":[{"from":"%s","id":"wamid.%s","timestamp":"%d","type":"text","text":{"body":"%s"}}]}}]}]}"""
                .formatted(pn, waId, waId, UUID.randomUUID(), Instant.now().getEpochSecond(), text);
        processor.process(body.getBytes(StandardCharsets.UTF_8));
        return as(() -> jdbc.sql("select c.id from conversations c join contacts k on k.id = c.contact_id where k.wa_id = ?")
                .param(waId).query(UUID.class).single());
    }

    @Test
    void theOfficeTeachesTheAgent() {
        assertThatThrownBy(() -> as(() -> knowledge.addFact(new AgentKnowledgeController.NewFact("س", "")))).isInstanceOf(ApiException.class);
        List<Map<String, Object>> facts = as(() -> knowledge.addFact(new AgentKnowledgeController.NewFact(
                "متى دوام المكتب؟", "من الأحد إلى الخميس، 9 صباحاً إلى 10 مساءً")));
        assertThat(facts).hasSize(1);

        String prompt = AgentPrompt.system("مكتب", "1100012345", LocalDate.now(),
                List.of(new AgentPrompt.Fact("متى دوام المكتب؟", "من الأحد إلى الخميس\n9 صباحاً")));
        assertThat(prompt).contains("معلومات من المكتب").contains("س: متى دوام المكتب؟").contains("ج: من الأحد إلى الخميس 9 صباحاً");
        // Without facts the prompt is exactly what it was.
        assertThat(AgentPrompt.system("مكتب", "1100012345", LocalDate.now(), List.of()))
                .isEqualTo(AgentPrompt.system("مكتب", "1100012345", LocalDate.now()));

        assertThat(as(() -> knowledge.deleteFact((UUID) facts.get(0).get("id")))).isEmpty();
    }

    @Test
    void gapsShowTheQuestionTheAgentCouldNotAnswer() throws Exception {
        UUID c = conversation("966557771001", "هل العمارة فيها مصعد؟");
        TenantContext.run(office, () -> jdbc.sql("""
                insert into handoffs (office_id, conversation_id, reason, summary) values (?, ?, 'OUT_OF_SCOPE', 'يسأل عن المصعد')""")
                .params(office, c).update());
        // A handoff for a serious buyer is not a gap.
        UUID d = conversation("966557771002", "أبي أشتري");
        TenantContext.run(office, () -> jdbc.sql("""
                insert into handoffs (office_id, conversation_id, reason, summary) values (?, ?, 'SERIOUS_BUYER', 'جاد')""")
                .params(office, d).update());

        List<Map<String, Object>> gaps = as(() -> knowledge.gaps(30));
        assertThat(gaps).hasSize(1);
        assertThat(gaps.get(0).get("question")).isEqualTo("هل العمارة فيها مصعد؟");
        assertThat(gaps.get(0).get("customer")).isEqualTo("عميل");
    }

    @Test
    @SuppressWarnings("unchecked")
    void listingInterestFromWhatTheAgentDid() throws Exception {
        UUID c = conversation("966557772001", "فيلا في الملقا");
        UUID shown = as(() -> jdbc.sql("""
                insert into listings (office_id, title, district, property_type, purpose, price, ad_license, license_expiry, latitude, longitude)
                values (?, 'فيلا', 'الملقا', 'فيلا', 'SALE', 1900000, '7100000001', current_date + 90, 24.8, 46.6) returning id""")
                .param(office).query(UUID.class).single());
        UUID quiet = as(() -> jdbc.sql("""
                insert into listings (office_id, title, district, property_type, purpose, price, ad_license, license_expiry, created_at)
                values (?, 'أرض', 'الرمال', 'أرض', 'SALE', 900000, '7100000002', current_date + 10, now() - interval '20 days') returning id""")
                .param(office).query(UUID.class).single());
        String calls = """
                [{"name":"search_listings","args":{},"result":{"listings":[{"id":"%s"}]}},
                 {"name":"send_listing_card","args":{"listing_id":"%s"},"result":{"sent":true}}]""".formatted(shown, shown);
        TenantContext.run(office, () -> jdbc.sql("""
                insert into agent_runs (office_id, conversation_id, model, tool_calls, outcome) values (?, ?, 'test', ?::jsonb, 'replied')""")
                .params(office, c, calls).update());

        Map<String, Object> r = as(() -> knowledge.interest(30));
        Map<String, Map<String, Integer>> per = (Map<String, Map<String, Integer>>) r.get("listings");
        assertThat(per.get(shown.toString())).containsEntry("shown", 1).containsEntry("cards", 1).containsEntry("viewings", 0);
        Map<String, Object> attention = (Map<String, Object>) r.get("attention");
        // The land: on the market 20 days, never shown, licence ending in 10 days, no photos or location.
        assertThat(attention.get("noInterest")).isEqualTo(1);
        assertThat((List<String>) attention.get("noInterestIds")).containsExactly(quiet.toString());
        assertThat(attention.get("licenceEnding")).isEqualTo(1);
        assertThat(attention.get("noCard")).isEqualTo(2);
    }
}
