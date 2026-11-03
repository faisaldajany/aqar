package sa.waslah.agent.conversation;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import sa.waslah.agent.common.ApiException;
import sa.waslah.agent.tenant.TenantContext;

/**
 * What the agent did not know, and what the office taught it. Gaps: conversations the agent handed over because it
 * could not answer (OUT_OF_SCOPE / OTHER), or where it fell back to a person, with the customer's question that led
 * there. Facts: the office's answers, given to the agent with every conversation (see AgentPrompt). And how much
 * interest each listing gets: shown by the agent, its photos and location sent, viewings booked.
 */
@RestController
public class AgentKnowledgeController {

    static final int MAX_FACTS = 40;

    public record NewFact(String question, String answer) {
    }

    private final JdbcClient jdbc;

    public AgentKnowledgeController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/api/agent/facts")
    List<Map<String, Object>> facts() {
        return jdbc.sql("select id, question, answer, created_at from agent_facts order by created_at")
                .query((rs, n) -> Map.<String, Object>of("id", rs.getObject(1, UUID.class), "question", rs.getString(2),
                        "answer", rs.getString(3), "createdAt", rs.getTimestamp(4).toInstant().toString()))
                .list();
    }

    @PostMapping("/api/agent/facts")
    List<Map<String, Object>> addFact(@RequestBody NewFact f) {
        String q = f == null || f.question() == null ? "" : f.question().replaceAll("\\s+", " ").trim();
        String a = f == null || f.answer() == null ? "" : f.answer().trim();
        if (q.length() < 2) throw ApiException.badRequest("no_question", "«السؤال»: اكتب ما يسأل عنه العملاء.");
        if (a.isEmpty()) throw ApiException.badRequest("no_answer", "«الجواب»: اكتب جواب المكتب.");
        if (q.length() > 300) throw ApiException.badRequest("long_question", "«السؤال»: 300 حرف على الأكثر.");
        if (a.length() > 600) throw ApiException.badRequest("long_answer", "«الجواب»: 600 حرف على الأكثر.");
        int n = jdbc.sql("select count(*) from agent_facts").query(Integer.class).single();
        if (n >= MAX_FACTS) throw ApiException.conflict("too_many", "حتى " + MAX_FACTS + " معلومة. احذف ما لم يعد صحيحاً أولاً.");
        jdbc.sql("insert into agent_facts (office_id, question, answer) values (?, ?, ?)").params(TenantContext.require(), q, a).update();
        return facts();
    }

    @DeleteMapping("/api/agent/facts/{id}")
    List<Map<String, Object>> deleteFact(@PathVariable UUID id) {
        jdbc.sql("delete from agent_facts where id = ?").param(id).update();
        return facts();
    }

    /** The last {@code days} days: where the agent had no answer, newest first, with the question that led there. */
    @GetMapping("/api/agent/gaps")
    List<Map<String, Object>> gaps(@RequestParam(defaultValue = "30") int days) {
        Timestamp since = Timestamp.from(Instant.now().minusSeconds(Math.min(Math.max(days, 1), 90) * 86400L));
        return jdbc.sql("""
                        with g as (
                            select h.conversation_id, h.created_at as at, 'HANDOFF' as kind, h.summary
                            from handoffs h where h.reason in ('OUT_OF_SCOPE', 'OTHER') and h.created_at >= :since
                            union all
                            select r.conversation_id, r.created_at, 'FALLBACK', r.error
                            from agent_runs r where r.outcome = 'fallback' and r.created_at >= :since
                        )
                        select g.conversation_id, g.at, g.kind, g.summary,
                               (select m.body from messages m where m.conversation_id = g.conversation_id and m.direction = 'IN'
                                  and m.type = 'text' and m.created_at <= g.at order by m.created_at desc limit 1) as question,
                               coalesce(nullif(trim(k.profile_name), ''), k.wa_id)
                        from g join conversations c on c.id = g.conversation_id join contacts k on k.id = c.contact_id
                        where k.wa_id <> :sandbox and k.erased_at is null
                        order by g.at desc limit 100""")
                .param("since", since).param("sandbox", ConversationStore.SANDBOX)
                .query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("conversationId", rs.getObject(1, UUID.class));
                    m.put("at", rs.getTimestamp(2).toInstant().toString());
                    m.put("kind", rs.getString(3));
                    // Handoff summaries are written for the marketer; a fallback's error is internal, so not shown.
                    m.put("summary", "HANDOFF".equals(rs.getString(3)) ? rs.getString(4) : null);
                    m.put("question", rs.getString(5));
                    m.put("customer", CustomersController.phone(rs.getString(6))); // a name stays as it is
                    return m;
                }).list();
    }

    /**
     * Per listing over the last {@code days}: customers the agent showed it to, customers who got its photos and
     * location, viewings booked; and which active listings need a look (no interest, licence ending, no photos or
     * location).
     */
    @GetMapping("/api/listings/interest")
    Map<String, Object> interest(@RequestParam(defaultValue = "30") int days) {
        Timestamp since = Timestamp.from(Instant.now().minusSeconds(Math.min(Math.max(days, 7), 90) * 86400L));
        Map<String, Map<String, Integer>> per = new LinkedHashMap<>();
        java.util.function.Function<String, Map<String, Integer>> row = id -> per.computeIfAbsent(id, k -> {
            Map<String, Integer> m = new LinkedHashMap<>();
            m.put("shown", 0);
            m.put("cards", 0);
            m.put("viewings", 0);
            return m;
        });
        jdbc.sql("""
                        select x.value->>'id', count(distinct r.conversation_id)
                        from agent_runs r, jsonb_array_elements(r.tool_calls) t, jsonb_array_elements(coalesce(t.value->'result'->'listings', '[]')) x
                        where t.value->>'name' = 'search_listings' and r.created_at >= ?
                        group by 1""")
                .param(since).query((rs, n) -> { if (rs.getString(1) != null) row.apply(rs.getString(1)).put("shown", rs.getInt(2)); return null; }).list();
        jdbc.sql("""
                        select t.value->'args'->>'listing_id', count(distinct r.conversation_id)
                        from agent_runs r, jsonb_array_elements(r.tool_calls) t
                        where t.value->>'name' = 'send_listing_card' and r.created_at >= ?
                        group by 1""")
                .param(since).query((rs, n) -> { if (rs.getString(1) != null) row.apply(rs.getString(1)).put("cards", rs.getInt(2)); return null; }).list();
        jdbc.sql("select listing_id::text, count(*) from bookings where created_at >= ? group by 1")
                .param(since).query((rs, n) -> { row.apply(rs.getString(1)).put("viewings", rs.getInt(2)); return null; }).list();

        // Active listings to look at: on the market two weeks without being shown to anyone in that time, a licence
        // ending within 14 days, or no photos / no location (the agent cannot send a card).
        Timestamp twoWeeks = Timestamp.from(Instant.now().minusSeconds(14 * 86400L));
        List<Map<String, Object>> active = jdbc.sql("""
                        select l.id::text, l.title, l.created_at < ?, l.license_expiry,
                               l.latitude is not null and l.longitude is not null,
                               (select count(*) from listing_photos p where p.listing_id = l.id) + coalesce(cardinality(l.photo_urls), 0)
                        from listings l where l.status = 'ACTIVE'""")
                .param(twoWeeks)
                .query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getString(1));
                    m.put("title", rs.getString(2));
                    m.put("old", rs.getBoolean(3));
                    m.put("expiry", rs.getDate(4).toLocalDate());
                    m.put("hasLocation", rs.getBoolean(5));
                    m.put("photos", rs.getInt(6));
                    return m;
                }).list();
        Map<String, Integer> shownTwoWeeks = new LinkedHashMap<>();
        jdbc.sql("""
                        select x.value->>'id', count(distinct r.conversation_id)
                        from agent_runs r, jsonb_array_elements(r.tool_calls) t, jsonb_array_elements(coalesce(t.value->'result'->'listings', '[]')) x
                        where t.value->>'name' = 'search_listings' and r.created_at >= ?
                        group by 1""")
                .param(twoWeeks).query((rs, n) -> { shownTwoWeeks.put(rs.getString(1), rs.getInt(2)); return null; }).list();
        LocalDate soon = LocalDate.now(CustomersController.RIYADH).plusDays(14);
        int noInterest = 0, licence = 0, noCard = 0;
        List<String> noInterestIds = new java.util.ArrayList<>();
        for (Map<String, Object> l : active) {
            if (Boolean.TRUE.equals(l.get("old")) && shownTwoWeeks.getOrDefault((String) l.get("id"), 0) == 0) {
                noInterest++;
                noInterestIds.add((String) l.get("id"));
            }
            if (!((LocalDate) l.get("expiry")).isAfter(soon)) licence++;
            if (!Boolean.TRUE.equals(l.get("hasLocation")) || (int) l.get("photos") == 0) noCard++;
        }
        Map<String, Object> attention = new LinkedHashMap<>();
        attention.put("noInterest", noInterest);
        attention.put("noInterestIds", noInterestIds);
        attention.put("licenceEnding", licence);
        attention.put("noCard", noCard);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("days", days);
        out.put("listings", per);
        out.put("attention", attention);
        return out;
    }
}
