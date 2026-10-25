package sa.waslah.agent.conversation;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import sa.waslah.agent.common.ApiException;
import sa.waslah.agent.listing.MarketerStore;
import sa.waslah.agent.tenant.TenantContext;

/** What the office sees around a conversation: the lead card, handoffs, and today's numbers. */
@RestController
public class WorkspaceController {

    static final ZoneId RIYADH = ZoneId.of("Asia/Riyadh");

    public record Assign(UUID marketerId, String note) {
    }

    private final JdbcClient jdbc;
    private final ConversationStore conversations;
    private final MarketerStore marketers;

    public WorkspaceController(JdbcClient jdbc, ConversationStore conversations, MarketerStore marketers) {
        this.jdbc = jdbc;
        this.conversations = conversations;
        this.marketers = marketers;
    }

    @GetMapping("/api/conversations/{id}/details")
    Map<String, Object> details(@PathVariable UUID id) {
        ConversationStore.ConversationRef c = conversations.find(id)
                .orElseThrow(() -> ApiException.notFound("no_conversation", "المحادثة غير موجودة."));
        Map<String, Object> lead = jdbc.sql("""
                        select customer_name, purpose, property_type, districts, budget_min, budget_max, payment, timeline, notes
                        from leads where conversation_id = ?""")
                .param(id)
                .query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("customerName", rs.getString(1));
                    m.put("purpose", rs.getString(2));
                    m.put("propertyType", rs.getString(3));
                    m.put("districts", rs.getString(4));
                    m.put("budgetMin", rs.getBigDecimal(5));
                    m.put("budgetMax", rs.getBigDecimal(6));
                    m.put("payment", rs.getString(7));
                    m.put("timeline", rs.getString(8));
                    m.put("notes", rs.getString(9));
                    m.put("temperature", temperature(rs.getString(2), rs.getBigDecimal(6), rs.getString(7), rs.getString(8)));
                    return m;
                }).optional().orElse(Map.of());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("waId", c.waId());
        out.put("agentActive", c.agentActive(Instant.now()));
        out.put("lead", lead);
        out.put("bookings", jdbc.sql("""
                        select b.id, b.mode, b.starts_at, b.status, l.title from bookings b join listings l on l.id = b.listing_id
                        where b.conversation_id = ? order by b.starts_at desc limit 10""")
                .param(id)
                .query((rs, n) -> Map.of("id", rs.getObject(1, UUID.class), "mode", rs.getString(2),
                        "startsAt", rs.getTimestamp(3).toInstant().toString(), "status", rs.getString(4), "listing", rs.getString(5)))
                .list());
        out.put("handoffs", handoffs("where h.conversation_id = ?", id));
        return out;
    }

    /** Hands the conversation to a marketer from the portal; the agent goes quiet in it. */
    @PostMapping("/api/conversations/{id}/assign")
    Map<String, Object> assign(@PathVariable UUID id, @RequestBody Assign a) {
        conversations.find(id).orElseThrow(() -> ApiException.notFound("no_conversation", "المحادثة غير موجودة."));
        MarketerStore.Marketer m = marketers.find(a.marketerId())
                .orElseThrow(() -> ApiException.notFound("no_marketer", "المسوّق غير موجود."));
        jdbc.sql("""
                insert into handoffs (office_id, conversation_id, marketer_id, reason, summary, status)
                values (?, ?, ?, 'ASSIGNED', ?, 'CLAIMED')""")
                .params(TenantContext.require(), id, m.id(), a.note() == null || a.note().isBlank() ? "حوّلها المكتب يدوياً" : a.note())
                .update();
        jdbc.sql("update conversations set assigned_marketer_id = ? where id = ?").params(m.id(), id).update();
        conversations.pauseAgent(id, ConversationStore.TAKEN_OVER);
        return Map.of("assigned", m.name());
    }

    @GetMapping("/api/handoffs")
    List<Map<String, Object>> handoffList(@RequestParam(defaultValue = "OPEN") String status) {
        if (!List.of("OPEN", "CLAIMED", "CLOSED").contains(status)) {
            throw ApiException.badRequest("bad_status", "حالة غير صحيحة.");
        }
        return handoffs("where h.status = ?", status);
    }

    @PostMapping("/api/handoffs/{id}/{action}")
    Map<String, Object> handoffAction(@PathVariable UUID id, @PathVariable String action) {
        String status = switch (action) {
            case "claim" -> "CLAIMED";
            case "close" -> "CLOSED";
            default -> throw ApiException.badRequest("bad_action", "إجراء غير معروف.");
        };
        int n = jdbc.sql("update handoffs set status = ? where id = ?").params(status, id).update();
        if (n == 0) throw ApiException.notFound("no_handoff", "التحويل غير موجود.");
        return Map.of("status", status);
    }

    /** Numbers for the dashboard: today (Riyadh) and the last 7 days. */
    @GetMapping("/api/stats")
    Map<String, Object> stats() {
        Instant today = LocalDate.now(RIYADH).atStartOfDay(RIYADH).toInstant();
        Instant week = today.minusSeconds(6 * 86400L);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("inquiriesToday", count("select count(*) from conversations where created_at >= ?", today));
        out.put("inquiriesWeek", count("select count(*) from conversations where created_at >= ?", week));
        out.put("qualifiedWeek", count("""
                select count(*) from leads l join conversations c on c.id = l.conversation_id
                where c.created_at >= ? and l.budget_max is not null and l.payment is not null""", week));
        out.put("bookingsToday", count("""
                select count(*) from bookings where status in ('CONFIRMED','PENDING_APPROVAL','DONE')
                and starts_at >= ? and starts_at < cast(? as timestamptz) + interval '1 day'""", today, today));
        out.put("bookedWeek", count("select count(*) from bookings where created_at >= ?", week));
        out.put("openHandoffs", jdbc.sql("select count(*) from handoffs where status = 'OPEN'").query(Integer.class).single());
        out.put("afterHoursWeek", count("""
                select count(*) from messages where direction = 'IN' and created_at >= ?
                and (extract(hour from created_at at time zone 'Asia/Riyadh') >= 22 or extract(hour from created_at at time zone 'Asia/Riyadh') < 8)""", week));
        List<Integer> p50 = jdbc.sql("""
                select percentile_cont(0.5) within group (order by latency_ms)::int from agent_runs
                where outcome = 'replied' and created_at >= ?""").param(Timestamp.from(week)).query(Integer.class).list();
        out.put("medianReplyMs", p50.isEmpty() ? null : p50.get(0));
        return out;
    }

    private int count(String sql, Instant... params) {
        Object[] p = new Object[params.length];
        for (int i = 0; i < params.length; i++) p[i] = Timestamp.from(params[i]);
        return jdbc.sql(sql).params(p).query(Integer.class).single();
    }

    private List<Map<String, Object>> handoffs(String where, Object param) {
        return jdbc.sql("""
                        select h.id, h.conversation_id, h.reason, h.summary, h.status, h.created_at, m.name, k.profile_name, k.wa_id
                        from handoffs h
                        join conversations c on c.id = h.conversation_id
                        join contacts k on k.id = c.contact_id
                        left join marketers m on m.id = h.marketer_id
                        """ + where + " order by h.created_at desc limit 100")
                .param(param)
                .query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getObject(1, UUID.class));
                    m.put("conversationId", rs.getObject(2, UUID.class));
                    m.put("reason", rs.getString(3));
                    m.put("summary", rs.getString(4));
                    m.put("status", rs.getString(5));
                    m.put("createdAt", rs.getTimestamp(6).toInstant().toString());
                    m.put("marketer", rs.getString(7));
                    m.put("customer", rs.getString(8) == null || rs.getString(8).isBlank() ? rs.getString(9) : rs.getString(8));
                    return m;
                }).list();
    }

    /** HOT: knows what, how much and how to pay, and needs it soon. WARM: some of that. COLD: little known. */
    static String temperature(String purpose, BigDecimal budget, String payment, String timeline) {
        int score = (purpose != null ? 1 : 0) + (budget != null ? 1 : 0) + (payment != null ? 1 : 0)
                + (timeline != null && timeline.matches(".*(أسبوع|اسبوع|شهر|فوري|الحين|بكرة|قريب|week|month|asap).*") ? 1 : 0);
        return score >= 4 ? "HOT" : score >= 2 ? "WARM" : "COLD";
    }
}
