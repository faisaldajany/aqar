package sa.waslah.agent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import sa.waslah.agent.agent.llm.Llm;
import sa.waslah.agent.common.ApiException;
import sa.waslah.agent.config.WaslahProperties;
import sa.waslah.agent.conversation.ConversationStore;
import sa.waslah.agent.conversation.ConversationStore.Sender;
import sa.waslah.agent.conversation.InboundMessageEvent;
import sa.waslah.agent.listing.ListingStore;
import sa.waslah.agent.listing.MarketerStore;
import sa.waslah.agent.tenant.TenantContext;
import sa.waslah.agent.whatsapp.OutboundMessageService;
import sa.waslah.agent.whatsapp.WaAccounts;
import sa.waslah.agent.whatsapp.WhatsAppClient;

/**
 * Answers new customer messages. Runs off the webhook thread, under the message's office.
 * Waits briefly so a burst of messages gets one reply, and stays silent when a human has taken over.
 */
@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);
    private static final ZoneId RIYADH = ZoneId.of("Asia/Riyadh");

    private final WaslahProperties props;
    private final AgentBrain brain;
    private final ConversationStore conversations;
    private final ListingStore listings;
    private final MarketerStore marketers;
    private final OutboundMessageService outbound;
    private final WaAccounts accounts;
    private final WhatsAppClient whatsapp;
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final ConcurrentHashMap<UUID, ReentrantLock> locks = new ConcurrentHashMap<>();
    private final sa.waslah.agent.billing.PlanLimits plans;
    private final sa.waslah.agent.booking.BookingService bookingService;
    private final sa.waslah.agent.booking.BookingStore bookingStore;
    private final sa.waslah.agent.office.OfficeAlerts alerts;
    private final sa.waslah.agent.billing.Subscription subscription;

    public AgentService(WaslahProperties props, AgentBrain brain, ConversationStore conversations, ListingStore listings,
                        MarketerStore marketers, OutboundMessageService outbound, WaAccounts accounts,
                        WhatsAppClient whatsapp, JdbcClient jdbc, ObjectMapper json,
                        sa.waslah.agent.billing.PlanLimits plans, sa.waslah.agent.office.OfficeAlerts alerts,
                        sa.waslah.agent.booking.BookingService bookingService, sa.waslah.agent.booking.BookingStore bookingStore,
                        sa.waslah.agent.billing.Subscription subscription) {
        this.subscription = subscription;
        this.bookingService = bookingService;
        this.bookingStore = bookingStore;
        this.plans = plans;
        this.alerts = alerts;
        this.props = props;
        this.brain = brain;
        this.conversations = conversations;
        this.listings = listings;
        this.marketers = marketers;
        this.outbound = outbound;
        this.accounts = accounts;
        this.whatsapp = whatsapp;
        this.jdbc = jdbc;
        this.json = json;
    }

    @Async("agentExecutor")
    @EventListener
    public void onInbound(InboundMessageEvent e) {
        if (!props.agent().enabled()) {
            return;
        }
        try {
            if (props.agent().debounceMs() > 0) {
                Thread.sleep(props.agent().debounceMs());
            }
            ReentrantLock lock = locks.computeIfAbsent(e.conversationId(), k -> new ReentrantLock());
            lock.lock();
            try {
                TenantContext.run(e.officeId(), () -> handle(e));
            } finally {
                lock.unlock();
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException ex) {
            log.error("Agent failed for conversation {}", e.conversationId(), ex);
            try {
                Throwable root = ex;
                while (root.getCause() != null) root = root.getCause();
                String msg = root.getClass().getSimpleName() + ": " + root.getMessage();
                TenantContext.run(e.officeId(), () -> recordRun(e, "none", Llm.Usage.NONE, 0, json.createArrayNode(),
                        "error", msg.length() > 500 ? msg.substring(0, 500) : msg));
            } catch (RuntimeException ignored) {
                // nothing more to do
            }
        }
    }

    record Msg(UUID id, String direction, String sender, String type, String body) {
    }

    void handle(InboundMessageEvent e) {
        Optional<ConversationStore.ConversationRef> conv = conversations.find(e.conversationId());
        if (conv.isEmpty() || !conv.get().agentActive(Instant.now())) {
            return;
        }
        if (!subscription.state().serving(Instant.now())) {
            // Trial over or subscription lapsed: the customer's message stays in the inbox for the office.
            alerts.raise(e.officeId(), "AGENT_STOPPED", null,
                    "الوكيل متوقف لأن الاشتراك غير فعّال، ورسائل العملاء تنتظر ردكم في المحادثات. فعّل الاشتراك من صفحة الاشتراك.",
                    "stopped-" + LocalDate.now(RIYADH));
            return;
        }
        List<Msg> history = recent(e.conversationId(), props.agent().historyLimit());
        Msg latestInbound = null;
        for (Msg m : history) {
            if ("IN".equals(m.direction())) latestInbound = m;
        }
        // A newer customer message has its own event; let that one answer.
        if (latestInbound == null || !latestInbound.id().equals(e.messageId())) {
            return;
        }
        // Already answered (e.g. an event replayed after a restart).
        if (!"IN".equals(history.get(history.size() - 1).direction())) {
            return;
        }

        if (limited(e, history)) {
            return;
        }

        if ("audio".equals(latestInbound.type())) {
            send(e.conversationId(), AgentPrompt.VOICE_NOTE_REPLY);
            recordRun(e, "none", Llm.Usage.NONE, 0, json.createArrayNode(), "voice_note", null);
            return;
        }

        List<Llm.Turn> turns = toTurns(history);
        Set<String> prior = new HashSet<>();
        for (Msg m : history) {
            if ("AGENT".equals(m.sender())) prior.addAll(ReplyGuard.licensesIn(m.body()));
        }
        String officeName = jdbc.sql("select name from offices").query(String.class).optional().orElse("المكتب");
        String customer = jdbc.sql("""
                        select coalesce(k.profile_name, '') || ' (' || k.wa_id || ')'
                        from conversations c join contacts k on k.id = c.contact_id where c.id = ?""")
                .param(e.conversationId()).query(String.class).single();

        DbAgentTools tools = new DbAgentTools(e.officeId(), e.conversationId(), customer, json, jdbc, listings,
                marketers, conversations, (m, label, summary) -> alertMarketer(conv.get(), m, label, summary),
                props.agent().humanPauseHours(), listing -> sendCard(e.conversationId(), listing), bookingService, bookingStore);

        AgentBrain.Outcome out = brain.respond(AgentPrompt.system(officeName, LocalDate.now(RIYADH)), turns, tools, prior);
        send(e.conversationId(), out.reply());

        ArrayNode calls = json.createArrayNode();
        for (AgentBrain.ToolCallLog c : out.toolCalls()) {
            com.fasterxml.jackson.databind.node.ObjectNode o = calls.addObject();
            o.put("name", c.name());
            o.set("args", c.args());
            o.set("result", c.result());
        }
        String error = out.failure();
        if (!tools.errors().isEmpty()) {
            error = (error == null ? "" : error + " | ") + "tool errors: " + String.join("; ", tools.errors());
        }
        recordRun(e, brain.model(), out.usage(), out.latencyMs(), calls, out.fallback() ? "fallback" : "replied", error);
        usageAlerts(e.officeId());
    }

    /**
     * Abuse protection: at most N agent replies per customer per day, and silence for a customer
     * repeating the same message. The first time a limit is hit, the office gets the chat.
     */
    private boolean limited(InboundMessageEvent e, List<Msg> history) {
        int cap = props.agent().dailyReplyCap();
        if (cap > 0 && plans.repliesTodayInConversation(e.conversationId()) >= cap) {
            String key = "cap-" + e.conversationId() + "-" + LocalDate.now(RIYADH);
            if (alerts.raise(e.officeId(), "REPLY_CAP", e.conversationId(),
                    "عميل تجاوز " + cap + " رداً آلياً اليوم؛ توقف الوكيل في محادثته حتى الغد.", key)) {
                handoffQuietly(e, "تجاوز العميل الحد اليومي للردود الآلية (" + cap + "). راجع المحادثة.");
                recordRun(e, "none", Llm.Usage.NONE, 0, json.createArrayNode(), "capped", null);
            }
            return true;
        }
        List<String> lastInbound = new ArrayList<>();
        for (int i = history.size() - 1; i >= 0 && lastInbound.size() < 5; i--) {
            if ("IN".equals(history.get(i).direction())) lastInbound.add(String.valueOf(history.get(i).body()).trim());
        }
        if (lastInbound.size() == 5 && lastInbound.stream().distinct().count() == 1) {
            recordRun(e, "none", Llm.Usage.NONE, 0, json.createArrayNode(), "spam", null);
            return true;
        }
        return false;
    }

    private void handoffQuietly(InboundMessageEvent e, String summary) {
        jdbc.sql("insert into handoffs (office_id, conversation_id, reason, summary) values (?, ?, 'OTHER', ?)")
                .params(e.officeId(), e.conversationId(), summary).update();
        conversations.pauseAgent(e.conversationId(), ConversationStore.humanPauseUntil(Instant.now(), 24));
    }

    private void usageAlerts(UUID officeId) {
        try {
            sa.waslah.agent.billing.PlanLimits.Usage u = plans.usageThisMonth();
            String month = u.periodStart().toString().substring(0, 7);
            if (u.percent() >= 100) {
                alerts.raise(officeId, "USAGE", null, "استهلكت كل الردود المشمولة في باقتك هذا الشهر (" + u.includedReplies()
                        + "). الردود الإضافية تُحتسب بـ" + u.plan().overagePerReplySar.movePointRight(2).stripTrailingZeros().toPlainString()
                        + " هللة للرد.", "usage-100-" + month);
            } else if (u.percent() >= 80) {
                alerts.raise(officeId, "USAGE", null, "استهلكت 80% من الردود المشمولة في باقتك هذا الشهر.", "usage-80-" + month);
            }
        } catch (RuntimeException ex) {
            log.warn("Usage alert failed: {}", ex.getMessage());
        }
    }

    /** Sends a listing's map pin and up to 3 photos; returns what was sent for the model. */
    private com.fasterxml.jackson.databind.JsonNode sendCard(UUID conversationId, ListingStore.Listing l) {
        com.fasterxml.jackson.databind.node.ObjectNode out = json.createObjectNode();
        try {
            boolean pin = false;
            if (l.hasLocation()) {
                outbound.sendMedia(conversationId, "location", l.title() + " · " + l.district(),
                        (cred, to) -> outbound.client().sendLocation(cred, to, l.latitude(), l.longitude(), l.title(), "حي " + l.district()));
                pin = true;
            }
            int photos = 0;
            for (ListingStore.Photo p : listings.photos(l.id(), 3)) {
                outbound.sendMedia(conversationId, "image", l.title(), (cred, to) ->
                        outbound.client().sendImageId(cred, to, outbound.client().uploadMedia(cred, p.bytes(), p.contentType()), null));
                photos++;
            }
            for (String url : l.photoUrls()) {
                if (photos >= 3) break;
                outbound.sendMedia(conversationId, "image", l.title(), (cred, to) -> outbound.client().sendImageLink(cred, to, url, null));
                photos++;
            }
            out.put("sent_location", pin);
            out.put("photos_sent", photos);
            if (l.videoUrl() != null) out.put("video_url", l.videoUrl());
            if (!pin && photos == 0 && l.videoUrl() == null) {
                out.put("note", "لا توجد صور أو موقع لهذا العقار بعد؛ اعرض على العميل أن يرسلها المسوّق.");
            }
        } catch (ApiException ex) {
            out.put("error", "تعذّر إرسال تفاصيل العقار");
        }
        return out;
    }

    private List<Msg> recent(UUID conversationId, int limit) {
        List<Msg> list = jdbc.sql("""
                        select id, direction, sender, type, body from messages
                        where conversation_id = ? order by created_at desc limit ?""")
                .params(conversationId, limit)
                .query((rs, n) -> new Msg(rs.getObject("id", UUID.class), rs.getString("direction"),
                        rs.getString("sender"), rs.getString("type"), rs.getString("body")))
                .list();
        List<Msg> asc = new ArrayList<>(list);
        java.util.Collections.reverse(asc);
        return asc;
    }

    /** Customer messages become user turns; agent and marketer messages become model turns. Same-role runs are merged. */
    static List<Llm.Turn> toTurns(List<Msg> history) {
        List<Llm.Turn> turns = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        Boolean user = null;
        for (Msg m : history) {
            if ("SYSTEM".equals(m.sender())) continue;
            boolean isUser = "CUSTOMER".equals(m.sender());
            String text = switch (m.type() == null ? "" : m.type()) {
                case "audio" -> "[رسالة صوتية]";
                case "image", "video", "document", "sticker" -> "[" + m.type() + "]" + (m.body() == null ? "" : " " + m.body());
                default -> m.body() == null ? "" : m.body();
            };
            if ("HUMAN".equals(m.sender())) text = "[رد من المسوّق] " + text;
            if (user != null && user != isUser) {
                turns.add(user ? new Llm.UserText(buf.toString()) : new Llm.ModelText(buf.toString()));
                buf.setLength(0);
            }
            if (!buf.isEmpty()) buf.append('\n');
            buf.append(text);
            user = isUser;
        }
        if (user != null && !buf.isEmpty()) {
            turns.add(user ? new Llm.UserText(buf.toString()) : new Llm.ModelText(buf.toString()));
        }
        while (!turns.isEmpty() && !(turns.get(0) instanceof Llm.UserText)) {
            turns.remove(0);
        }
        return turns;
    }

    private void send(UUID conversationId, String text) {
        try {
            outbound.sendText(conversationId, text, Sender.AGENT);
        } catch (ApiException ex) {
            log.warn("Agent reply not sent ({}): {}", ex.code(), ex.getMessage());
        }
    }

    private void alertMarketer(ConversationStore.ConversationRef conv, MarketerStore.Marketer m, String customer, String summary) {
        String template = props.agent().handoffTemplate();
        if (template == null || template.isBlank() || conv.waAccountId() == null) {
            return;
        }
        try {
            WaAccounts.Account acct = accounts.byId(conv.waAccountId()).orElseThrow();
            whatsapp.sendTemplate(accounts.credentials(acct), m.waPhone(), template,
                    props.agent().handoffTemplateLanguage(), List.of(customer, summary.replace('\n', ' ')));
        } catch (RuntimeException ex) {
            log.warn("Could not alert marketer {}: {}", m.id(), ex.getMessage());
        }
    }

    private void recordRun(InboundMessageEvent e, String model, Llm.Usage usage, long latencyMs, ArrayNode calls,
                           String outcome, String error) {
        jdbc.sql("""
                insert into agent_runs (office_id, conversation_id, trigger_message, model, input_tokens, output_tokens,
                                        latency_ms, tool_calls, outcome, error)
                values (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)""")
                .params(e.officeId(), e.conversationId(), e.messageId(), model, usage.inputTokens(), usage.outputTokens(),
                        (int) latencyMs, calls.toString(), outcome, error)
                .update();
    }
}
