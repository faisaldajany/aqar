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

/** New customers shared across marketers, unanswered ones moved on, and each marketer's numbers. */
class RoutingTest extends IntegrationTest {

    @Autowired AuthService auth;
    @Autowired EmbeddedSignupService signup;
    @Autowired WebhookProcessor processor;
    @Autowired MarketerStore marketers;
    @Autowired HandoffRouting routing;
    @Autowired JdbcClient jdbc;

    UUID office, first, second;
    String pn;

    @BeforeEach
    void setUp() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        office = auth.signup(new AuthService.Signup("مكتب " + tag, "12345678", "مالك", tag + "@route.sa", "password123")).officeId();
        pn = "PN-R-" + tag;
        TenantContext.run(office, () -> signup.complete(new EmbeddedSignupService.Request(null, null, pn, true)));
        first = as(() -> marketers.insert(office, "خالد", "966551840001", List.of("الملقا")));
        second = as(() -> marketers.insert(office, "سارة", "966551840002", List.of("الملقا", "النرجس")));
    }

    <T> T as(java.util.function.Supplier<T> block) {
        return TenantContext.call(office, block);
    }

    UUID conversation(String waId) throws Exception {
        String body = """
                {"object":"whatsapp_business_account","entry":[{"id":"W","changes":[{"field":"messages","value":{
                "messaging_product":"whatsapp","metadata":{"phone_number_id":"%s"},
                "contacts":[{"profile":{"name":"عميل"},"wa_id":"%s"}],
                "messages":[{"from":"%s","id":"wamid.%s","timestamp":"%d","type":"text","text":{"body":"أبي فيلا في الملقا"}}]}}]}]}"""
                .formatted(pn, waId, waId, UUID.randomUUID(), Instant.now().getEpochSecond());
        processor.process(body.getBytes(StandardCharsets.UTF_8));
        UUID id = as(() -> jdbc.sql("select c.id from conversations c join contacts k on k.id = c.contact_id where k.wa_id = ?")
                .param(waId).query(UUID.class).single());
        TenantContext.run(office, () -> jdbc.sql("insert into leads (conversation_id, office_id, districts) values (?, ?, 'الملقا')")
                .params(id, office).update());
        return id;
    }

    /** A handoff given to a marketer {@code minutesAgo}. */
    UUID handoff(UUID conversation, UUID marketer, int minutesAgo) {
        return as(() -> {
            UUID h = jdbc.sql("""
                    insert into handoffs (office_id, conversation_id, marketer_id, reason, summary, assigned_at, created_at)
                    values (?, ?, ?, 'SERIOUS', 'يبحث عن فيلا', now() - make_interval(mins => ?), now() - make_interval(mins => ?))
                    returning id""").params(office, conversation, marketer, minutesAgo, minutesAgo).query(UUID.class).single();
            jdbc.sql("insert into handoff_assignments (office_id, handoff_id, marketer_id, assigned_at) values (?, ?, ?, now() - make_interval(mins => ?))")
                    .params(office, h, marketer, minutesAgo).update();
            return h;
        });
    }

    UUID marketerOf(UUID handoff) {
        return as(() -> jdbc.sql("select marketer_id from handoffs where id = ?").param(handoff).query(UUID.class).single());
    }

    List<String> alerts() {
        return as(() -> jdbc.sql("select kind from office_alerts").query(String.class).list());
    }

    @Test
    void newCustomersGoToWhoeverHasFewerOpen() throws Exception {
        assertThat(as(() -> marketers.forDistrict("الملقا")).orElseThrow().id()).isEqualTo(first);
        handoff(conversation("966557770001"), first, 1);
        assertThat(as(() -> marketers.forDistrict("الملقا")).orElseThrow().id()).isEqualTo(second);
        // Only سارة covers النرجس, however busy she is.
        handoff(conversation("966557770002"), second, 1);
        handoff(conversation("966557770003"), second, 1);
        assertThat(as(() -> marketers.forDistrict("النرجس")).orElseThrow().id()).isEqualTo(second);
    }

    @Test
    void unansweredHandoffsMoveOnThenTheOfficeIsTold() throws Exception {
        UUID c = conversation("966557770101");
        UUID h = handoff(c, first, 20);
        assertThat(as(() -> routing.reassignStale())).as("off until the office sets a time").isZero();

        as(() -> routing.setTimeout(15));
        assertThat(as(() -> routing.reassignStale())).isEqualTo(1);
        assertThat(marketerOf(h)).isEqualTo(second);
        assertThat(alerts()).contains("HANDOFF_MOVED");
        assertThat(as(() -> jdbc.sql("select ended_reason from handoff_assignments where handoff_id = ? and marketer_id = ?")
                .params(h, first).query(String.class).single())).isEqualTo("TIMEOUT");

        // سارة does not answer either, and nobody is left: the office is told, and it stops there.
        TenantContext.run(office, () -> jdbc.sql("update handoffs set assigned_at = now() - interval '20 minutes' where id = ?").param(h).update());
        assertThat(as(() -> routing.reassignStale())).isZero();
        assertThat(alerts()).contains("HANDOFF_UNANSWERED");
        assertThat(as(() -> routing.reassignStale())).isZero();
    }

    @Test
    void aReplyInTheChatCountsAsAnswered() throws Exception {
        UUID c = conversation("966557770201");
        UUID h = handoff(c, first, 20);
        TenantContext.run(office, () -> jdbc.sql("""
                insert into messages (office_id, conversation_id, direction, sender, type, body, created_at)
                values (?, ?, 'OUT', 'HUMAN', 'text', 'هلا، معك خالد', now() - interval '16 minutes')""").params(office, c).update());
        as(() -> routing.setTimeout(15));
        assertThat(as(() -> routing.reassignStale())).isZero();
        assertThat(marketerOf(h)).isEqualTo(first);

        Map<String, Object> khalid = as(() -> routing.performance(30)).stream()
                .filter(m -> first.equals(m.get("marketerId"))).findFirst().orElseThrow();
        assertThat(khalid.get("received")).isEqualTo(1);
        assertThat(khalid.get("answered")).isEqualTo(1);
        assertThat((Double) khalid.get("medianAnswerMinutes")).isBetween(3.5, 4.5);
        assertThat(khalid.get("movedOn")).isEqualTo(0);
    }
}
