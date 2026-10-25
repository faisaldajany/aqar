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
}
