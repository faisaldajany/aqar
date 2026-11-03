package sa.waslah.agent.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import sa.waslah.agent.IntegrationTest;
import sa.waslah.agent.agent.llm.Llm;
import sa.waslah.agent.agent.llm.ScriptedLlmClient;
import sa.waslah.agent.auth.AuthService;
import sa.waslah.agent.tenant.TenantContext;
import sa.waslah.agent.whatsapp.EmbeddedSignupService;
import sa.waslah.agent.whatsapp.WebhookProcessor;

/** /ops → مراجعة الردود: rule flags, the masked transcript, exports and the AI review, on a real database. */
class AgentReviewTest extends IntegrationTest {

    @Autowired TestRestTemplate http;
    @Autowired AuthService auth;
    @Autowired EmbeddedSignupService signup;
    @Autowired WebhookProcessor processor;
    @Autowired JdbcClient jdbc;
    @Autowired Llm.Client llm;
    @Autowired ObjectMapper json;

    UUID office, conversation;
    String ops;

    @BeforeEach
    void setUp() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        office = auth.signup(new AuthService.Signup("مكتب " + tag, "12345678", "مالك", tag + "@rev.sa", "password123")).officeId();
        String pn = "PN-R-" + tag;
        TenantContext.run(office, () -> signup.complete(new EmbeddedSignupService.Request(null, null, pn, true)));
        String body = """
                {"object":"whatsapp_business_account","entry":[{"id":"W","changes":[{"field":"messages","value":{
                "messaging_product":"whatsapp","metadata":{"phone_number_id":"%s"},
                "contacts":[{"profile":{"name":"أبو فهد"},"wa_id":"966557778888"}],
                "messages":[{"from":"966557778888","id":"wamid.%s","timestamp":"%d","type":"text","text":{"body":"أبي فيلا في النرجس"}}]}}]}]}"""
                .formatted(pn, tag, Instant.now().getEpochSecond());
        processor.process(body.getBytes(StandardCharsets.UTF_8));
        TenantContext.run(office, () -> {
            conversation = jdbc.sql("select id from conversations").query(UUID.class).single();
            UUID first = jdbc.sql("select id from messages").query(UUID.class).single();
            jdbc.sql("update messages set created_at = now() - interval '60 minutes' where id = ?").param(first).update();
            msg("OUT", "AGENT", "هلا! عندنا فيلا في النرجس بـ 2,000,000 ريال، ترخيص الإعلان 7100000001", 59);
            jdbc.sql("""
                    insert into agent_runs (office_id, conversation_id, trigger_message, model, latency_ms, tool_calls, outcome)
                    values (?, ?, ?, 'gemini-test', 25000, ?::jsonb, 'replied')""")
                    .params(office, conversation, first, """
                            [{"name":"search_listings","args":{"districts":"النرجس","customer_name":"أبو فهد"},
                              "result":{"listings":[{"title":"فيلا درج داخلي","district":"النرجس","property_type":"فيلا","purpose":"SALE",
                                "price_sar":1900000,"ad_license":"7100000001","license_expiry":"2027-01-01"}]}}]""").update();
            msg("IN", "CUSTOMER", "ابي اكلم موظف، جوالي 0551234567", 50);
            msg("OUT", "AGENT", "هلا! عندنا فيلا في النرجس بـ 2,000,000 ريال، ترخيص الإعلان 7100000001", 49);
            msg("IN", "CUSTOMER", "غلط السعر", 40);
        });
        ResponseEntity<Map> login = http.postForEntity("/api/ops/login", Map.of("key", OpsFlowTest.KEY), Map.class);
        ops = OpsFlowTest.cookie(login, "hidaa_ops");
    }

    void msg(String direction, String sender, String body, int minutesAgo) {
        jdbc.sql("""
                insert into messages (office_id, conversation_id, direction, sender, type, body, created_at)
                values (?, ?, ?, ?, 'text', ?, now() - make_interval(mins => ?))""")
                .params(office, conversation, direction, sender, body, minutesAgo).update();
    }

    <T> ResponseEntity<T> get(String path, Class<T> type) {
        HttpHeaders h = new HttpHeaders();
        h.add(HttpHeaders.COOKIE, ops);
        return http.exchange(path, HttpMethod.GET, new HttpEntity<>(h), type);
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> row() {
        Map<String, Object> list = get("/api/ops/reviews?officeId=" + office, Map.class).getBody();
        return ((List<Map<String, Object>>) list.get("rows")).stream()
                .filter(r -> conversation.toString().equals(r.get("conversationId"))).findFirst().orElseThrow();
    }

    @Test
    @SuppressWarnings("unchecked")
    void rulesFlagTheProblemsAndCustomersAreMasked() {
        Map<String, Object> r = row();
        assertThat((List<String>) r.get("flags")).contains("SLOW", "ASKED_HUMAN", "REPEAT", "COMPLAINT", "UNANSWERED");
        assertThat((String) r.get("customer")).startsWith("عميل-").doesNotContain("966557778888");

        Map<String, Object> c = get("/api/ops/offices/" + office + "/conversations/" + conversation, Map.class).getBody();
        List<Map<String, Object>> messages = (List<Map<String, Object>>) c.get("messages");
        assertThat(messages).hasSize(5);
        assertThat((String) messages.get(2).get("text")).contains("[جوال]").doesNotContain("0551234567");
        Map<String, Object> run = (Map<String, Object>) messages.get(1).get("run");
        assertThat(run).as("the run sits on the reply it produced").containsEntry("outcome", "replied");
        assertThat(run.toString()).doesNotContain("أبو فهد").contains("7100000001");
        long viewed = jdbc.sql("select count(*) from ops_audit where action = 'CONVERSATION_VIEWED' and office_id = ?")
                .param(office).query(Long.class).single();
        assertThat(viewed).isEqualTo(1);
    }

    @Test
    void exportsAreMaskedCsvAndJsonLines() throws Exception {
        String csv = get("/api/ops/export?officeId=" + office + "&format=csv", String.class).getBody();
        assertThat(csv.replace("\uFEFF", "")).startsWith("office,conversation,customer,n,").contains("[جوال]").contains("عميل-")
                .doesNotContain("0551234567").doesNotContain("966557778888").doesNotContain("أبو فهد");
        assertThat(csv.lines().count()).isEqualTo(6);

        String jsonl = get("/api/ops/export?officeId=" + office + "&format=jsonl", String.class).getBody();
        JsonNode line = json.readTree(jsonl.lines().findFirst().orElseThrow());
        assertThat(line.path("messages")).hasSize(5);
        assertThat(line.path("messages").get(1).path("agent").path("tools").get(0).path("name").asText()).isEqualTo("search_listings");
        assertThat(jdbc.sql("select count(*) from ops_audit where action = 'EXPORT' and office_id = ?").param(office)
                .query(Long.class).single()).isEqualTo(2);
    }

    @Test
    @SuppressWarnings("unchecked")
    void theAiReviewerGradesAgainstTheToolResultsAndIsStored() {
        ScriptedLlmClient scripted = (ScriptedLlmClient) llm;
        scripted.reset();
        scripted.thenExtract("""
                {"score":2,"verdict":"ISSUE","summary":"ذكر الوكيل سعراً غير الموجود في نتائج البحث.",
                 "issues":[{"turn":2,"type":"INACCURATE","detail":"السعر في النتائج 1,900,000 لا 2,000,000."}]}""");
        HttpHeaders h = new HttpHeaders();
        h.add(HttpHeaders.COOKIE, ops);
        h.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> res = http.exchange("/api/ops/reviews/run", HttpMethod.POST,
                new HttpEntity<>(Map.of("officeId", office.toString(), "batch", 5), h), Map.class).getBody();
        assertThat((List<?>) res.get("reviewed")).hasSize(1);

        String seen = ((Llm.UserText) scripted.requests().get(scripted.requests().size() - 1).turns().get(0)).text();
        assertThat(seen).contains("أداة الوكيل search_listings").contains("السعر 1900000").contains("[2] الوكيل")
                .doesNotContain("0551234567").doesNotContain("أبو فهد");

        Map<String, Object> r = row();
        Map<String, Object> review = (Map<String, Object>) r.get("review");
        assertThat(review).containsEntry("score", 2).containsEntry("verdict", "ISSUE").containsEntry("stale", false);
        Map<String, Object> list = get("/api/ops/reviews?officeId=" + office, Map.class).getBody();
        assertThat((Map<String, Object>) list.get("byIssue")).containsEntry("INACCURATE", 1);
        assertThat(list.get("toReview")).isEqualTo(0);

        Map<String, Object> again = http.exchange("/api/ops/reviews/run", HttpMethod.POST,
                new HttpEntity<>(Map.of("officeId", office.toString()), h), Map.class).getBody();
        assertThat((List<?>) again.get("reviewed")).as("nothing new to review").isEmpty();
    }
}
