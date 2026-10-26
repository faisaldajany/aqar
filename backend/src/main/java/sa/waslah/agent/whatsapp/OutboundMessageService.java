package sa.waslah.agent.whatsapp;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import sa.waslah.agent.common.ApiException;
import sa.waslah.agent.conversation.ConversationStore;
import sa.waslah.agent.conversation.ConversationStore.Sender;

/**
 * Sends a free-form text into a conversation for the current office, enforcing WhatsApp's
 * 24-hour window. Used by the agent (Sender.AGENT) and by marketers replying from the portal
 * (Sender.HUMAN, which also takes the conversation over from the agent).
 */
@Service
public class OutboundMessageService {

    private final ConversationStore store;
    private final WaAccounts accounts;
    private final WhatsAppClient client;
    private final MessagingWindowPolicy window;
    private final TransactionTemplate tx;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public OutboundMessageService(ConversationStore store, WaAccounts accounts, WhatsAppClient client,
                                  MessagingWindowPolicy window, TransactionTemplate tx) {
        this(store, accounts, client, window, tx, Clock.systemUTC());
    }

    OutboundMessageService(ConversationStore store, WaAccounts accounts, WhatsAppClient client,
                           MessagingWindowPolicy window, TransactionTemplate tx, Clock clock) {
        this.store = store;
        this.accounts = accounts;
        this.client = client;
        this.window = window;
        this.tx = tx;
        this.clock = clock;
    }

    public UUID sendText(UUID conversationId, String text, Sender sender) {
        if (text == null || text.isBlank()) {
            throw ApiException.badRequest("empty_message", "اكتب نص الرسالة.");
        }
        Instant now = clock.instant();
        ConversationStore.ConversationRef conv = store.find(conversationId)
                .orElseThrow(() -> ApiException.notFound("no_conversation", "المحادثة غير موجودة."));
        if (!window.isOpen(conv.lastInboundAt(), now)) {
            throw ApiException.conflict("window_closed",
                    "مضى أكثر من 24 ساعة على آخر رسالة من العميل، ولا يسمح واتساب إلا برسالة قالب معتمد.");
        }
        if (ConversationStore.SANDBOX.equals(conv.waId())) {
            return storeSandbox(conversationId, "text", text.trim(), null, sender, now);
        }
        if (conv.waAccountId() == null) {
            throw ApiException.conflict("no_whatsapp", "لا يوجد رقم واتساب مربوط بهذه المحادثة.");
        }
        WaAccounts.Account account = accounts.byId(conv.waAccountId())
                .orElseThrow(() -> ApiException.conflict("no_whatsapp", "رقم واتساب غير مربوط."));

        // Send first: if Meta rejects, nothing is stored as sent.
        String wamid = client.sendText(accounts.credentials(account), conv.waId(), text.trim());

        return tx.execute(s -> {
            UUID id = store.insertMessage(account.officeId(), conversationId, wamid, "OUT", sender, "text",
                    text.trim(), null, "sent", now).orElseThrow();
            store.touchOutbound(conversationId, now);
            if (sender == Sender.HUMAN) {
                store.pauseAgent(conversationId, ConversationStore.TAKEN_OVER);
            }
            return id;
        });
    }

    /** Sends a non-text message (map pin or image) from the agent, inside the 24-hour window. */
    public UUID sendMedia(UUID conversationId, String type, String description,
                          java.util.function.BiFunction<WhatsAppClient.Credentials, String, String> send) {
        return sendMedia(conversationId, type, description, null, send);
    }

    /** @param mediaRef what the portal shows for it: an image URL or path, or "geo:lat,lng" for a location */
    public UUID sendMedia(UUID conversationId, String type, String description, String mediaRef,
                          java.util.function.BiFunction<WhatsAppClient.Credentials, String, String> send) {
        Instant now = clock.instant();
        ConversationStore.ConversationRef conv = store.find(conversationId)
                .orElseThrow(() -> ApiException.notFound("no_conversation", "المحادثة غير موجودة."));
        if (!window.isOpen(conv.lastInboundAt(), now)) {
            throw ApiException.conflict("window_closed", "انتهت نافذة 24 ساعة.");
        }
        if (ConversationStore.SANDBOX.equals(conv.waId())) {
            return storeSandbox(conversationId, type, description, mediaRef, Sender.AGENT, now);
        }
        WaAccounts.Account account = accounts.byId(conv.waAccountId())
                .orElseThrow(() -> ApiException.conflict("no_whatsapp", "رقم واتساب غير مربوط."));
        String wamid = send.apply(accounts.credentials(account), conv.waId());
        return tx.execute(s -> {
            UUID id = store.insertMessage(account.officeId(), conversationId, wamid, "OUT", Sender.AGENT, type,
                    description, mediaRef, "sent", now).orElseThrow();
            store.touchOutbound(conversationId, now);
            return id;
        });
    }

    /** The office's test chat: store the reply as if sent; nothing goes to WhatsApp. */
    private UUID storeSandbox(UUID conversationId, String type, String body, String mediaRef, Sender sender, Instant now) {
        UUID office = sa.waslah.agent.tenant.TenantContext.require();
        return tx.execute(s -> {
            UUID id = store.insertMessage(office, conversationId, "sandbox-" + UUID.randomUUID(), "OUT", sender, type,
                    body, mediaRef, "sent", now).orElseThrow();
            store.touchOutbound(conversationId, now);
            return id;
        });
    }

    public WhatsAppClient client() {
        return client;
    }
}
