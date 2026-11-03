package sa.waslah.agent.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
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
import sa.waslah.agent.common.ApiException;
import sa.waslah.agent.tenant.TenantContext;
import sa.waslah.agent.whatsapp.EmbeddedSignupService;
import sa.waslah.agent.whatsapp.WebhookProcessor;

/** قائمة العملاء: who wrote, what they want, where they stand, and the deals. */
class CustomersTest extends IntegrationTest {

    @Autowired AuthService auth;
    @Autowired EmbeddedSignupService signup;
    @Autowired WebhookProcessor processor;
    @Autowired CustomersController customers;
    @Autowired JdbcClient jdbc;

    UUID office;
    String pn;

    @BeforeEach
    void setUp() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        office = auth.signup(new AuthService.Signup("مكتب " + tag, "12345678", "مالك", tag + "@cust.sa", "password123")).officeId();
        pn = "PN-C-" + tag;
        TenantContext.run(office, () -> signup.complete(new EmbeddedSignupService.Request(null, null, pn, true)));
    }

    UUID customer(String waId, String name) throws Exception {
        String body = """
                {"object":"whatsapp_business_account","entry":[{"id":"W","changes":[{"field":"messages","value":{
                "messaging_product":"whatsapp","metadata":{"phone_number_id":"%s"},
                "contacts":[{"profile":{"name":"%s"},"wa_id":"%s"}],
                "messages":[{"from":"%s","id":"wamid.%s","timestamp":"%d","type":"text","text":{"body":"السلام عليكم"}}]}}]}]}"""
                .formatted(pn, name, waId, waId, UUID.randomUUID(), Instant.now().getEpochSecond());
        processor.process(body.getBytes(StandardCharsets.UTF_8));
        return as(() -> jdbc.sql("select c.id from conversations c join contacts k on k.id = c.contact_id where k.wa_id = ?")
                .param(waId).query(UUID.class).single());
    }

    void lead(UUID conversation, String purpose, String type, String districts, Integer budget, String payment, String timeline) {
        TenantContext.run(office, () -> jdbc.sql("""
                insert into leads (conversation_id, office_id, purpose, property_type, districts, budget_max, payment, timeline)
                values (?, ?, ?, ?, ?, ?, ?, ?)""").params(conversation, office, purpose, type, districts, budget, payment, timeline).update());
    }

    <T> T as(java.util.function.Supplier<T> block) {
        return TenantContext.call(office, block);
    }

    @SuppressWarnings("unchecked")
    List<CustomersController.Customer> list(String q, String temperature, String district, String stage) {
        return (List<CustomersController.Customer>) as(() -> customers.list(q, temperature, null, null, district, null, null, null, stage))
                .get("customers");
    }

    @Test
    void listsCustomersWithTheirNeedAndSeriousness() throws Exception {
        UUID a = customer("966551112233", "أبو خالد");
        UUID b = customer("966554445566", "سارة");
        lead(a, "SALE", "فيلا", "حي الملقا، النرجس", 2_500_000, "CASH", "خلال شهر");
        lead(b, "RENT", "شقة", "العارض", null, null, null);
        // A test chat from «جرّب الوكيل» is not a customer.
        TenantContext.run(office, () -> {
            UUID contact = jdbc.sql("insert into contacts (office_id, wa_id) values (?, ?) returning id")
                    .params(office, ConversationStore.SANDBOX).query(UUID.class).single();
            jdbc.sql("insert into conversations (id, office_id, contact_id) values (?, ?, ?)").params(UUID.randomUUID(), office, contact).update();
        });

        List<CustomersController.Customer> all = list(null, null, null, null);
        assertThat(all).hasSize(2);
        CustomersController.Customer khalid = all.stream().filter(c -> c.conversationId().equals(a)).findFirst().orElseThrow();
        assertThat(khalid.name()).isEqualTo("أبو خالد");
        assertThat(khalid.phone()).isEqualTo("0551112233");
        assertThat(khalid.temperature()).isEqualTo("HOT");
        assertThat(khalid.stage()).isEqualTo("NEW");

        // Filters: seriousness, a district spelled without «ال», a phone typed in Arabic digits, a name.
        assertThat(list(null, "HOT", null, null)).extracting(CustomersController.Customer::conversationId).containsExactly(a);
        assertThat(list(null, null, "ملقا", null)).extracting(CustomersController.Customer::conversationId).containsExactly(a);
        assertThat(list("٠٥٥٤٤٤٥٥٦٦", null, null, null)).extracting(CustomersController.Customer::conversationId).containsExactly(b);
        assertThat(list("ساره", null, null, null)).extracting(CustomersController.Customer::conversationId).containsExactly(b);
    }

    @Test
    void stagesMoveForwardByThemselvesAndDealsAreCounted() throws Exception {
        UUID a = customer("966551110001", "عميل أ");
        UUID b = customer("966551110002", "عميل ب");
        // A person from the office replied: contacted, without anyone setting it.
        TenantContext.run(office, () -> jdbc.sql("""
                insert into messages (office_id, conversation_id, direction, sender, type, body)
                values (?, ?, 'OUT', 'HUMAN', 'text', 'أهلاً')""").params(office, a).update());
        CustomersController.Customer ca = list(null, null, null, "CONTACTED").get(0);
        assertThat(ca.conversationId()).isEqualTo(a);
        assertThat(ca.stageAuto()).isTrue();

        // The office closes the deal with its value; the other one did not go through.
        CustomersController.Customer won = as(() -> customers.setStage(a, new CustomersController.StageChange("WON", new BigDecimal("1850000"), "x")));
        assertThat(won.stage()).isEqualTo("WON");
        assertThat(won.dealValue()).isEqualByComparingTo("1850000");
        assertThat(won.lostReason()).isNull();
        CustomersController.Customer lost = as(() -> customers.setStage(b, new CustomersController.StageChange("LOST", new BigDecimal("5"), "اشترى من مكتب آخر")));
        assertThat(lost.dealValue()).isNull();
        assertThat(lost.lostReason()).isEqualTo("اشترى من مكتب آخر");

        Map<String, Object> s = as(() -> customers.summary());
        @SuppressWarnings("unchecked") Map<String, Object> month = (Map<String, Object>) s.get("thisMonth");
        assertThat(month.get("deals")).isEqualTo(1);
        assertThat((BigDecimal) month.get("value")).isEqualByComparingTo("1850000");
        assertThat(s.get("everWon")).isEqualTo(true);

        @SuppressWarnings("unchecked") Map<String, Integer> tabs = (Map<String, Integer>) as(() ->
                customers.list(null, null, null, null, null, null, null, null, "WON")).get("stages");
        assertThat(tabs.get("ALL")).isEqualTo(2);
        assertThat(tabs.get("WON")).isEqualTo(1);
        assertThat(tabs.get("LOST")).isEqualTo(1);

        assertThatThrownBy(() -> as(() -> customers.setStage(a, new CustomersController.StageChange("MAYBE", null, null))))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> as(() -> customers.setStage(UUID.randomUUID(), new CustomersController.StageChange("WON", null, null))))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void exportOpensInExcelAndCannotRunFormulas() throws Exception {
        UUID a = customer("966551119999", "=HYPERLINK(\\\"http://x\\\")");
        lead(a, "SALE", "أرض", "الرمال", 900_000, "FINANCE", null);
        byte[] csv = as(() -> customers.export(null, null, null, null, null, null, null, null, null)).getBody();
        String text = new String(csv, StandardCharsets.UTF_8);
        assertThat(text).startsWith("﻿\"الاسم\"");
        assertThat(text).contains("\"'=HYPERLINK").contains("‎0551119999").contains("\"تمويل\"").contains("\"شراء\"");
    }

    @Test
    void anotherOfficeSeesNoneOfThem() throws Exception {
        customer("966551118888", "عميل");
        UUID other = auth.signup(new AuthService.Signup("آخر", "12345678", "مالك", UUID.randomUUID() + "@cust.sa", "password123")).officeId();
        @SuppressWarnings("unchecked") List<?> theirs = (List<?>) TenantContext.call(other, () ->
                customers.list(null, null, null, null, null, null, null, null, null)).get("customers");
        assertThat(theirs).isEmpty();
    }
}
