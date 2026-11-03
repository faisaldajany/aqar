package sa.waslah.agent.admin;

import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import sa.waslah.agent.billing.BillingService;
import sa.waslah.agent.billing.PaymentGateways;
import sa.waslah.agent.billing.PlanLimits;
import sa.waslah.agent.common.ApiException;
import sa.waslah.agent.common.ClientIp;
import sa.waslah.agent.conversation.ConversationStore;
import sa.waslah.agent.tenant.TenantContext;

/**
 * Numbers for the Hidaa team (portal /ops): revenue and every invoice, usage and estimated AI cost per office, and the
 * signup funnel. Invoices and usage are tenant data, so each office is read under its own tenant and the results are
 * added up here; with hundreds of offices this is still a few hundred small queries. Amounts are SAR.
 */
@RestController
public class OpsReportsController {

    static final ZoneId RIYADH = ZoneId.of("Asia/Riyadh");

    public record ManualPayment(UUID officeId, String note) {
    }

    /** {@code internal}: a Hidaa team account (confirmed company email, pays in test mode); left out of revenue and the funnel. */
    record Office(UUID id, String name, String status, PlanLimits.Plan plan, String cycle, boolean suspended, Instant createdAt,
                  boolean internal) {

        /** What the office pays per month on its plan now: 0 unless subscribed (annual counts as a twelfth), or internal. */
        BigDecimal monthlyRevenue() {
            if (internal || suspended || !("ACTIVE".equals(status) || "PAST_DUE".equals(status))) return BigDecimal.ZERO;
            return "ANNUAL".equals(cycle) ? plan.annualSar.divide(BigDecimal.valueOf(12), 2, RoundingMode.HALF_UP) : plan.monthlySar;
        }
    }

    private final JdbcClient jdbc;
    private final OpsAuth ops;
    private final BillingService billing;
    private final PaymentGateways gateways;
    private final BigDecimal inputUsdPerMillion;
    private final BigDecimal outputUsdPerMillion;
    private final BigDecimal usdToSar;

    public OpsReportsController(JdbcClient jdbc, OpsAuth ops, BillingService billing, PaymentGateways gateways,
                                @Value("${waslah.ops.llm-input-usd-per-million:0.10}") BigDecimal inputUsdPerMillion,
                                @Value("${waslah.ops.llm-output-usd-per-million:0.40}") BigDecimal outputUsdPerMillion,
                                @Value("${waslah.ops.usd-to-sar:3.75}") BigDecimal usdToSar) {
        this.jdbc = jdbc;
        this.ops = ops;
        this.billing = billing;
        this.gateways = gateways;
        this.inputUsdPerMillion = inputUsdPerMillion;
        this.outputUsdPerMillion = outputUsdPerMillion;
        this.usdToSar = usdToSar;
    }

    // ---- Revenue and invoices ----

    /** Revenue collected per month (12 months, excluding VAT), monthly recurring revenue, unpaid invoices, and every invoice. */
    @GetMapping("/api/ops/revenue")
    Map<String, Object> revenue(HttpServletRequest http) {
        ops.require(http);
        YearMonth now = YearMonth.now(RIYADH);
        Map<YearMonth, Map<String, BigDecimal>> months = new LinkedHashMap<>();
        for (int i = 11; i >= 0; i--) {
            Map<String, BigDecimal> m = new LinkedHashMap<>();
            for (String k : List.of("SUBSCRIPTION", "OVERAGE", "SETUP", "vat")) m.put(k, BigDecimal.ZERO);
            months.put(now.minusMonths(i), m);
        }
        Instant since = now.minusMonths(11).atDay(1).atStartOfDay(RIYADH).toInstant();
        List<Map<String, Object>> invoices = new ArrayList<>();
        BigDecimal mrr = BigDecimal.ZERO, unpaid = BigDecimal.ZERO;
        int paying = 0, unpaidCount = 0;

        for (Office o : offices()) {
            if (o.monthlyRevenue().signum() > 0) paying++;
            mrr = mrr.add(o.monthlyRevenue());
            List<Map<String, Object>> rows = TenantContext.call(o.id(), () -> jdbc.sql("""
                            select id, number, kind, plan, cycle, description, subtotal_halalas, vat_halalas, total_halalas,
                                   status, provider, created_at, paid_at, discount_halalas, promo_code
                            from invoices order by created_at desc limit 200""")
                    .query((rs, n) -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("id", rs.getObject(1, UUID.class));
                        m.put("officeId", o.id());
                        m.put("office", o.name());
                        m.put("number", rs.getString(2));
                        m.put("kind", rs.getString(3));
                        m.put("plan", rs.getString(4));
                        m.put("cycle", rs.getString(5));
                        m.put("description", rs.getString(6));
                        m.put("subtotal", sar(rs.getLong(7)));
                        m.put("vat", sar(rs.getLong(8)));
                        m.put("total", sar(rs.getLong(9)));
                        m.put("status", rs.getString(10));
                        m.put("provider", rs.getString(11));
                        m.put("createdAt", iso(rs.getTimestamp(12)));
                        m.put("paidAt", iso(rs.getTimestamp(13)));
                        m.put("discount", sar(rs.getLong(14)));
                        m.put("promoCode", rs.getString(15));
                        // Paid with Moyasar test cards (an internal office): shown, never counted as revenue.
                        m.put("test", PaymentGateways.TEST.equals(rs.getString(11)) || o.internal());
                        return m;
                    }).list());
            for (Map<String, Object> r : rows) {
                if (Boolean.TRUE.equals(r.get("test"))) continue;
                if ("DUE".equals(r.get("status"))) {
                    unpaid = unpaid.add((BigDecimal) r.get("total"));
                    unpaidCount++;
                }
                if ("PAID".equals(r.get("status")) && r.get("paidAt") != null) {
                    Instant paid = Instant.parse((String) r.get("paidAt"));
                    if (paid.isBefore(since)) continue;
                    Map<String, BigDecimal> m = months.get(YearMonth.from(paid.atZone(RIYADH)));
                    if (m == null) continue;
                    String kind = (String) r.get("kind");
                    if (m.containsKey(kind)) m.merge(kind, (BigDecimal) r.get("subtotal"), BigDecimal::add);
                    m.merge("vat", (BigDecimal) r.get("vat"), BigDecimal::add);
                }
            }
            invoices.addAll(rows);
        }
        invoices.sort(Comparator.comparing((Map<String, Object> r) -> (String) r.get("createdAt")).reversed());

        List<Map<String, Object>> monthList = new ArrayList<>();
        months.forEach((ym, m) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("month", ym.toString());
            row.put("subscriptions", m.get("SUBSCRIPTION"));
            row.put("overage", m.get("OVERAGE"));
            row.put("setup", m.get("SETUP"));
            row.put("total", m.get("SUBSCRIPTION").add(m.get("OVERAGE")).add(m.get("SETUP")));
            row.put("vat", m.get("vat"));
            monthList.add(row);
        });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mrr", mrr);
        out.put("arr", mrr.multiply(BigDecimal.valueOf(12)));
        out.put("payingOffices", paying);
        out.put("unpaidTotal", unpaid);
        out.put("unpaidCount", unpaidCount);
        out.put("months", monthList);
        out.put("invoices", invoices.size() > 500 ? invoices.subList(0, 500) : invoices);
        return out;
    }

    /** A payment that came outside the payment page (bank transfer): the invoice is paid and applied, and audited. */
    @PostMapping("/api/ops/invoices/{id}/paid")
    Map<String, Object> markPaid(HttpServletRequest http, @PathVariable UUID id, @RequestBody ManualPayment p) {
        ops.require(http);
        if (p == null || p.officeId() == null) throw ApiException.badRequest("no_office", "المكتب مطلوب.");
        String note = p.note() == null || p.note().isBlank() ? null : p.note().trim();
        if (note == null) throw ApiException.badRequest("no_note", "اكتب مرجع التحويل أو ملاحظة للسجل.");
        boolean changed = TenantContext.call(p.officeId(), () -> billing.markPaidByOperator(id));
        BillingService.Invoice inv = TenantContext.call(p.officeId(), () -> billing.find(id)).orElseThrow();
        if (changed) {
            jdbc.sql("insert into ops_audit (action, office_id, target, detail, ip_hash) values ('INVOICE_PAID', ?, ?, ?, ?)")
                    .params(p.officeId(), inv.number(), note.length() > 500 ? note.substring(0, 500) : note, ClientIp.hash(http, "ops"))
                    .update();
        }
        return Map.of("id", id, "status", inv.status(), "changed", changed);
    }

    // ---- Usage and AI cost ----

    /**
     * Per office for a month (default this month): customer conversations, agent replies, tokens and estimated AI cost
     * (list price × tokens, see waslah.ops.*), what the office pays, and the difference.
     */
    @GetMapping("/api/ops/usage")
    Map<String, Object> usage(HttpServletRequest http, @RequestParam(required = false) String month) {
        ops.require(http);
        YearMonth ym;
        try {
            ym = month == null || month.isBlank() ? YearMonth.now(RIYADH) : YearMonth.parse(month);
        } catch (RuntimeException e) {
            throw ApiException.badRequest("bad_month", "الشهر بصيغة 2026-11.");
        }
        Timestamp from = Timestamp.from(ym.atDay(1).atStartOfDay(RIYADH).toInstant());
        Timestamp to = Timestamp.from(ym.plusMonths(1).atDay(1).atStartOfDay(RIYADH).toInstant());
        List<Map<String, Object>> rows = new ArrayList<>();
        long conv = 0, replies = 0, errors = 0, in = 0, outTok = 0;
        BigDecimal cost = BigDecimal.ZERO, revenue = BigDecimal.ZERO;
        for (Office o : offices()) {
            Map<String, Object> r = TenantContext.call(o.id(), () -> jdbc.sql("""
                            select (select count(*) from agent_sessions where started_at >= :f and started_at < :t),
                                   count(*) filter (where outcome = 'replied'),
                                   count(*) filter (where outcome = 'error'),
                                   coalesce(sum(input_tokens), 0), coalesce(sum(output_tokens), 0)
                            from agent_runs where created_at >= :f and created_at < :t""")
                    .param("f", from).param("t", to)
                    .query((rs, n) -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("conversations", rs.getLong(1));
                        m.put("replies", rs.getLong(2));
                        m.put("errors", rs.getLong(3));
                        m.put("inputTokens", rs.getLong(4));
                        m.put("outputTokens", rs.getLong(5));
                        return m;
                    }).single());
            BigDecimal c = aiCost((Long) r.get("inputTokens"), (Long) r.get("outputTokens"));
            BigDecimal rev = o.monthlyRevenue();
            r.put("officeId", o.id());
            r.put("office", o.name());
            r.put("plan", o.plan().name());
            r.put("status", o.suspended() ? "SUSPENDED" : o.status());
            r.put("aiCost", c);
            r.put("revenue", rev);
            r.put("margin", rev.subtract(c));
            rows.add(r);
            conv += (Long) r.get("conversations");
            replies += (Long) r.get("replies");
            errors += (Long) r.get("errors");
            in += (Long) r.get("inputTokens");
            outTok += (Long) r.get("outputTokens");
            cost = cost.add(c);
            revenue = revenue.add(rev);
        }
        rows.sort(Comparator.comparing((Map<String, Object> r) -> (BigDecimal) r.get("aiCost")).reversed());
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("conversations", conv);
        totals.put("replies", replies);
        totals.put("errors", errors);
        totals.put("inputTokens", in);
        totals.put("outputTokens", outTok);
        totals.put("aiCost", cost);
        totals.put("revenue", revenue);
        totals.put("margin", revenue.subtract(cost));
        totals.put("costPerReply", replies == 0 ? null : cost.divide(BigDecimal.valueOf(replies), 4, RoundingMode.HALF_UP));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("month", ym.toString());
        out.put("prices", Map.of("inputUsdPerMillion", inputUsdPerMillion, "outputUsdPerMillion", outputUsdPerMillion, "usdToSar", usdToSar));
        out.put("totals", totals);
        out.put("offices", rows);
        return out;
    }

    BigDecimal aiCost(long inputTokens, long outputTokens) {
        BigDecimal usd = inputUsdPerMillion.multiply(BigDecimal.valueOf(inputTokens))
                .add(outputUsdPerMillion.multiply(BigDecimal.valueOf(outputTokens)))
                .divide(BigDecimal.valueOf(1_000_000), 6, RoundingMode.HALF_UP);
        return usd.multiply(usdToSar).setScale(2, RoundingMode.HALF_UP);
    }

    // ---- Funnel ----

    /**
     * How far offices that signed up in the last {@code days} days (0 = all) got, in onboarding order: WhatsApp linked
     * (a real number, not the test one), listings added, a first customer conversation, a paid subscription. Each step
     * is counted on its own; "stuck" lists offices with the first step they have not done.
     */
    @GetMapping("/api/ops/funnel")
    Map<String, Object> funnel(HttpServletRequest http, @RequestParam(defaultValue = "90") int days) {
        ops.require(http);
        Instant since = days <= 0 ? Instant.EPOCH : LocalDate.now(RIYADH).minusDays(days - 1L).atStartOfDay(RIYADH).toInstant();
        int signedUp = 0, whatsapp = 0, listings = 0, conversation = 0, paid = 0;
        List<Map<String, Object>> stuck = new ArrayList<>();
        for (Office o : offices()) {
            if (o.createdAt().isBefore(since) || o.internal()) continue;
            signedUp++;
            boolean[] s = TenantContext.call(o.id(), () -> new boolean[]{
                    exists("select 1 from wa_accounts where verified_name is distinct from 'مكتب تجريبي'"),
                    exists("select 1 from listings"),
                    exists("select 1 from conversations c join contacts k on k.id = c.contact_id where k.wa_id <> '"
                            + ConversationStore.SANDBOX + "'"),
                    exists("select 1 from invoices where kind = 'SUBSCRIPTION' and status = 'PAID'")});
            if (s[0]) whatsapp++;
            if (s[1]) listings++;
            if (s[2]) conversation++;
            if (s[3]) paid++;
            String next = !s[0] ? "WHATSAPP" : !s[1] ? "LISTINGS" : !s[2] ? "CONVERSATION" : !s[3] ? "PAID" : null;
            if (next != null) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("officeId", o.id());
                m.put("office", o.name());
                m.put("createdAt", o.createdAt().toString());
                m.put("status", o.status());
                m.put("next", next);
                stuck.add(m);
            }
        }
        List<Map<String, Object>> steps = List.of(
                step("SIGNED_UP", signedUp), step("WHATSAPP", whatsapp), step("LISTINGS", listings),
                step("CONVERSATION", conversation), step("PAID", paid));
        stuck.sort(Comparator.comparing((Map<String, Object> m) -> (String) m.get("createdAt")).reversed());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("days", days);
        out.put("steps", steps);
        out.put("stuck", stuck.size() > 100 ? stuck.subList(0, 100) : stuck);
        return out;
    }

    private static Map<String, Object> step(String key, int count) {
        return Map.of("key", key, "count", count);
    }

    // ---- Helpers ----

    private List<Office> offices() {
        List<Office> out = new ArrayList<>();
        for (UUID id : jdbc.sql("select distinct office_id from users").query(UUID.class).list()) {
            TenantContext.call(id, () -> jdbc.sql("""
                            select name, subscription_status, plan, billing_cycle, suspended_at, created_at from offices""")
                    .query((rs, n) -> new Office(id, rs.getString(1), rs.getString(2), PlanLimits.Plan.valueOf(rs.getString(3)),
                            rs.getString(4), rs.getTimestamp(5) != null, rs.getTimestamp(6).toInstant(),
                            gateways.mode(id) == PaymentGateways.Mode.TEST))
                    .optional()).ifPresent(out::add);
        }
        return out;
    }

    private boolean exists(String sql) {
        return jdbc.sql("select exists(" + sql + ")").query(Boolean.class).single();
    }

    static BigDecimal sar(long halalas) {
        return BigDecimal.valueOf(halalas, 2);
    }

    private static String iso(Timestamp t) {
        return t == null ? null : t.toInstant().toString();
    }
}
