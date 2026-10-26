package sa.waslah.agent.billing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import sa.waslah.agent.common.ApiException;
import sa.waslah.agent.office.OfficeAlerts;

/** Invoices and payments. Everything except {@link #officeForProviderRef} runs under the office's tenant. */
@Service
public class BillingService {

    private static final Logger log = LoggerFactory.getLogger(BillingService.class);
    static final ZoneId RIYADH = ZoneId.of("Asia/Riyadh");

    public record Invoice(UUID id, String number, String kind, String plan, String cycle, String description,
                          LocalDate periodStart, LocalDate periodEnd, long subtotalHalalas, long vatHalalas,
                          long totalHalalas, String status, String provider, String providerRef, String payUrl,
                          Instant createdAt, Instant paidAt) {
    }

    private final JdbcClient jdbc;
    private final PaymentGateway gateway;
    private final BillingProperties props;
    private final Subscription subscription;
    private final PlanLimits plans;
    private final OfficeAlerts alerts;

    public BillingService(JdbcClient jdbc, PaymentGateway gateway, BillingProperties props, Subscription subscription,
                          PlanLimits plans, OfficeAlerts alerts) {
        this.jdbc = jdbc;
        this.gateway = gateway;
        this.props = props;
        this.subscription = subscription;
        this.plans = plans;
        this.alerts = alerts;
    }

    /** Creates a subscription invoice and its hosted payment page. */
    public Invoice checkout(UUID officeId, PlanLimits.Plan plan, String cycle) {
        if (!"MONTHLY".equals(cycle) && !"ANNUAL".equals(cycle)) {
            throw ApiException.badRequest("bad_cycle", "اختر الدفع الشهري أو السنوي.");
        }
        int activeMarketers = jdbc.sql("select count(*) from marketers where active").query(Integer.class).single();
        if (activeMarketers > plan.marketers) {
            throw ApiException.conflict("too_many_marketers",
                    "باقة " + plan.arabicName + " تسمح بـ " + plan.marketers + " مسوّقين. عطّل بعض المسوّقين أولاً.");
        }
        // Reuse an unpaid invoice for the same choice instead of piling up new ones.
        Optional<Invoice> open = jdbc.sql(SELECT + " where kind = 'SUBSCRIPTION' and status = 'DUE' and plan = ? and cycle = ? "
                        + "and created_at > now() - interval '6 days' order by created_at desc limit 1")
                .params(plan.name(), cycle).query(BillingService::map).optional();
        if (open.isPresent() && open.get().payUrl() != null) return open.get();

        BigDecimal price = "ANNUAL".equals(cycle) ? plan.annualSar : plan.monthlySar;
        String desc = "اشتراك حداء — باقة " + plan.arabicName + ("ANNUAL".equals(cycle) ? " (سنوي)" : " (شهري)");
        return create(officeId, "SUBSCRIPTION", plan.name(), cycle, desc, null, null, halalas(price), true);
    }

    /** Called after the customer returns from the payment page, or from the provider's notification. */
    public Invoice confirm(UUID invoiceId) {
        Invoice inv = find(invoiceId).orElseThrow(() -> ApiException.notFound("no_invoice", "الفاتورة غير موجودة."));
        if ("PAID".equals(inv.status()) || inv.providerRef() == null) return inv;
        if (!gateway.isPaid(inv.providerRef())) return inv;
        int changed = jdbc.sql("update invoices set status = 'PAID', paid_at = now() where id = ? and status = 'DUE'")
                .param(invoiceId).update();
        if (changed == 1 && "SUBSCRIPTION".equals(inv.kind())) {
            subscription.activate(PlanLimits.Plan.valueOf(inv.plan()), inv.cycle(), Instant.now());
        }
        if (changed == 1 && "OVERAGE".equals(inv.kind())) {
            Subscription.State s = subscription.state();
            if ("PAST_DUE".equals(s.status()) && s.paidUntil() != null && s.paidUntil().isAfter(Instant.now())) {
                subscription.setStatus("ACTIVE");
            }
        }
        return find(invoiceId).orElseThrow();
    }

    /** Overage for a finished month: replies over the allowance times the plan's per-reply price. Idempotent. */
    public Optional<Invoice> overage(UUID officeId, LocalDate monthStart) {
        LocalDate next = monthStart.plusMonths(1);
        PlanLimits.Usage u = plans.usageBetween(monthStart, next);
        if (u.overageReplies() <= 0) return Optional.empty();
        boolean exists = jdbc.sql("select exists(select 1 from invoices where kind = 'OVERAGE' and period_start = ?)")
                .param(java.sql.Date.valueOf(monthStart)).query(Boolean.class).single();
        if (exists) return Optional.empty();
        String desc = "ردود إضافية لشهر " + monthStart.toString().substring(0, 7) + ": " + u.overageReplies() + " رد × "
                + u.plan().overagePerReplySar.multiply(BigDecimal.valueOf(100)).stripTrailingZeros().toPlainString() + " هللة";
        Invoice inv = create(officeId, "OVERAGE", u.plan().name(), null, desc, monthStart, next.minusDays(1),
                halalas(u.overageSar()), true);
        alerts.raise(officeId, "INVOICE_DUE", inv.id(), "فاتورة الردود الإضافية لشهر " + monthStart.toString().substring(0, 7)
                + " جاهزة: " + sar(inv.totalHalalas()) + " ريال شامل الضريبة.", "invoice-" + inv.id());
        return Optional.of(inv);
    }

    public List<Invoice> list() {
        return jdbc.sql(SELECT + " order by created_at desc limit 50").query(BillingService::map).list();
    }

    public Optional<Invoice> find(UUID id) {
        return jdbc.sql(SELECT + " where id = ?").param(id).query(BillingService::map).optional();
    }

    /** Payment notifications arrive without a session; this table maps the provider's id back to the office. */
    public Optional<UUID[]> officeForProviderRef(String providerRef) {
        return jdbc.sql("select office_id, invoice_id from billing_refs where provider_ref = ?").param(providerRef)
                .query((rs, n) -> new UUID[]{rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)}).optional();
    }

    /** Development only: pays a mock invoice. */
    public Invoice mockPay(UUID invoiceId) {
        if (!(gateway instanceof MockPaymentGateway mock)) {
            throw ApiException.notFound("not_found", "غير متاح.");
        }
        Invoice inv = find(invoiceId).orElseThrow(() -> ApiException.notFound("no_invoice", "الفاتورة غير موجودة."));
        mock.pay(inv.providerRef());
        return confirm(invoiceId);
    }

    private Invoice create(UUID officeId, String kind, String plan, String cycle, String description, LocalDate from,
                           LocalDate to, long subtotal, boolean withPayPage) {
        long vat = Math.round(subtotal * props.vatPercent() / 100.0);
        UUID id = UUID.randomUUID();
        String number = "W" + LocalDate.now(RIYADH).toString().replace("-", "").substring(2) + "-"
                + id.toString().substring(0, 6).toUpperCase();
        jdbc.sql("""
                insert into invoices (id, office_id, number, kind, plan, cycle, description, period_start, period_end,
                                      subtotal_halalas, vat_halalas, total_halalas)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(id, officeId, number, kind, plan, cycle, description,
                        from == null ? null : java.sql.Date.valueOf(from), to == null ? null : java.sql.Date.valueOf(to),
                        subtotal, vat, subtotal + vat)
                .update();
        if (withPayPage) {
            String success = props.portalBaseUrl() + "/billing?invoice=" + id;
            String callback = props.apiBaseUrl() == null || props.apiBaseUrl().isBlank() ? null
                    : props.apiBaseUrl() + "/webhooks/moyasar";
            try {
                PaymentGateway.Checkout c = gateway.create(subtotal + vat, description + " — " + number, id.toString(), success, callback);
                jdbc.sql("update invoices set provider = ?, provider_ref = ?, pay_url = ? where id = ?")
                        .params(gateway.name(), c.providerRef(), c.url(), id).update();
                jdbc.sql("insert into billing_refs (provider_ref, office_id, invoice_id) values (?, ?, ?) on conflict do nothing")
                        .params(c.providerRef(), officeId, id).update();
            } catch (RuntimeException e) {
                log.warn("Payment page for invoice {} not created: {}", id, e.getMessage());
                if ("SUBSCRIPTION".equals(kind)) {
                    throw ApiException.badGateway("payment_unavailable", "تعذّر فتح صفحة الدفع الآن. حاول بعد قليل.");
                }
            }
        }
        return find(id).orElseThrow();
    }

    static long halalas(BigDecimal sar) {
        return sar.multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    static String sar(long halalas) {
        return BigDecimal.valueOf(halalas, 2).stripTrailingZeros().toPlainString();
    }

    private static final String SELECT = """
            select id, number, kind, plan, cycle, description, period_start, period_end, subtotal_halalas, vat_halalas,
                   total_halalas, status, provider, provider_ref, pay_url, created_at, paid_at from invoices""";

    private static Invoice map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        java.sql.Date ps = rs.getDate("period_start"), pe = rs.getDate("period_end");
        Timestamp paid = rs.getTimestamp("paid_at");
        return new Invoice(rs.getObject("id", UUID.class), rs.getString("number"), rs.getString("kind"), rs.getString("plan"),
                rs.getString("cycle"), rs.getString("description"), ps == null ? null : ps.toLocalDate(),
                pe == null ? null : pe.toLocalDate(), rs.getLong("subtotal_halalas"), rs.getLong("vat_halalas"),
                rs.getLong("total_halalas"), rs.getString("status"), rs.getString("provider"), rs.getString("provider_ref"),
                rs.getString("pay_url"), rs.getTimestamp("created_at").toInstant(), paid == null ? null : paid.toInstant());
    }
}
