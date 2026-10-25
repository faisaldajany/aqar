package sa.waslah.agent.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import sa.waslah.agent.IntegrationTest;
import sa.waslah.agent.agent.llm.Llm;
import sa.waslah.agent.agent.llm.ScriptedLlmClient;
import sa.waslah.agent.auth.AuthService;
import sa.waslah.agent.listing.ListingStore;
import sa.waslah.agent.listing.MarketerStore;
import sa.waslah.agent.tenant.TenantContext;
import sa.waslah.agent.whatsapp.EmbeddedSignupService;
import sa.waslah.agent.whatsapp.MockWhatsAppClient;
import sa.waslah.agent.whatsapp.WebhookProcessor;
import sa.waslah.agent.whatsapp.WhatsAppClient;

/** End to end: webhook → agent (scripted model) → tools on the real DB → reply through WhatsApp mock. */
@TestPropertySource(properties = "waslah.agent.enabled=true")
class AgentFlowTest extends IntegrationTest {

    @Autowired AuthService auth;
    @Autowired EmbeddedSignupService signup;
    @Autowired WebhookProcessor processor;
    @Autowired ListingStore listings;
    @Autowired MarketerStore marketers;
    @Autowired Llm.Client llm;
    @Autowired WhatsAppClient whatsapp;
    @Autowired JdbcClient jdbc;

    UUID office;
    String phoneNumberId;
    String customer;
    String license = "7200418836";

    @BeforeEach
    void setUp() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        office = auth.signup(new AuthService.Signup("مكتب الأفق " + tag, "12345678", "مالك", tag + "@agent.sa", "password123")).officeId();
        phoneNumberId = "PN-" + tag;
        customer = "9665" + String.valueOf(Math.abs(tag.hashCode()) % 100000000 + 100000000).substring(1);
        TenantContext.run(office, () -> {
            signup.complete(new EmbeddedSignupService.Request(null, null, phoneNumberId, true));
            marketers.insert(office, "خالد العتيبي", "966551840022", List.of("النرجس"));
            listings.insert(office, java.util.Map.of(
                    "title", "فيلا درج داخلي", "district", "النرجس", "propertyType", "فلة", "purpose", "SALE",
                    "price", new java.math.BigDecimal("1950000"), "areaSqm", new java.math.BigDecimal("375"), "rooms", 5,
                    "adLicense", license, "licenseExpiry", LocalDate.now().plusMonths(4)));
        });
        ((ScriptedLlmClient) llm).reset();
        ((MockWhatsAppClient) whatsapp).reset();
    }

    @Test
    void answersWithToolResultsAndLogsTheRun() throws Exception {
        ((ScriptedLlmClient) llm)
                .thenCall("save_lead", "{\"purpose\":\"SALE\",\"property_type\":\"فيلا\",\"districts\":\"النرجس\",\"budget_max\":2000000,\"payment\":\"CASH\"}")
                .thenCall("search_listings", "{\"purpose\":\"SALE\",\"property_type\":\"فيلا\",\"district\":\"حي النرجس\",\"max_price\":2000000}")
                .thenText("هلا، معك المساعد الآلي لمكتب الأفق. عندنا فيلا درج داخلي في النرجس 375 م² بـ1,950,000 ريال، ترخيص " + license + ". تبي معاينة؟");

        inbound("wamid.a1", "ابغى فيلا في النرجس بحدود مليونين كاش");

        String sent = await(() -> lastSentText());
        assertThat(sent).as(() -> "agent run: " + TenantContext.call(office, () -> jdbc.sql(
                "select outcome || ' | ' || coalesce(error, '') || ' | ' || tool_calls::text from agent_runs")
                .query(String.class).list())).contains(license);
        // The model's second call saw the listing returned by the real search.
        Llm.Request third = ((ScriptedLlmClient) llm).requests().get(2);
        assertThat(third.turns().toString()).contains(license);
        assertThat(TenantContext.call(office, () -> jdbc.sql("select payment from leads").query(String.class).single()))
                .isEqualTo("CASH");
        assertThat(TenantContext.call(office, () -> jdbc.sql("select outcome from agent_runs").query(String.class).single()))
                .isEqualTo("replied");
    }

    @Test
    void replyWithAnUnknownLicenseIsBlockedAndHandedOff() throws Exception {
        ((ScriptedLlmClient) llm).thenText("عندنا فيلا ممتازة ترخيصها 7299999999");
        inbound("wamid.b1", "عندكم فلل؟");

        assertThat(await(() -> lastSentText())).isEqualTo(AgentPrompt.FALLBACK_REPLY);
        assertThat(TenantContext.call(office, () -> jdbc.sql("select count(*) from handoffs").query(Integer.class).single()))
                .isEqualTo(1);
        Boolean paused = TenantContext.call(office, () -> jdbc.sql(
                "select agent_paused_until > now() from conversations").query(Boolean.class).single());
        assertThat(paused).isTrue();
    }

    @Test
    void voiceNoteGetsTheTextPromptWithoutCallingTheModel() throws Exception {
        String body = envelope("""
                "messages":[{"from":"%s","id":"wamid.v1","timestamp":"%d","type":"audio","audio":{"id":"MEDIA1","mime_type":"audio/ogg"}}]"""
                .formatted(customer, Instant.now().getEpochSecond()));
        processor.process(body.getBytes(StandardCharsets.UTF_8));

        assertThat(await(() -> lastSentText())).isEqualTo(AgentPrompt.VOICE_NOTE_REPLY);
        assertThat(((ScriptedLlmClient) llm).requests()).isEmpty();
    }

    @Test
    void negotiationHandsOffToTheDistrictMarketer() throws Exception {
        ((ScriptedLlmClient) llm)
                .thenCall("save_lead", "{\"districts\":\"النرجس\"}")
                .thenCall("handoff_to_marketer", "{\"reason\":\"NEGOTIATION\",\"summary\":\"يطلب خصم على فيلا النرجس\"}")
                .thenText("السعر يحدده المكتب، بحوّلك للأستاذ خالد ويتواصل معك.");
        inbound("wamid.c1", "كم آخر سعر للفيلا؟ تنزلون؟");

        await(() -> lastSentText());
        String marketer = TenantContext.call(office, () -> jdbc.sql("""
                select m.name from handoffs h join marketers m on m.id = h.marketer_id""").query(String.class).single());
        assertThat(marketer).isEqualTo("خالد العتيبي");
    }

    private void inbound(String wamid, String text) throws Exception {
        String body = envelope("""
                "contacts":[{"profile":{"name":"أبو عبدالله"},"wa_id":"%s"}],
                "messages":[{"from":"%s","id":"%s","timestamp":"%d","type":"text","text":{"body":"%s"}}]"""
                .formatted(customer, customer, wamid, Instant.now().getEpochSecond(), text));
        processor.process(body.getBytes(StandardCharsets.UTF_8));
    }

    private String envelope(String inner) {
        return """
                {"object":"whatsapp_business_account","entry":[{"id":"W","changes":[{"field":"messages","value":{
                "messaging_product":"whatsapp","metadata":{"phone_number_id":"%s"},%s}}]}]}""".formatted(phoneNumberId, inner);
    }

    private String lastSentText() {
        List<MockWhatsAppClient.Sent> sent = ((MockWhatsAppClient) whatsapp).sent().stream()
                .filter(s -> s.phoneNumberId().equals(phoneNumberId) && s.kind().equals("text")).toList();
        return sent.isEmpty() ? null : sent.get(sent.size() - 1).content();
    }

    private static <T> T await(Supplier<T> probe) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        while (Instant.now().isBefore(deadline)) {
            T v = probe.get();
            if (v != null) return v;
            Thread.sleep(50);
        }
        throw new AssertionError("Timed out waiting for the agent");
    }
}
