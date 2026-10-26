package sa.waslah.agent.conversation;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import sa.waslah.agent.common.ApiException;
import sa.waslah.agent.privacy.PrivacyService;
import sa.waslah.agent.tenant.TenantContext;

/**
 * "جرّب الوكيل": the office chats with its own agent from the portal, with its real listings, marketers and
 * booking rules, before (or without) connecting WhatsApp. Replies are stored, never sent.
 */
@RestController
public class SandboxController {

    public record Send(String text) {
    }

    private final JdbcClient jdbc;
    private final ConversationStore store;
    private final ApplicationEventPublisher events;
    private final PrivacyService privacy;

    public SandboxController(JdbcClient jdbc, ConversationStore store, ApplicationEventPublisher events, PrivacyService privacy) {
        this.jdbc = jdbc;
        this.store = store;
        this.events = events;
        this.privacy = privacy;
    }

    @GetMapping("/api/sandbox")
    public Map<String, Object> get() {
        Map<String, Object> out = new LinkedHashMap<>();
        Optional<UUID> id = current();
        out.put("conversationId", id.orElse(null));
        out.put("messages", id.map(c -> store.messages(c, 100)).orElse(List.of()));
        out.put("agentActive", id.flatMap(store::find).map(c -> c.agentActive(Instant.now())).orElse(true));
        return out;
    }

    @PostMapping("/api/sandbox/messages")
    public Map<String, Object> send(@RequestBody Send s) {
        String text = s.text() == null ? "" : s.text().strip();
        if (text.isEmpty()) throw ApiException.badRequest("empty_message", "اكتب رسالة.");
        if (text.length() > 2000) throw ApiException.badRequest("too_long", "الرسالة طويلة.");
        UUID office = TenantContext.require();
        Instant now = Instant.now();
        UUID contact = store.upsertContact(office, ConversationStore.SANDBOX, "تجربة المكتب");
        ConversationStore.ConversationRef conv = store.upsertConversation(office, contact, null, now, true);
        UUID msg = store.insertMessage(office, conv.id(), "sandbox-in-" + UUID.randomUUID(), "IN",
                ConversationStore.Sender.CUSTOMER, "text", text, null, "received", now).orElseThrow();
        events.publishEvent(new InboundMessageEvent(office, conv.id(), msg));
        return Map.of("conversationId", conv.id());
    }

    /** Starts the test chat over: messages, request card and test bookings are erased. */
    @PostMapping("/api/sandbox/reset")
    public Map<String, Object> reset() {
        current().ifPresent(c -> {
            jdbc.sql("update bookings set status = 'CANCELLED' where conversation_id = ? and status in ('PENDING_APPROVAL','CONFIRMED')")
                    .param(c).update();
            privacy.erase(c);
        });
        return Map.of("ok", true);
    }

    private Optional<UUID> current() {
        return jdbc.sql("""
                        select c.id from conversations c join contacts k on k.id = c.contact_id
                        where k.wa_id = ? order by c.created_at desc limit 1""")
                .param(ConversationStore.SANDBOX).query(UUID.class).optional();
    }
}
