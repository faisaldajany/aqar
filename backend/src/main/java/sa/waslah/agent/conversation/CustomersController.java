package sa.waslah.agent.conversation;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import sa.waslah.agent.common.ApiException;
import sa.waslah.agent.common.Digits;
import sa.waslah.agent.tenant.TenantContext;

/**
 * قائمة العملاء: every customer who wrote to the office (test chats and erased customers left out), with what the agent
 * learned about their need, how serious they are, their marketer, and where they stand: new, contacted, viewed, deal
 * closed or not. The office sets the stage; Hidaa also moves it forward by itself (a person replied or a viewing was
 * booked: contacted; a viewing was done: viewed), never past what the office set.
 */
@RestController
public class CustomersController {

    static final ZoneId RIYADH = ZoneId.of("Asia/Riyadh");
    static final List<String> STAGES = List.of("NEW", "CONTACTED", "VIEWING", "WON", "LOST");
    static final int MAX = 3000;

    public record Customer(UUID conversationId, String name, String phone, String purpose, String propertyType,
                           String districts, BigDecimal budgetMin, BigDecimal budgetMax, String payment, String timeline,
                           String notes, String temperature, String stage, boolean stageAuto, Instant stageAt,
                           BigDecimal dealValue, String lostReason, UUID marketerId, String marketer, Instant createdAt,
                           Instant lastMessageAt, int bookings) {
    }

    public record StageChange(String stage, BigDecimal dealValue, String lostReason) {
    }

    /** The list's filters, all optional. */
    record Filter(String q, String temperature, String purpose, String type, String district, String marketer,
                  BigDecimal minBudget, BigDecimal maxBudget) {
    }

    private final JdbcClient jdbc;

    public CustomersController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/api/customers")
    Map<String, Object> list(@RequestParam(required = false) String q, @RequestParam(required = false) String temperature,
                             @RequestParam(required = false) String purpose, @RequestParam(required = false) String type,
                             @RequestParam(required = false) String district, @RequestParam(required = false) String marketer,
                             @RequestParam(required = false) BigDecimal minBudget, @RequestParam(required = false) BigDecimal maxBudget,
                             @RequestParam(required = false) String stage) {
        List<Customer> all = all();
        Filter f = new Filter(q, temperature, purpose, type, district, marketer, minBudget, maxBudget);
        List<Customer> matching = all.stream().filter(c -> matches(c, f)).toList();
        // Counts per stage for the tabs, within the other filters.
        Map<String, Integer> stages = new LinkedHashMap<>();
        stages.put("ALL", matching.size());
        for (String s : STAGES) stages.put(s, (int) matching.stream().filter(c -> s.equals(c.stage())).count());
        List<Customer> shown = stage == null || stage.isBlank() || "ALL".equals(stage) ? matching
                : matching.stream().filter(c -> stage.equals(c.stage())).toList();

        Set<String> types = new LinkedHashSet<>();
        all.stream().map(Customer::propertyType).filter(t -> t != null && !t.isBlank()).map(String::trim).sorted().forEach(types::add);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("customers", shown.size() > 500 ? shown.subList(0, 500) : shown);
        out.put("total", shown.size());
        out.put("stages", stages);
        out.put("types", types);
        out.put("marketers", jdbc.sql("select id, name from marketers order by name")
                .query((rs, n) -> Map.of("id", rs.getObject(1, UUID.class), "name", rs.getString(2))).list());
        out.put("truncated", all.size() >= MAX);
        return out;
    }

    /** The list as a spreadsheet (CSV, opens in Excel with Arabic intact), with the same filters. */
    @GetMapping("/api/customers/export")
    ResponseEntity<byte[]> export(@RequestParam(required = false) String q, @RequestParam(required = false) String temperature,
                                  @RequestParam(required = false) String purpose, @RequestParam(required = false) String type,
                                  @RequestParam(required = false) String district, @RequestParam(required = false) String marketer,
                                  @RequestParam(required = false) BigDecimal minBudget, @RequestParam(required = false) BigDecimal maxBudget,
                                  @RequestParam(required = false) String stage) {
        Filter f = new Filter(q, temperature, purpose, type, district, marketer, minBudget, maxBudget);
        List<Customer> rows = all().stream().filter(c -> matches(c, f))
                .filter(c -> stage == null || stage.isBlank() || "ALL".equals(stage) || stage.equals(c.stage())).toList();
        StringBuilder b = new StringBuilder("﻿");
        row(b, List.of("الاسم", "الجوال", "الطلب", "النوع", "الأحياء", "الميزانية من", "الميزانية إلى", "الدفع", "متى",
                "الجدية", "المرحلة", "قيمة الصفقة", "سبب عدم الإتمام", "المسوّق", "المواعيد", "أول تواصل", "آخر رسالة", "ملاحظات"));
        for (Customer c : rows) {
            // The phone starts with an invisible direction mark, so Excel keeps it as text (and its leading 0).
            row(b, Stream.of(c.name(), c.phone() == null ? null : "\u200E" + c.phone(), purposeAr(c.purpose()), c.propertyType(), c.districts(), num(c.budgetMin()),
                    num(c.budgetMax()), paymentAr(c.payment()), c.timeline(), temperatureAr(c.temperature()), stageAr(c.stage()),
                    num(c.dealValue()), c.lostReason(), c.marketer(), String.valueOf(c.bookings()), day(c.createdAt()),
                    day(c.lastMessageAt()), c.notes()).map(v -> v == null ? "" : v).toList());
        }
        String name = "hidaa-customers-" + LocalDate.now(RIYADH) + ".csv";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(b.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** The office moves a customer: contacted, viewed, deal closed (with its value) or not (with why). */
    @PostMapping("/api/customers/{id}/stage")
    Customer setStage(@PathVariable UUID id, @RequestBody StageChange s) {
        if (s == null || s.stage() == null || !STAGES.contains(s.stage())) {
            throw ApiException.badRequest("bad_stage", "اختر مرحلة العميل.");
        }
        boolean exists = jdbc.sql("""
                        select exists(select 1 from conversations c join contacts k on k.id = c.contact_id
                                      where c.id = ? and k.wa_id <> ? and k.erased_at is null)""")
                .params(id, ConversationStore.SANDBOX).query(Boolean.class).single();
        if (!exists) throw ApiException.notFound("no_customer", "العميل غير موجود.");
        BigDecimal value = "WON".equals(s.stage()) ? s.dealValue() : null;
        if (value != null && (value.signum() < 0 || value.compareTo(new BigDecimal("1000000000000")) >= 0)) {
            throw ApiException.badRequest("bad_value", "«قيمة الصفقة»: رقم بالريال.");
        }
        String reason = "LOST".equals(s.stage()) && s.lostReason() != null && !s.lostReason().isBlank()
                ? s.lostReason().trim().substring(0, Math.min(300, s.lostReason().trim().length())) : null;
        jdbc.sql("""
                        insert into leads (conversation_id, office_id, stage, stage_at, deal_value, lost_reason)
                        values (?, ?, ?, now(), ?, ?)
                        on conflict (conversation_id) do update set stage = excluded.stage, stage_at = now(),
                            deal_value = excluded.deal_value, lost_reason = excluded.lost_reason""")
                .params(id, TenantContext.require(), s.stage(), value, reason).update();
        return all(id).stream().findFirst().orElseThrow();
    }

    /** Deals for the dashboard: this month and last (Riyadh), with their value, and the count at each stage. */
    @GetMapping("/api/customers/summary")
    Map<String, Object> summary() {
        List<Customer> all = all();
        YearMonth now = YearMonth.now(RIYADH);
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Integer> stages = new LinkedHashMap<>();
        for (String s : STAGES) stages.put(s, (int) all.stream().filter(c -> s.equals(c.stage())).count());
        out.put("stages", stages);
        for (var e : Map.of("thisMonth", now, "lastMonth", now.minusMonths(1)).entrySet()) {
            List<Customer> won = all.stream().filter(c -> "WON".equals(c.stage()) && c.stageAt() != null
                    && YearMonth.from(c.stageAt().atZone(RIYADH)).equals(e.getValue())).toList();
            out.put(e.getKey(), Map.of("deals", won.size(), "value",
                    won.stream().map(Customer::dealValue).filter(v -> v != null).reduce(BigDecimal.ZERO, BigDecimal::add)));
        }
        out.put("everWon", stages.get("WON") > 0);
        return out;
    }

    // ---- Building blocks ----

    private List<Customer> all() {
        return all(null);
    }

    private List<Customer> all(UUID only) {
        return jdbc.sql("""
                        select c.id, coalesce(nullif(trim(l.customer_name), ''), nullif(trim(k.profile_name), '')), k.wa_id,
                               l.purpose, l.property_type, l.districts, l.budget_min, l.budget_max, l.payment, l.timeline, l.notes,
                               coalesce(l.stage, 'NEW'), l.stage_at, l.deal_value, l.lost_reason, c.assigned_marketer_id, m.name,
                               c.created_at, c.last_message_at,
                               (select count(*) from bookings b where b.conversation_id = c.id),
                               exists(select 1 from bookings b where b.conversation_id = c.id and b.status = 'DONE'),
                               exists(select 1 from messages x where x.conversation_id = c.id and x.sender = 'HUMAN')
                                 or exists(select 1 from handoffs h where h.conversation_id = c.id and h.status in ('CLAIMED', 'CLOSED'))
                                 or exists(select 1 from bookings b where b.conversation_id = c.id
                                           and b.status in ('PENDING_APPROVAL', 'CONFIRMED', 'DONE', 'NO_SHOW'))
                        from conversations c
                        join contacts k on k.id = c.contact_id
                        left join leads l on l.conversation_id = c.id
                        left join marketers m on m.id = c.assigned_marketer_id
                        where k.wa_id <> :sandbox and k.erased_at is null and (cast(:only as uuid) is null or c.id = :only)
                        order by coalesce(c.last_message_at, c.created_at) desc
                        limit :max""")
                .param("sandbox", ConversationStore.SANDBOX).param("only", only).param("max", MAX)
                .query((rs, n) -> {
                    String manual = rs.getString(12);
                    String stage = effective(manual, rs.getBoolean(22), rs.getBoolean(21));
                    return new Customer(rs.getObject(1, UUID.class), rs.getString(2), phone(rs.getString(3)), rs.getString(4),
                            rs.getString(5), rs.getString(6), rs.getBigDecimal(7), rs.getBigDecimal(8), rs.getString(9),
                            rs.getString(10), rs.getString(11),
                            WorkspaceController.temperature(rs.getString(4), rs.getBigDecimal(8), rs.getString(9), rs.getString(10)),
                            stage, !stage.equals(manual), ts(rs.getTimestamp(13)), rs.getBigDecimal(14), rs.getString(15),
                            rs.getObject(16, UUID.class), rs.getString(17), ts(rs.getTimestamp(18)), ts(rs.getTimestamp(19)),
                            rs.getInt(20));
                }).list();
    }

    /** What the office set, or further along if what happened says so; a closed deal (won or lost) stays as set. */
    static String effective(String manual, boolean contacted, boolean viewed) {
        String m = manual == null ? "NEW" : manual;
        if ("WON".equals(m) || "LOST".equals(m)) return m;
        int set = STAGES.indexOf(m);
        int seen = viewed ? 2 : contacted ? 1 : 0;
        return STAGES.get(Math.max(set, seen));
    }

    static boolean matches(Customer c, Filter f) {
        if (has(f.temperature()) && !f.temperature().equals(c.temperature())) return false;
        if (has(f.purpose()) && !f.purpose().equals(c.purpose())) return false;
        if (has(f.type()) && (c.propertyType() == null || !norm(c.propertyType()).equals(norm(f.type())))) return false;
        if (has(f.district()) && (c.districts() == null || !norm(c.districts()).contains(norm(f.district())))) return false;
        if (has(f.marketer())) {
            if ("none".equals(f.marketer())) {
                if (c.marketerId() != null) return false;
            } else if (c.marketerId() == null || !c.marketerId().toString().equals(f.marketer())) {
                return false;
            }
        }
        if (f.minBudget() != null && (c.budgetMax() == null || c.budgetMax().compareTo(f.minBudget()) < 0)) return false;
        if (f.maxBudget() != null && (c.budgetMax() == null || c.budgetMax().compareTo(f.maxBudget()) > 0)) return false;
        if (has(f.q())) {
            String q = norm(Digits.latin(f.q()));
            String digits = q.replaceAll("\\D", "");
            String hay = norm(String.join(" ", Stream.of(c.name(), c.propertyType(), c.districts(), c.notes(), c.timeline(),
                    c.marketer()).map(v -> v == null ? "" : v).toList()));
            boolean phone = digits.length() >= 4 && c.phone() != null && c.phone().replaceAll("\\D", "")
                    .contains(digits.startsWith("0") ? digits.substring(1) : digits);
            if (!hay.contains(q) && !phone) return false;
        }
        return true;
    }

    /** Arabic spelling variants count as the same: أ/إ/آ/ا, ة/ه, ى/ي, and a leading «حي». */
    static String norm(String s) {
        if (s == null) return "";
        return s.trim().toLowerCase(Locale.ROOT).replaceAll("[أإآ]", "ا").replace('ة', 'ه').replace('ى', 'ي')
                .replaceAll("^حي\\s+", "").replaceAll("\\s+", " ");
    }

    private static boolean has(String s) {
        return s != null && !s.isBlank();
    }

    /** 9665XXXXXXXX as 05XXXXXXXX; anything else as +international. */
    static String phone(String waId) {
        if (waId == null) return null;
        if (waId.matches("9665\\d{8}")) return "0" + waId.substring(3);
        return waId.matches("\\d{6,15}") ? "+" + waId : waId;
    }

    private static Instant ts(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private static String num(BigDecimal v) {
        return v == null ? null : v.stripTrailingZeros().toPlainString();
    }

    private static String day(Instant t) {
        return t == null ? null : LocalDate.ofInstant(t, RIYADH).toString();
    }

    static String purposeAr(String p) {
        return p == null ? null : switch (p) { case "SALE" -> "شراء"; case "RENT" -> "إيجار"; default -> p; };
    }

    static String paymentAr(String p) {
        return p == null ? null : switch (p) {
            case "CASH" -> "كاش"; case "FINANCE" -> "تمويل"; case "INSTALLMENTS" -> "أقساط"; case "UNKNOWN" -> null; default -> p;
        };
    }

    static String temperatureAr(String t) {
        return switch (t) { case "HOT" -> "جاد"; case "WARM" -> "مهتم"; default -> "يستكشف"; };
    }

    static String stageAr(String s) {
        return switch (s) {
            case "CONTACTED" -> "تواصلنا"; case "VIEWING" -> "عاين"; case "WON" -> "تمت الصفقة"; case "LOST" -> "لم تتم"; default -> "جديد";
        };
    }

    /** One CSV line; a cell that Excel would read as a formula (=, +, -, @) is kept as text. */
    private static void row(StringBuilder b, List<String> cells) {
        List<String> out = new ArrayList<>(cells.size());
        for (String v : cells) {
            String s = v.replace("\r", " ").replace("\n", " ");
            if (!s.isEmpty() && "=+-@".indexOf(s.charAt(0)) >= 0 && !s.matches("[+-]?\\d+(\\.\\d+)?")) s = "'" + s;
            out.add("\"" + s.replace("\"", "\"\"") + "\"");
        }
        b.append(String.join(",", out)).append("\r\n");
    }
}
