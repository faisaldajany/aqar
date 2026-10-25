package sa.waslah.agent.whatsapp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import sa.waslah.agent.config.WaslahProperties;
import sa.waslah.agent.conversation.ConversationStore;
import sa.waslah.agent.conversation.ConversationStore.Sender;
import sa.waslah.agent.conversation.InboundMessageEvent;
import sa.waslah.agent.tenant.TenantContext;

/**
 * Turns a WhatsApp Business Account webhook into stored messages.
 * Handles three change fields:
 *   messages            customer messages and delivery statuses
 *   smb_message_echoes  messages a marketer sent from the WhatsApp Business app (coexistence);
 *                       these pause the agent in that conversation
 * Retries are safe: messages are de-duplicated on (office_id, wa_message_id).
 */
@Service
public class WebhookProcessor {

    private static final Logger log = LoggerFactory.getLogger(WebhookProcessor.class);

    private final ObjectMapper json;
    private final WaAccounts accounts;
    private final ConversationStore store;
    private final TransactionTemplate tx;
    private final ApplicationEventPublisher events;
    private final JdbcClient jdbc;
    private final WaslahProperties props;

    public WebhookProcessor(ObjectMapper json, WaAccounts accounts, ConversationStore store, TransactionTemplate tx,
                            ApplicationEventPublisher events, JdbcClient jdbc, WaslahProperties props) {
        this.json = json;
        this.accounts = accounts;
        this.store = store;
        this.tx = tx;
        this.events = events;
        this.jdbc = jdbc;
        this.props = props;
    }

    public void process(byte[] body) throws IOException {
        JsonNode root = json.readTree(body);
        for (JsonNode entry : root.path("entry")) {
            for (JsonNode change : entry.path("changes")) {
                String field = change.path("field").asText();
                JsonNode value = change.path("value");
                String phoneNumberId = value.path("metadata").path("phone_number_id").asText("");
                Optional<UUID> office = accounts.officeForPhoneNumberId(phoneNumberId);
                logRaw(office.orElse(null), field, change);
                if (office.isEmpty()) {
                    log.warn("Webhook for unknown phone_number_id {} (field {})", phoneNumberId, field);
                    continue;
                }
                List<InboundMessageEvent> toPublish = TenantContext.call(office.get(),
                        () -> tx.execute(s -> handle(office.get(), phoneNumberId, field, value)));
                // Published after commit so listeners always see the stored message.
                toPublish.forEach(events::publishEvent);
            }
        }
    }

    private List<InboundMessageEvent> handle(UUID officeId, String phoneNumberId, String field, JsonNode value) {
        List<InboundMessageEvent> out = new ArrayList<>();
        UUID accountId = accounts.byPhoneNumberId(phoneNumberId).map(WaAccounts.Account::id).orElse(null);
        switch (field) {
            case "messages" -> {
                Map<String, String> names = new HashMap<>();
                for (JsonNode c : value.path("contacts")) {
                    names.put(c.path("wa_id").asText(), c.path("profile").path("name").asText(null));
                }
                for (JsonNode m : value.path("messages")) {
                    onCustomerMessage(officeId, accountId, m, names.get(m.path("from").asText())).ifPresent(out::add);
                }
                for (JsonNode st : value.path("statuses")) {
                    String error = st.path("errors").isArray() && !st.path("errors").isEmpty()
                            ? st.path("errors").get(0).path("title").asText(null) : null;
                    store.updateStatus(st.path("id").asText(), st.path("status").asText(), error);
                }
            }
            case "smb_message_echoes" -> {
                for (JsonNode m : value.path("message_echoes")) {
                    onHumanEcho(officeId, accountId, m);
                }
            }
            default -> log.debug("Ignoring webhook field {}", field);
        }
        return out;
    }

    private Optional<InboundMessageEvent> onCustomerMessage(UUID officeId, UUID accountId, JsonNode m, String name) {
        String waId = m.path("from").asText();
        Instant at = Instant.ofEpochSecond(m.path("timestamp").asLong(Instant.now().getEpochSecond()));
        Content c = content(m);
        UUID contactId = store.upsertContact(officeId, waId, name);
        ConversationStore.ConversationRef conv = store.upsertConversation(officeId, contactId, accountId, at, true);
        Optional<UUID> messageId = store.insertMessage(officeId, conv.id(), m.path("id").asText(), "IN",
                Sender.CUSTOMER, c.type(), c.body(), c.mediaId(), "received", at);
        if (messageId.isEmpty() || !conv.agentActive(Instant.now())) {
            return Optional.empty();
        }
        return Optional.of(new InboundMessageEvent(officeId, conv.id(), messageId.get()));
    }

    private void onHumanEcho(UUID officeId, UUID accountId, JsonNode m) {
        String customerWaId = m.path("to").asText();
        Instant at = Instant.ofEpochSecond(m.path("timestamp").asLong(Instant.now().getEpochSecond()));
        Content c = content(m);
        UUID contactId = store.upsertContact(officeId, customerWaId, null);
        ConversationStore.ConversationRef conv = store.upsertConversation(officeId, contactId, accountId, at, false);
        Optional<UUID> inserted = store.insertMessage(officeId, conv.id(), m.path("id").asText(), "OUT",
                Sender.HUMAN, c.type(), c.body(), c.mediaId(), "sent", at);
        // Only a genuinely new app-sent message pauses the agent; our own API sends are already stored.
        if (inserted.isPresent()) {
            store.pauseAgent(conv.id(), ConversationStore.humanPauseUntil(Instant.now(), props.agent().humanPauseHours()));
        }
    }

    record Content(String type, String body, String mediaId) {
    }

    static Content content(JsonNode m) {
        String type = m.path("type").asText("unknown");
        return switch (type) {
            case "text" -> new Content(type, m.path("text").path("body").asText(""), null);
            case "audio", "voice" -> new Content("audio", null, m.path(type).path("id").asText(null));
            case "image", "video", "document", "sticker" ->
                    new Content(type, m.path(type).path("caption").asText(null), m.path(type).path("id").asText(null));
            case "location" -> new Content(type,
                    m.path("location").path("latitude").asText() + "," + m.path("location").path("longitude").asText(), null);
            case "button" -> new Content(type, m.path("button").path("text").asText(null), null);
            case "interactive" -> {
                JsonNode i = m.path("interactive");
                String title = i.path("button_reply").path("title").asText(i.path("list_reply").path("title").asText(null));
                yield new Content(type, title, null);
            }
            default -> new Content(type, null, null);
        };
    }

    private void logRaw(UUID officeId, String field, JsonNode change) {
        try {
            jdbc.sql("insert into webhook_events (office_id, field, payload) values (?::uuid, ?, ?::jsonb)")
                    .params(officeId, field, json.writeValueAsString(change))
                    .update();
        } catch (Exception e) {
            log.warn("Could not log webhook event: {}", e.getMessage());
        }
    }
}
