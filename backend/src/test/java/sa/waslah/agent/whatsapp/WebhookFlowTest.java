package sa.waslah.agent.whatsapp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import sa.waslah.agent.IntegrationTest;
import sa.waslah.agent.auth.AuthService;
import sa.waslah.agent.common.ApiException;
import sa.waslah.agent.conversation.ConversationStore.Sender;
import sa.waslah.agent.conversation.InboundMessageEvent;
import sa.waslah.agent.tenant.TenantContext;

@RecordApplicationEvents
class WebhookFlowTest extends IntegrationTest {

    @Autowired TestRestTemplate http;
    @Autowired AuthService auth;
    @Autowired EmbeddedSignupService signup;
    @Autowired WebhookSignatureVerifier verifier;
    @Autowired WebhookProcessor processor;
    @Autowired OutboundMessageService outbound;
    @Autowired WhatsAppClient client;
    @Autowired JdbcClient jdbc;
    @Autowired ApplicationEvents events;

    UUID office;
    String phoneNumberId;
    final String customer = "966551234567";

    @BeforeEach
    void connectOffice() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        office = auth.signup(new AuthService.Signup("مكتب " + tag, "12345678", "مالك", tag + "@test.sa", "password123")).officeId();
        phoneNumberId = "PN-" + tag;
        TenantContext.run(office, () -> signup.complete(new EmbeddedSignupService.Request(null, null, phoneNumberId, true)));
        ((MockWhatsAppClient) client).reset();
    }

    @Test
    void verificationHandshake() {
        String ok = http.getForObject("/webhooks/whatsapp?hub.mode=subscribe&hub.verify_token=test-verify-token&hub.challenge=42", String.class);
        assertThat(ok).isEqualTo("42");
        assertThat(http.getForEntity("/webhooks/whatsapp?hub.mode=subscribe&hub.verify_token=wrong&hub.challenge=42", String.class)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void unsignedWebhookIsRejected() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.add("X-Hub-Signature-256", "sha256=00");
        byte[] body = inbound("wamid.A", "hello").getBytes(StandardCharsets.UTF_8);
        assertThat(http.postForEntity("/webhooks/whatsapp", new HttpEntity<>(body, h), Void.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(messageCount()).isZero();
    }

    @Test
    void signedInboundIsStoredOnceEvenWhenRetried() {
        byte[] body = inbound("wamid.B", "أبغى فيلا في النرجس").getBytes(StandardCharsets.UTF_8);
        assertThat(post(body)).isEqualTo(HttpStatus.OK);
        assertThat(post(body)).isEqualTo(HttpStatus.OK);
        assertThat(messageCount()).isEqualTo(1);
        String name = TenantContext.call(office, () ->
                jdbc.sql("select profile_name from contacts where wa_id = ?").param(customer).query(String.class).single());
        assertThat(name).isEqualTo("أبو عبدالله");
    }

    @Test
    void newCustomerMessagePublishesAgentEventOnce() throws Exception {
        processor.process(inbound("wamid.C", "السلام عليكم").getBytes(StandardCharsets.UTF_8));
        processor.process(inbound("wamid.C", "السلام عليكم").getBytes(StandardCharsets.UTF_8));
        assertThat(events.stream(InboundMessageEvent.class).filter(e -> e.officeId().equals(office)).count()).isEqualTo(1);
    }

    @Test
    void marketerReplyFromAppPausesTheAgent() throws Exception {
        processor.process(inbound("wamid.D", "كم السعر؟").getBytes(StandardCharsets.UTF_8));
        processor.process(echo("wamid.E", "هلا، معك خالد").getBytes(StandardCharsets.UTF_8));
        events.clear();
        processor.process(inbound("wamid.F", "تمام").getBytes(StandardCharsets.UTF_8));

        assertThat(messageCount()).isEqualTo(3);
        assertThat(events.stream(InboundMessageEvent.class).count()).as("agent stays silent after a human reply").isZero();
        String sender = TenantContext.call(office, () ->
                jdbc.sql("select sender from messages where wa_message_id = 'wamid.E'").query(String.class).single());
        assertThat(sender).isEqualTo("HUMAN");
    }

    @Test
    void outboundWithinWindowIsSentAndStored() throws Exception {
        processor.process(inbound("wamid.G", "مرحبا").getBytes(StandardCharsets.UTF_8));
        UUID conversation = conversationId();
        TenantContext.run(office, () -> outbound.sendText(conversation, "هلا وغلا", Sender.AGENT));

        assertThat(((MockWhatsAppClient) client).sent()).hasSize(1);
        assertThat(((MockWhatsAppClient) client).sent().get(0).to()).isEqualTo(customer);
        assertThat(messageCount()).isEqualTo(2);
    }

    @Test
    void outboundOutsideWindowIsRefused() throws Exception {
        long old = Instant.now().minusSeconds(25 * 3600).getEpochSecond();
        processor.process(inbound("wamid.H", "قديمة", old).getBytes(StandardCharsets.UTF_8));
        UUID conversation = conversationId();
        assertThatThrownBy(() -> TenantContext.run(office, () -> outbound.sendText(conversation, "متأخر", Sender.AGENT)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code()).isEqualTo("window_closed");
        assertThat(((MockWhatsAppClient) client).sent()).isEmpty();
    }

    @Test
    void deliveryStatusUpdatesTheMessage() throws Exception {
        processor.process(inbound("wamid.I", "مرحبا").getBytes(StandardCharsets.UTF_8));
        processor.process(status("wamid.I", "read").getBytes(StandardCharsets.UTF_8));
        String st = TenantContext.call(office, () ->
                jdbc.sql("select status from messages where wa_message_id = 'wamid.I'").query(String.class).single());
        assertThat(st).isEqualTo("read");
    }

    private HttpStatus post(byte[] body) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.add("X-Hub-Signature-256", verifier.header(body));
        return HttpStatus.valueOf(http.postForEntity("/webhooks/whatsapp", new HttpEntity<>(body, h), Void.class)
                .getStatusCode().value());
    }

    private int messageCount() {
        return TenantContext.call(office, () -> jdbc.sql("select count(*) from messages").query(Integer.class).single());
    }

    private UUID conversationId() {
        return TenantContext.call(office, () -> jdbc.sql("select id from conversations").query(UUID.class).single());
    }

    private String inbound(String wamid, String text) {
        return inbound(wamid, text, Instant.now().getEpochSecond());
    }

    private String inbound(String wamid, String text, long ts) {
        return """
                {"object":"whatsapp_business_account","entry":[{"id":"WABA","changes":[{"field":"messages","value":{
                  "messaging_product":"whatsapp",
                  "metadata":{"display_phone_number":"966500000000","phone_number_id":"%s"},
                  "contacts":[{"profile":{"name":"أبو عبدالله"},"wa_id":"%s"}],
                  "messages":[{"from":"%s","id":"%s","timestamp":"%d","type":"text","text":{"body":"%s"}}]
                }}]}]}""".formatted(phoneNumberId, customer, customer, wamid, ts, text);
    }

    private String echo(String wamid, String text) {
        return """
                {"object":"whatsapp_business_account","entry":[{"id":"WABA","changes":[{"field":"smb_message_echoes","value":{
                  "messaging_product":"whatsapp",
                  "metadata":{"display_phone_number":"966500000000","phone_number_id":"%s"},
                  "message_echoes":[{"from":"966500000000","to":"%s","id":"%s","timestamp":"%d","type":"text","text":{"body":"%s"}}]
                }}]}]}""".formatted(phoneNumberId, customer, wamid, Instant.now().getEpochSecond(), text);
    }

    private String status(String wamid, String st) {
        return """
                {"object":"whatsapp_business_account","entry":[{"id":"WABA","changes":[{"field":"messages","value":{
                  "messaging_product":"whatsapp",
                  "metadata":{"display_phone_number":"966500000000","phone_number_id":"%s"},
                  "statuses":[{"id":"%s","status":"%s","timestamp":"%d","recipient_id":"%s"}]
                }}]}]}""".formatted(phoneNumberId, wamid, st, Instant.now().getEpochSecond(), customer);
    }
}
