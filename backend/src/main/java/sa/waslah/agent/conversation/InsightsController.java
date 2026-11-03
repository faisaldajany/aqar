package sa.waslah.agent.conversation;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The office's home dashboard: what came in over the last 7, 30 or 90 days, day by day, against the period before,
 * and what customers asked for (districts, property types, buy or rent) next to what the office has listed, so it can
 * see demand it is not serving. The office's own test chats (the "جرّب الوكيل" page) are left out. Runs under the
 * office's tenant.
 */
@RestController
public class InsightsController {

    static final ZoneId RIYADH = ZoneId.of("Asia/Riyadh");
    static final String DAY = "(%s at time zone 'Asia/Riyadh')::date";
    /** Joined to conversations c: customers only, not the office's test chat. */
    static final String CUSTOMERS = " join contacts k on k.id = c.contact_id and k.wa_id <> '" + ConversationStore.SANDBOX + "' ";

    private final JdbcClient jdbc;

    public InsightsController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/api/insights")
    Map<String, Object> insights(@RequestParam(defaultValue = "30") int days) {
        int n = days <= 7 ? 7 : days <= 30 ? 30 : 90;
        LocalDate today = LocalDate.now(RIYADH);
        LocalDate first = today.minusDays(n - 1L);
        Instant from = first.atStartOfDay(RIYADH).toInstant();
        Instant prevFrom = first.minusDays(n).atStartOfDay(RIYADH).toInstant();

        Map<LocalDate, Map<String, Integer>> byDay = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            Map<String, Integer> zero = new LinkedHashMap<>();
            for (String k : List.of("inquiries", "serious", "bookings", "handoffs")) zero.put(k, 0);
            byDay.put(first.plusDays(i), zero);
        }
        perDay("inquiries", "select " + DAY.formatted("c.created_at") + ", count(*) from conversations c" + CUSTOMERS
                + "where c.created_at >= ? group by 1", from, byDay);
        perDay("serious", "select " + DAY.formatted("c.created_at") + ", count(*) from leads l join conversations c on c.id = l.conversation_id"
                + CUSTOMERS + "where c.created_at >= ? and l.budget_max is not null and l.payment is not null group by 1", from, byDay);
        perDay("bookings", "select " + DAY.formatted("b.created_at") + ", count(*) from bookings b join conversations c on c.id = b.conversation_id"
                + CUSTOMERS + "where b.created_at >= ? group by 1", from, byDay);
        perDay("handoffs", "select " + DAY.formatted("h.created_at") + ", count(*) from handoffs h join conversations c on c.id = h.conversation_id"
                + CUSTOMERS + "where h.created_at >= ? group by 1", from, byDay);

        List<Map<String, Object>> series = new ArrayList<>();
        Map<String, Integer> totals = new LinkedHashMap<>();
        byDay.forEach((d, v) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", d.toString());
            row.putAll(v);
            series.add(row);
            v.forEach((k, c) -> totals.merge(k, c, Integer::sum));
        });

        Map<String, Integer> previous = new LinkedHashMap<>();
        previous.put("inquiries", count("select count(*) from conversations c" + CUSTOMERS
                + "where c.created_at >= ? and c.created_at < ?", prevFrom, from));
        previous.put("serious", count("select count(*) from leads l join conversations c on c.id = l.conversation_id" + CUSTOMERS
                + "where c.created_at >= ? and c.created_at < ? and l.budget_max is not null and l.payment is not null", prevFrom, from));
        previous.put("bookings", count("select count(*) from bookings b join conversations c on c.id = b.conversation_id" + CUSTOMERS
                + "where b.created_at >= ? and b.created_at < ?", prevFrom, from));
        previous.put("handoffs", count("select count(*) from handoffs h join conversations c on c.id = h.conversation_id" + CUSTOMERS
                + "where h.created_at >= ? and h.created_at < ?", prevFrom, from));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("days", n);
        out.put("from", first.toString());
        out.put("series", series);
        out.put("totals", totals);
        out.put("previous", previous);
        List<Integer> p50 = jdbc.sql("select percentile_cont(0.5) within group (order by r.latency_ms)::int from agent_runs r "
                        + "join conversations c on c.id = r.conversation_id" + CUSTOMERS + "where r.outcome = 'replied' and r.created_at >= ?")
                .param(Timestamp.from(from)).query(Integer.class).list();
        out.put("medianReplyMs", p50.isEmpty() ? null : p50.get(0));
        out.put("hours", hours(from));
        out.putAll(demand(from));
        return out;
    }

    /** Customer messages by hour of day (Riyadh), and the share outside 8:00–22:00. */
    private Map<String, Object> hours(Instant from) {
        int[] h = new int[24];
        jdbc.sql("select extract(hour from m.created_at at time zone 'Asia/Riyadh')::int, count(*) from messages m "
                        + "join conversations c on c.id = m.conversation_id" + CUSTOMERS + "where m.direction = 'IN' and m.created_at >= ? group by 1")
                .param(Timestamp.from(from))
                .query((rs, i) -> h[rs.getInt(1)] = rs.getInt(2)).list();
        int all = 0, after = 0;
        for (int i = 0; i < 24; i++) {
            all += h[i];
            if (i >= 22 || i < 8) after += h[i];
        }
        List<Integer> list = new ArrayList<>();
        for (int v : h) list.add(v);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("byHour", list);
        m.put("afterHours", after);
        m.put("messages", all);
        return m;
    }

    record LeadRow(String purpose, String type, String districts, BigDecimal budget, String payment, String timeline) {
    }

    /** What customers asked for in the period, next to the office's active listings. */
    private Map<String, Object> demand(Instant from) {
        List<LeadRow> leads = jdbc.sql("select l.purpose, l.property_type, l.districts, l.budget_max, l.payment, l.timeline "
                        + "from leads l join conversations c on c.id = l.conversation_id" + CUSTOMERS + "where c.created_at >= ?")
                .param(Timestamp.from(from))
                .query((rs, i) -> new LeadRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBigDecimal(4),
                        rs.getString(5), rs.getString(6)))
                .list();

        Map<String, Integer> temperature = new LinkedHashMap<>(Map.of("HOT", 0, "WARM", 0, "COLD", 0));
        Map<String, Integer> purpose = new LinkedHashMap<>(Map.of("SALE", 0, "RENT", 0));
        Tally districts = new Tally(), types = new Tally();
        for (LeadRow l : leads) {
            temperature.merge(WorkspaceController.temperature(l.purpose(), l.budget(), l.payment(), l.timeline()), 1, Integer::sum);
            if (l.purpose() != null) purpose.merge(l.purpose(), 1, Integer::sum);
            if (l.districts() != null) {
                for (String d : l.districts().split("[,،/]|\\s+و\\s+|\\s+و(?=ال)")) districts.add(d);
            }
            if (l.type() != null) types.add(l.type());
        }

        Map<String, Integer> listedDistricts = listed("district"), listedTypes = listed("property_type");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("leads", leads.size());
        m.put("temperature", temperature);
        m.put("purpose", purpose);
        m.put("topDistricts", districts.top(8, listedDistricts));
        m.put("topTypes", types.top(6, listedTypes));
        return m;
    }

    /** Active listings per normalized district or type, normalized the same way as the agent's search (ar_norm). */
    private Map<String, Integer> listed(String column) {
        Map<String, Integer> m = new HashMap<>();
        jdbc.sql("select " + column + ", count(*) from listings where status = 'ACTIVE' group by 1")
                .query((rs, i) -> m.merge(norm(rs.getString(1)), rs.getInt(2), Integer::sum)).list();
        return m;
    }

    /** Counts requests by normalized name, remembering the spelling customers used most. */
    static final class Tally {
        final Map<String, Integer> counts = new HashMap<>();
        final Map<String, Map<String, Integer>> spellings = new HashMap<>();

        void add(String raw) {
            String shown = raw == null ? "" : raw.trim().replaceFirst("^حي\\s+", "");
            String key = norm(shown);
            if (key.length() < 2) return;
            counts.merge(key, 1, Integer::sum);
            spellings.computeIfAbsent(key, k -> new HashMap<>()).merge(shown, 1, Integer::sum);
        }

        List<Map<String, Object>> top(int limit, Map<String, Integer> listed) {
            return counts.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                    .limit(limit)
                    .map(e -> {
                        Map<String, Object> r = new LinkedHashMap<>();
                        r.put("name", spellings.get(e.getKey()).entrySet().stream()
                                .max(Comparator.comparingInt(Map.Entry::getValue)).map(Map.Entry::getKey).orElse(e.getKey()));
                        r.put("requests", e.getValue());
                        r.put("listings", listed.getOrDefault(e.getKey(), 0));
                        return r;
                    })
                    .toList();
        }
    }

    /** Same rules as the database's ar_norm: "حي النرجس" = "النرجس" = "نرجس"; أ/إ/آ→ا, ة→ه, ى→ي. */
    static String norm(String s) {
        if (s == null) return "";
        String t = s.trim().toLowerCase(java.util.Locale.ROOT)
                .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ة', 'ه').replace('ى', 'ي');
        t = t.replaceFirst("^حي\\s+", "");
        if (t.startsWith("ال")) t = t.substring(2);
        return t.trim();
    }

    private void perDay(String key, String sql, Instant from, Map<LocalDate, Map<String, Integer>> byDay) {
        jdbc.sql(sql).param(Timestamp.from(from)).query((rs, i) -> {
            Map<String, Integer> row = byDay.get(rs.getDate(1).toLocalDate());
            if (row != null) row.put(key, rs.getInt(2));
            return null;
        }).list();
    }

    private int count(String sql, Instant a, Instant b) {
        return jdbc.sql(sql).params(Timestamp.from(a), Timestamp.from(b)).query(Integer.class).single();
    }
}
