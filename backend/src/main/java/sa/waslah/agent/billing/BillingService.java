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
import org.springframework.transaction.support.TransactionTemplate;
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
                          Instant createdAt, Instant paidAt, long discountHalalas, String promoCode) {
    }

    private final JdbcClient jdbc;
    private final PaymentGateways gateways;
    private final BillingProperties props;
    private final Subscription subscription;
    private final PlanLimits plans;
    private final OfficeAlerts alerts;
    private final PromoService promos;
    private final TransactionTemplate tx;

    public BillingService(JdbcClient jdbc, PaymentGateways gateways, BillingProperties props, Subscription subscription,
                          PlanLimits plans, OfficeAlerts alerts, PromoService promos, TransactionTemplate tx) {
        this.promos = promos;
        this.tx = tx;
        this.jdbc = jdbc;
        this.gateways = gateways;
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
        // One checkout at a time per office (row lock): parallel requests cannot each take a discounted or free period.
        return tx.execute(status -> {
            promos.lockOffice();
            return checkoutLocked(officeId, plan, cycle);
        });
    }

    private Invoice checkoutLocked(UUID officeId, PlanLimits.Plan plan, String cycle) {
        int activeMarketers = jdbc.sql("select count(*) from marketers where active").query(Integer.class).single();
        if (activeMarketers > plan.marketers) {
            throw ApiException.conflict("too_many_marketers",
                    "باقة " + plan.arabicName + " تسمح بـ " + plan.marketers + " مسوّقين. عطّل بعض المسوّقين أولاً.");
        }
        BigDecimal price = "ANNUAL".equals(cycle) ? plan.annualSar : plan.monthlySar;
        long gross = halalas(price);
        Optional<PromoService.Applied> promo = promos.current();
        long discount = promo.map(a -> promos.discountHalalas(a, plan, cycle, gross)).orElse(0L);
        UUID promoId = discount > 0 ? promo.get().promo().id() : null;
        String promoCode = discount > 0 ? promo.get().promo().code() : null;

        // Reuse an unpaid invoice for the same choice (the same discount, the same gateway) instead of piling up new ones.
        Optional<Invoice> open = jdbc.sql(SELECT + " where kind = 'SUBSCRIPTION' and status = 'DUE' and plan = ? and cycle = ? "
                        + "and discount_halalas = ? and provider = ? and created_at > now() - interval '6 days' order by created_at desc limit 1")
                .params(plan.name(), cycle, discount, gateways.forOffice(officeId).name()).query(BillingService::map).optional();
        if (open.isPresent() && open.get().payUrl() != null) return open.get();

        String desc = "اشتراك حداء — باقة " + plan.arabicName + ("ANNUAL".equals(cycle) ? " (سنوي)" : " (شهري)")
                + (discount > 0 ? " — خصم " + promoCode : "");
        long subtotal = gross - discount;
        Invoice inv = create(officeId, "SUBSCRIPTION", plan.name(), cycle, desc, null, null, subtotal, discount, promoId,
                promoCode, subtotal > 0);
        // One open subscription invoice at a time: older unpaid ones are voided (paying one anyway still counts).
        jdbc.sql("update invoices set status = 'VOID' where kind = 'SUBSCRIPTION' and status = 'DUE' and id <> ?")
                .param(inv.id()).update();
        if (subtotal == 0) {
            // A 100% discount: nothing to pay, the period starts now.
            markPaid(inv);
            return find(inv.id()).orElseThrow();
        }
        return inv;
    }

    /**
     * Free months from the Hidaa team: a zero invoice for the record, then the plan is active for that long
     * (from the later of now, the paid-until date and the trial end).
     */
    public Invoice comp(UUID officeId, PlanLimits.Plan plan, int months, String reason) {
        if (months < 1 || months > 24) throw ApiException.badRequest("bad_months", "عدد الأشهر من 1 إلى 24.");
        String desc = "أشهر مجانية من فريق حداء — باقة " + plan.arabicName + " (" + months + (months == 1 ? " شهر)" : " أشهر)")
                + (reason == null || reason.isBlank() ? "" : " — " + reason.trim());
        // The office keeps its billing cycle: free months on an annual subscription do not turn it monthly.
        String cycle = subscription.state().cycle();
        Invoice inv = create(officeId, "COMP", plan.name(), cycle, desc, null, null, 0, 0, null, null, false);
        jdbc.sql("update invoices set status = 'PAID', paid_at = now() where id = ?").param(inv.id()).update();
        subscription.activateFor(plan, cycle, months, Instant.now());
        return find(inv.id()).orElseThrow();
    }

    /** Called after the customer returns from the payment page, or from the provider's notification. */
    public Invoice confirm(UUID invoiceId) {
        Invoice inv = find(invoiceId).orElseThrow(() -> ApiException.notFound("no_invoice", "الفاتورة غير موجودة."));
        if ("PAID".equals(inv.status()) || inv.providerRef() == null) return inv;
        if (!gateways.isPaid(inv.provider(), inv.providerRef())) return inv;
        boolean changed = markPaid(inv);
        if (changed && "OVERAGE".equals(inv.kind())) {
            Subscription.State s = subscription.state();
            if ("PAST_DUE".equals(s.status()) && s.paidUntil() != null && s.paidUntil().isAfter(Instant.now())) {
                subscription.setStatus("ACTIVE");
            }
        }
        return find(invoiceId).orElseThrow();
    }

    /**
     * The Hidaa team recorded a payment made outside the payment page (e.g. a bank transfer): the invoice is paid and
     * applied as if paid online. Returns false if it was already paid.
     */
    public boolean markPaidByOperator(UUID invoiceId) {
        Invoice inv = find(invoiceId).orElseThrow(() -> ApiException.notFound("no_invoice", "الفاتورة غير موجودة."));
        if ("PAID".equals(inv.status())) return false;
        boolean changed = tx.execute(status -> {
            promos.lockOffice();
            boolean c = markPaid(inv);
            if (c) jdbc.sql("update invoices set provider = coalesce(provider, 'MANUAL') where id = ?").param(inv.id()).update();
            return c;
        });
        if (changed && "OVERAGE".equals(inv.kind())) {
            Subscription.State s = subscription.state();
            if ("PAST_DUE".equals(s.status()) && s.paidUntil() != null && s.paidUntil().isAfter(Instant.now())) {
                subscription.setStatus("ACTIVE");
            }
        }
        return changed;
    }

    /** Marks a DUE (or voided, if the provider took the money anyway) invoice paid, once, and applies it. */
    private boolean markPaid(Invoice inv) {
        int changed = jdbc.sql("update invoices set status = 'PAID', paid_at = now() where id = ? and status in ('DUE', 'VOID')")
                .param(inv.id()).update();
        if (changed == 1 && "SUBSCRIPTION".equals(inv.kind())) {
            subscription.activate(PlanLimits.Plan.valueOf(inv.plan()), inv.cycle(), Instant.now());
            UUID promoId = jdbc.sql("select promo_code_id from invoices where id = ?").param(inv.id())
                    .query((rs, n) -> rs.getObject(1, UUID.class)).optional().orElse(null);
            promos.onPaid(promoId);
        }
        return changed == 1;
    }

    /** Overage for a finished month: replies over the allowance times the plan's per-reply price. Idempotent. */
    public Optional<Invoice> overage(UUID officeId, LocalDate monthStart) {
        LocalDate next = monthStart.plusMonths(1);
        PlanLimits.Usage u = plans.usageBetween(monthStart, next);
        if (u.overage() <= 0) return Optional.empty();
        boolean exists = jdbc.sql("select exists(select 1 from invoices where kind = 'OVERAGE' and period_start = ?)")
                .param(java.sql.Date.valueOf(monthStart)).query(Boolean.class).single();
        if (exists) return Optional.empty();
        String desc = "محادثات إضافية لشهر " + monthStart.toString().substring(0, 7) + ": " + u.overage() + " محادثة × "
                + u.plan().overagePerConversationSar.stripTrailingZeros().toPlainString() + " ريال";
        Invoice inv = create(officeId, "OVERAGE", u.plan().name(), null, desc, monthStart, next.minusDays(1),
                halalas(u.overageSar()), 0, null, null, true);
        alerts.raise(officeId, "INVOICE_DUE", inv.id(), "فاتورة المحادثات الإضافية لشهر " + monthStart.toString().substring(0, 7)
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
        if (!(gateways.main() instanceof MockPaymentGateway mock)) {
            throw ApiException.notFound("not_found", "غير متاح.");
        }
        Invoice inv = find(invoiceId).orElseThrow(() -> ApiException.notFound("no_invoice", "الفاتورة غير موجودة."));
        mock.pay(inv.providerRef());
        return confirm(invoiceId);
    }

    private Invoice create(UUID officeId, String kind, String plan, String cycle, String description, LocalDate from,
                           LocalDate to, long subtotal, long discount, UUID promoId, String promoCode, boolean withPayPage) {
        long vat = Math.round(subtotal * props.vatPercent() / 100.0);
        UUID id = UUID.randomUUID();
        String number = "W" + LocalDate.now(RIYADH).toString().replace("-", "").substring(2) + "-"
                + id.toString().substring(0, 6).toUpperCase();
        jdbc.sql("""
                insert into invoices (id, office_id, number, kind, plan, cycle, description, period_start, period_end,
                                      subtotal_halalas, vat_halalas, total_halalas, discount_halalas, promo_code_id, promo_code)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(id, officeId, number, kind, plan, cycle, description,
                        from == null ? null : java.sql.Date.valueOf(from), to == null ? null : java.sql.Date.valueOf(to),
                        subtotal, vat, subtotal + vat, discount, promoId, promoCode)
                .update();
        if (withPayPage) {
            String success = props.portalBaseUrl() + "/billing?invoice=" + id;
            String callback = props.apiBaseUrl() == null || props.apiBaseUrl().isBlank() ? null
                    : props.apiBaseUrl() + "/webhooks/moyasar";
            if (callback != null && MoyasarWebhookAuth.configured(props.moyasarWebhookSecret())) {
                callback += "?token=" + MoyasarWebhookAuth.callbackToken(props.moyasarWebhookSecret());
            }
            try {
                PaymentGateway gateway = gateways.forOffice(officeId);
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
                   total_halalas, status, provider, provider_ref, pay_url, created_at, paid_at, discount_halalas,
                   promo_code from invoices""";

    private static Invoice map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        java.sql.Date ps = rs.getDate("period_start"), pe = rs.getDate("period_end");
        Timestamp paid = rs.getTimestamp("paid_at");
        return new Invoice(rs.getObject("id", UUID.class), rs.getString("number"), rs.getString("kind"), rs.getString("plan"),
                rs.getString("cycle"), rs.getString("description"), ps == null ? null : ps.toLocalDate(),
                pe == null ? null : pe.toLocalDate(), rs.getLong("subtotal_halalas"), rs.getLong("vat_halalas"),
                rs.getLong("total_halalas"), rs.getString("status"), rs.getString("provider"), rs.getString("provider_ref"),
                rs.getString("pay_url"), rs.getTimestamp("created_at").toInstant(), paid == null ? null : paid.toInstant(),
                rs.getLong("discount_halalas"), rs.getString("promo_code"));
    }
}
