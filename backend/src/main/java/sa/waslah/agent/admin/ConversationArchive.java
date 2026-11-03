package sa.waslah.agent.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import sa.waslah.agent.common.Redactor;
import sa.waslah.agent.conversation.ConversationStore;

/**
 * Customer conversations as the Hidaa team sees them for quality review and export: messages in order, each agent
 * turn (tools called, what they returned, outcome, latency) attached to the reply it produced, and the lead and
 * handoffs — with the customer's identity masked (see {@link Redactor}). The office's own test chat is left out.
 * Must run under the office's tenant.
 */
@Component
public class ConversationArchive {

    static final int MAX_MESSAGES = 300;

    public record Run(UUID triggerMessage, Instant at, String model, int inputTokens, int outputTokens, int latencyMs,
                      String outcome, String error, JsonNode tools) {
    }

    /** One message; {@code run} is the agent turn that produced this reply (or that this customer message got, when it had no reply). */
    public record Msg(int n, UUID id, Instant at, String direction, String sender, String type, String text, Run run) {
    }

    public record Handoff(Instant at, String reason, String summary) {
    }

    public record Conv(UUID id, UUID officeId, String office, String customer, Instant createdAt, Instant lastMessageAt,
                       boolean withMarketer, Map<String, Object> lead, List<Msg> messages, List<Handoff> handoffs,
                       int bookings) {

        public long agentReplies() {
            return messages.stream().filter(m -> "AGENT".equals(m.sender())).count();
        }
    }

    private final JdbcClient jdbc;
    private final Redactor redactor;
    private final ObjectMapper json;

    public ConversationArchive(JdbcClient jdbc, Redactor redactor, ObjectMapper json) {
        this.jdbc = jdbc;
        this.redactor = redactor;
        this.json = json;
    }

    /** Customer conversations with messages in [from, to), most recent first. */
    public List<UUID> ids(Instant from, Instant to, int limit) {
        return jdbc.sql("""
                        select c.id from conversations c join contacts k on k.id = c.contact_id
                        where k.wa_id <> :sandbox and k.erased_at is null
                          and c.last_message_at >= :f and c.created_at < :t
                        order by c.last_message_at desc limit :lim""")
                .param("sandbox", ConversationStore.SANDBOX).param("f", Timestamp.from(from)).param("t", Timestamp.from(to))
                .param("lim", limit)
                .query(UUID.class).list();
    }

    public java.util.Optional<Conv> load(UUID officeId, String officeName, UUID conversationId) {
        record Head(String waId, Instant created, Instant last, boolean withMarketer) {
        }
        java.util.Optional<Head> head = jdbc.sql("""
                        select k.wa_id, c.created_at, c.last_message_at,
                               c.assigned_marketer_id is not null or coalesce(c.agent_paused_until > now(), false)
                        from conversations c join contacts k on k.id = c.contact_id
                        where c.id = ? and k.wa_id <> ? and k.erased_at is null""")
                .params(conversationId, ConversationStore.SANDBOX)
                .query((rs, n) -> new Head(rs.getString(1), rs.getTimestamp(2).toInstant(),
                        rs.getTimestamp(3) == null ? null : rs.getTimestamp(3).toInstant(), rs.getBoolean(4)))
                .optional();
        if (head.isEmpty()) return java.util.Optional.empty();

        record Row(UUID id, Instant at, String direction, String sender, String type, String body) {
        }
        List<Row> rows = new ArrayList<>(jdbc.sql("""
                        select id, created_at, direction, sender, type, body from messages
                        where conversation_id = ? order by created_at desc, id limit ?""")
                .params(conversationId, MAX_MESSAGES)
                .query((rs, n) -> new Row(rs.getObject(1, UUID.class), rs.getTimestamp(2).toInstant(), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6)))
                .list());
        Collections.reverse(rows);

        List<Run> runs = jdbc.sql("""
                        select trigger_message, created_at, model, input_tokens, output_tokens, latency_ms, outcome, error,
                               tool_calls::text
                        from agent_runs where conversation_id = ? order by created_at""")
                .param(conversationId)
                .query((rs, n) -> new Run(rs.getObject(1, UUID.class), rs.getTimestamp(2).toInstant(), rs.getString(3),
                        rs.getInt(4), rs.getInt(5), rs.getInt(6), rs.getString(7), Redactor.text(rs.getString(8)),
                        Redactor.json(parse(rs.getString(9)))))
                .list();

        // Each run goes on the first agent reply after its customer message (and before the next customer message);
        // a run that produced no reply stays on the customer message.
        Map<UUID, Run> byTrigger = new LinkedHashMap<>();
        for (Run r : runs) if (r.triggerMessage() != null) byTrigger.put(r.triggerMessage(), r);
        Map<Integer, Run> attach = new LinkedHashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            Run r = byTrigger.get(rows.get(i).id());
            if (r == null) continue;
            int target = i;
            for (int j = i + 1; j < rows.size() && "OUT".equals(rows.get(j).direction()); j++) {
                if ("AGENT".equals(rows.get(j).sender())) {
                    target = j;
                    break;
                }
            }
            attach.put(target, r);
        }
        List<Msg> messages = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            messages.add(new Msg(i + 1, r.id(), r.at(), r.direction(), r.sender(), r.type(), Redactor.text(r.body()), attach.get(i)));
        }

        Map<String, Object> lead = jdbc.sql("""
                        select purpose, property_type, districts, budget_min, budget_max, payment, timeline, notes
                        from leads where conversation_id = ?""")
                .param(conversationId)
                .query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("purpose", rs.getString(1));
                    m.put("propertyType", rs.getString(2));
                    m.put("districts", rs.getString(3));
                    m.put("budgetMin", (BigDecimal) rs.getBigDecimal(4));
                    m.put("budgetMax", (BigDecimal) rs.getBigDecimal(5));
                    m.put("payment", rs.getString(6));
                    m.put("timeline", rs.getString(7));
                    m.put("notes", Redactor.text(rs.getString(8)));
                    return m;
                }).optional().orElse(null);
        List<Handoff> handoffs = jdbc.sql("select created_at, reason, summary from handoffs where conversation_id = ? order by created_at")
                .param(conversationId)
                .query((rs, n) -> new Handoff(rs.getTimestamp(1).toInstant(), rs.getString(2), Redactor.text(rs.getString(3))))
                .list();
        int bookings = jdbc.sql("select count(*) from bookings where conversation_id = ?").param(conversationId)
                .query(Integer.class).single();
        Head h = head.get();
        return java.util.Optional.of(new Conv(conversationId, officeId, officeName, redactor.customerCode(officeId, h.waId()),
                h.created(), h.last(), h.withMarketer(), lead, messages, handoffs, bookings));
    }

    private JsonNode parse(String s) {
        try {
            return s == null ? json.createArrayNode() : json.readTree(s);
        } catch (Exception e) {
            return json.createArrayNode();
        }
    }
}
