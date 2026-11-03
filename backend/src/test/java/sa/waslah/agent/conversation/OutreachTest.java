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

/** A new listing told to the customers it fits, «إيقاف» respected, and check-ins with quiet serious customers. */
class OutreachTest extends IntegrationTest {

    @Autowired AuthService auth;
    @Autowired EmbeddedSignupService signup;
    @Autowired WebhookProcessor processor;
    @Autowired CustomerOutreach outreach;
    @Autowired JdbcClient jdbc;

    UUID office;
    String pn;
    UUID villa;

    @BeforeEach
    void setUp() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        office = auth.signup(new AuthService.Signup("مكتب " + tag, "12345678", "مالك", tag + "@out.sa", "password123")).officeId();
        pn = "PN-O-" + tag;
        TenantContext.run(office, () -> signup.complete(new EmbeddedSignupService.Request(null, null, pn, true)));
        villa = as(() -> jdbc.sql("""
                insert into listings (office_id, title, district, property_type, purpose, price, ad_license, license_expiry)
                values (?, 'فيلا درج داخلي', 'الملقا', 'فيلا', 'SALE', 2100000, '7100000001', current_date + 90) returning id""")
                .param(office).query(UUID.class).single());
    }

    <T> T as(java.util.function.Supplier<T> block) {
        return TenantContext.call(office, block);
    }

    void message(String waId, String text) throws Exception {
        String body = """
                {"object":"whatsapp_business_account","entry":[{"id":"W","changes":[{"field":"messages","value":{
                "messaging_product":"whatsapp","metadata":{"phone_number_id":"%s"},
                "contacts":[{"profile":{"name":"عميل %s"},"wa_id":"%s"}],
                "messages":[{"from":"%s","id":"wamid.%s","timestamp":"%d","type":"text","text":{"body":"%s"}}]}}]}]}"""
                .formatted(pn, waId.substring(9), waId, waId, UUID.randomUUID(), Instant.now().getEpochSecond(), text);
        processor.process(body.getBytes(StandardCharsets.UTF_8));
    }

    UUID customer(String waId, String purpose, String type, String districts, Integer budget, String payment, String timeline) throws Exception {
        message(waId, "السلام عليكم");
        UUID id = as(() -> jdbc.sql("select c.id from conversations c join contacts k on k.id = c.contact_id where k.wa_id = ?")
                .param(waId).query(UUID.class).single());
        TenantContext.run(office, () -> jdbc.sql("""
                insert into leads (conversation_id, office_id, purpose, property_type, districts, budget_max, payment, timeline)
                values (?, ?, ?, ?, ?, ?, ?, ?)""").params(id, office, purpose, type, districts, budget, payment, timeline).update());
        return id;
    }

    void quietFor(UUID conversation, int days) {
        TenantContext.run(office, () -> jdbc.sql("""
                update conversations set last_inbound_at = now() - make_interval(days => ?),
                       last_message_at = now() - make_interval(days => ?) where id = ?""").params(days, days, conversation).update());
    }

    void approve(String kind) {
        TenantContext.run(office, () -> jdbc.sql("update wa_templates set status = 'APPROVED' where kind = ?").param(kind).update());
    }

    @Test
    void aNewListingFindsTheCustomersItFits() throws Exception {
        UUID within = customer("966550000101", "SALE", "فيلا", "حي الملقا، النرجس", 2_000_000, "CASH", "خلال شهر");
        UUID noBudget = customer("966550000102", "SALE", "فيلا", "ملقا", null, null, null);
        customer("966550000103", "RENT", "فيلا", "الملقا", 200_000, null, null);      // wants to rent
        customer("966550000104", "SALE", "فيلا", "العارض", 3_000_000, null, null);    // another district
        customer("966550000105", "SALE", "فيلا", "الملقا", 1_000_000, null, null);    // far over budget
        customer("966550000106", "SALE", "شقة", "الملقا", 3_000_000, null, null);     // another type

        List<CustomerOutreach.Match> m = as(() -> outreach.matches(villa));
        assertThat(m).extracting(CustomerOutreach.Match::conversationId).containsExactly(within, noBudget);
        assertThat(m.get(0).why()).anyMatch(w -> w.contains("أعلى من ميزانيته بـ5٪"));
        assertThat(m.get(1).why()).contains("لم يذكر ميزانيته");
        assertThat(m).allMatch(CustomerOutreach.Match::suggested);
        assertThat(as(() -> outreach.counts())).containsEntry(villa, 2);

        // «إيقاف»: never messaged again.
        message("966550000102", "إيقاف");
        assertThat(as(() -> outreach.matches(villa))).extracting(CustomerOutreach.Match::conversationId).containsExactly(within);
        assertThat(CustomerOutreach.isStop("ايقاف!")).isTrue();
        assertThat(CustomerOutreach.isStop("ما أبي أوقف البحث")).isFalse();
    }

    @Test
    void sendingInsideAndOutsideTheWindow() throws Exception {
        UUID recent = customer("966550000201", "SALE", "فيلا", "الملقا", 2_500_000, "CASH", null);
        UUID older = customer("966550000202", "SALE", "فيلا", "الملقا", 2_500_000, "FINANCE", null);
        quietFor(older, 3);

        List<CustomerOutreach.Result> r = as(() -> outreach.send(villa, List.of(recent, older)));
        // Wrote today: a normal message. Three days ago: needs the template, which Meta has not approved yet.
        assertThat(r.get(0).sent()).isTrue();
        assertThat(r.get(0).via()).isEqualTo("TEXT");
        assertThat(r.get(1).sent()).isFalse();
        assertThat(r.get(1).error()).contains("Meta");
        String text = as(() -> jdbc.sql("select body from messages where conversation_id = ? and direction = 'OUT' order by created_at desc limit 1")
                .param(recent).query(String.class).single());
        assertThat(text).contains("فيلا درج داخلي").contains("2,100,000 ريال");

        approve("NEW_LISTING");
        r = as(() -> outreach.send(villa, List.of(recent, older)));
        assertThat(r.get(0).sent()).isFalse();
        assertThat(r.get(0).error()).contains("من قبل");
        assertThat(r.get(1).sent()).isTrue();
        assertThat(r.get(1).via()).isEqualTo("TEMPLATE");
        assertThat(as(() -> jdbc.sql("select type from messages where conversation_id = ? and direction = 'OUT' order by created_at desc limit 1")
                .param(older).query(String.class).single())).isEqualTo("template");
        assertThat(as(() -> outreach.counts())).doesNotContainKey(villa);
        // Both were told: shown, not ticked.
        assertThat(as(() -> outreach.matches(villa))).allMatch(CustomerOutreach.Match::alreadySent).noneMatch(CustomerOutreach.Match::suggested);
    }

    @Test
    void quietSeriousCustomersGetOneCheckIn() throws Exception {
        UUID serious = customer("966550000301", "SALE", "فيلا", "الملقا", 2_500_000, "CASH", "خلال شهر");
        UUID unknown = customer("966550000302", "SALE", "فيلا", "الملقا", null, null, null);   // not serious enough
        UUID fresh = customer("966550000303", "SALE", "فيلا", "الملقا", 2_500_000, "CASH", "خلال شهر"); // still talking
        quietFor(serious, 4);
        quietFor(unknown, 4);
        approve("CHECKIN");

        assertThat(as(() -> outreach.checkIns())).as("off until the office turns it on").isZero();
        Map<String, Object> s = as(() -> outreach.setFollowUpDays(3));
        assertThat(s.get("days")).isEqualTo(3);
        assertThat(as(() -> outreach.checkIns())).isEqualTo(1);
        assertThat(as(() -> jdbc.sql("select checked_in_at is not null from conversations where id = ?").param(serious)
                .query(Boolean.class).single())).isTrue();
        assertThat(as(() -> jdbc.sql("select checked_in_at is null from conversations where id = ?").param(fresh)
                .query(Boolean.class).single())).isTrue();
        // Once per quiet spell.
        assertThat(as(() -> outreach.checkIns())).isZero();
    }
}
