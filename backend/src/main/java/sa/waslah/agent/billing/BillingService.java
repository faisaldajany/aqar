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
import sa.waslah.agent.affiliate.AffiliateLedger;
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
                          Instant createdAt, Instant paidAt, long discountHalalas, String promoCode,
                          long refundedHalalas, Instant refundedAt, String lastFailureReason, Instant lastFailureAt) {
    }

    /** What a refund changed: the total refunded so far, whether it was all of it, and whether the period was taken back. */
    public record Refund(Invoice invoice, long refundedHalalas, boolean full, boolean periodTakenBack) {
    }

    /** Moyasar's hosted pages work for 7 days; a page this close to the end is replaced rather than handed out. */
    static final String PAGE_STILL_GOOD = "pay_url is not null and pay_url_expires_at > now() + interval '30 minutes'";
    static final java.time.Duration PAGE_LIFETIME = java.time.Duration.ofDays(7);

    private final JdbcClient jdbc;
    private final PaymentGateways gateways;
    private final BillingProperties props;
    private final Subscription subscription;
    private final PlanLimits plans;
    private final OfficeAlerts alerts;
    private final PromoService promos;
    private final TransactionTemplate tx;
    private final BillingMail mail;
    private final AffiliateLedger affiliates;

    public BillingService(JdbcClient jdbc, PaymentGateways gateways, BillingProperties props, Subscription subscription,
                          PlanLimits plans, OfficeAlerts alerts, PromoService promos, TransactionTemplate tx, BillingMail mail,
                          AffiliateLedger affiliates) {
        this.affiliates = affiliates;
        this.mail = mail;
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
        // …and the same VAT: an invoice made before the VAT rate changed is not handed out again.
        long subtotalNow = gross - discount;
        long vatNow = Math.round(subtotalNow * props.vatPercent() / 100.0);
        Optional<Invoice> open = jdbc.sql(SELECT + " where kind = 'SUBSCRIPTION' and status = 'DUE' and plan = ? and cycle = ? "
                        + "and discount_halalas = ? and vat_halalas = ? and provider = ? and " + PAGE_STILL_GOOD + " order by created_at desc limit 1")
                .params(plan.name(), cycle, discount, vatNow, gateways.forOffice(officeId).name()).query(BillingService::map).optional();
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
        if (changed) mail.paid(find(invoiceId).orElseThrow());
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
        return markPaidByOperator(invoiceId, null);
    }

    /** @param reference the transfer reference the team recorded, shown on the receipt */
    public boolean markPaidByOperator(UUID invoiceId, String reference) {
        Invoice inv = find(invoiceId).orElseThrow(() -> ApiException.notFound("no_invoice", "الفاتورة غير موجودة."));
        if ("PAID".equals(inv.status())) return false;
        String ref = reference == null || reference.isBlank() ? null : reference.trim().substring(0, Math.min(100, reference.trim().length()));
        boolean changed = tx.execute(status -> {
            promos.lockOffice();
            boolean c = markPaid(inv);
            if (c) {
                jdbc.sql("update invoices set provider = coalesce(provider, 'MANUAL'), paid_method = 'MANUAL', paid_reference = ? where id = ?")
                        .params(ref, inv.id()).update();
            }
            return c;
        });
        if (changed) mail.paid(find(invoiceId).orElseThrow());
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
        // The office's marketer earns their share (not on test payments: the ledger checks the invoice).
        if (changed == 1) affiliates.onPaid(inv.id());
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
                + " جاهزة: " + sar(inv.totalHalalas()) + " ريال" + (inv.vatHalalas() > 0 ? " شامل الضريبة." : "."), "invoice-" + inv.id());
        mail.due(inv);
        return Optional.of(inv);
    }

    public List<Invoice> list() {
        return jdbc.sql(SELECT + " order by created_at desc limit 50").query(BillingService::map).list();
    }

    public Optional<Invoice> find(UUID id) {
        return jdbc.sql(SELECT + " where id = ?").param(id).query(BillingService::map).optional();
    }

    /** Everything the printable receipt shows: the invoice, how it was paid, the office, and the seller. */
    public java.util.Map<String, Object> receipt(UUID invoiceId) {
        Invoice inv = find(invoiceId).orElseThrow(() -> ApiException.notFound("no_invoice", "الفاتورة غير موجودة."));
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("invoice", inv);
        jdbc.sql("select paid_method, paid_last4, paid_reference from invoices where id = ?").param(invoiceId)
                .query((rs, n) -> {
                    out.put("paidMethod", rs.getString(1));
                    out.put("paidLast4", rs.getString(2));
                    out.put("paidReference", rs.getString(3));
                    return null;
                }).list();
        UUID officeId = jdbc.sql("select office_id from invoices where id = ?").param(invoiceId)
                .query((rs, n) -> rs.getObject(1, UUID.class)).single();
        java.util.Map<String, Object> office = new java.util.LinkedHashMap<>();
        jdbc.sql("select name, fal_license from offices").query((rs, n) -> {
            office.put("name", rs.getString(1));
            office.put("falLicense", rs.getString(2));
            return null;
        }).list();
        office.put("email", jdbc.sql("select email from users where office_id = ? and role = 'OWNER' and disabled_at is null order by created_at limit 1")
                .param(officeId).query(String.class).optional().orElse(null));
        out.put("office", office);
        java.util.Map<String, Object> seller = new java.util.LinkedHashMap<>();
        seller.put("name", props.sellerName());
        seller.put("vatNumber", props.sellerVatNumber());
        seller.put("cr", props.sellerCr());
        seller.put("address", props.sellerAddress());
        out.put("seller", seller);
        out.put("vatPercent", props.vatPercent());
        out.put("test", PaymentGateways.TEST.equals(inv.provider()));
        return out;
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
            try {
                attachPayPage(officeId, id, number, subtotal + vat, description);
            } catch (RuntimeException e) {
                log.warn("Payment page for invoice {} not created: {}", id, e.getMessage());
                if ("SUBSCRIPTION".equals(kind)) {
                    throw ApiException.badGateway("payment_unavailable", "تعذّر فتح صفحة الدفع الآن. حاول بعد قليل.");
                }
            }
        }
        return find(id).orElseThrow();
    }

    /** A hosted payment page for the invoice, from the office's gateway; replaces the one it had. */
    private void attachPayPage(UUID officeId, UUID id, String number, long totalHalalas, String description) {
        String success = props.portalBaseUrl() + "/billing?invoice=" + id;
        String callback = props.apiBaseUrl() == null || props.apiBaseUrl().isBlank() ? null
                : props.apiBaseUrl() + "/webhooks/moyasar";
        if (callback != null && MoyasarWebhookAuth.configured(props.moyasarWebhookSecret())) {
            callback += "?token=" + MoyasarWebhookAuth.callbackToken(props.moyasarWebhookSecret());
        }
        PaymentGateway gateway = gateways.forOffice(officeId);
        PaymentGateway.Checkout c = gateway.create(totalHalalas, description + " — " + number, id.toString(), success, callback);
        jdbc.sql("update invoices set provider = ?, provider_ref = ?, pay_url = ?, pay_url_expires_at = ? where id = ?")
                .params(gateway.name(), c.providerRef(), c.url(), Timestamp.from(Instant.now().plus(PAGE_LIFETIME)), id).update();
        jdbc.sql("insert into billing_refs (provider_ref, office_id, invoice_id) values (?, ?, ?) on conflict do nothing")
                .params(c.providerRef(), officeId, id).update();
    }

    /**
     * «ادفع» on a due invoice: its payment page, or a new one when that page expired or is about to (Moyasar pages last
     * 7 days, and an overage invoice can wait longer than that). Paid in the meantime: returned as paid.
     */
    public Invoice payPage(UUID invoiceId) {
        Invoice inv = confirm(invoiceId);
        if (!"DUE".equals(inv.status()) || inv.totalHalalas() <= 0) return inv;
        boolean good = jdbc.sql("select " + PAGE_STILL_GOOD + " from invoices where id = ?").param(invoiceId)
                .query(Boolean.class).single();
        if (good) return inv;
        UUID officeId = jdbc.sql("select office_id from invoices where id = ?").param(invoiceId)
                .query((rs, n) -> rs.getObject(1, UUID.class)).single();
        try {
            attachPayPage(officeId, invoiceId, inv.number(), inv.totalHalalas(), inv.description());
        } catch (RuntimeException e) {
            log.warn("New payment page for invoice {} not created: {}", invoiceId, e.getMessage());
            throw ApiException.badGateway("payment_unavailable", "تعذّر فتح صفحة الدفع الآن. حاول بعد قليل.");
        }
        return find(invoiceId).orElseThrow();
    }

    /** The provider says the page expired or was canceled: the next «ادفع» makes a new one. */
    public void payPageGone(UUID invoiceId, String providerRef) {
        jdbc.sql("update invoices set pay_url_expires_at = now() where id = ? and status = 'DUE' and provider_ref = ?")
                .params(invoiceId, providerRef).update();
    }

    /** A declined attempt on the payment page, with the provider's reason: shown on the billing page and in /ops. */
    public void paymentFailed(UUID invoiceId, String reason) {
        String r = reason == null || reason.isBlank() ? null : reason.length() > 300 ? reason.substring(0, 300) : reason.trim();
        jdbc.sql("update invoices set last_failure_at = now(), last_failure_reason = ? where id = ? and status = 'DUE'")
                .params(r, invoiceId).update();
    }

    /**
     * The provider reports {@code refundedHalalas} refunded on the invoice's payment so far (a total, so a repeated
     * notification changes nothing). All of it: the invoice is REFUNDED, and a subscription's period is taken back
     * (paid-until moves back by the months it bought; the usual renewal and grace follow). Part of it (a correction):
     * recorded, the subscription stays. Empty when nothing changed.
     */
    public Optional<Refund> refunded(UUID invoiceId, long refundedHalalas) {
        return tx.execute(status -> {
            promos.lockOffice();
            Invoice inv = find(invoiceId).orElse(null);
            if (inv == null || inv.totalHalalas() <= 0) return Optional.<Refund>empty();
            if (!"PAID".equals(inv.status()) && !"REFUNDED".equals(inv.status())) return Optional.<Refund>empty();
            long amount = Math.min(refundedHalalas, inv.totalHalalas());
            if (amount <= inv.refundedHalalas()) return Optional.<Refund>empty();
            jdbc.sql("update invoices set refunded_halalas = ?, refunded_at = now() where id = ?").params(amount, invoiceId).update();
            boolean full = amount >= inv.totalHalalas();
            boolean takenBack = false;
            if (full && jdbc.sql("update invoices set status = 'REFUNDED' where id = ? and status = 'PAID'").param(invoiceId).update() == 1
                    && "SUBSCRIPTION".equals(inv.kind())) {
                subscription.takeBack("ANNUAL".equals(inv.cycle()) ? 12 : 1);
                takenBack = true;
            }
            affiliates.onRefund(invoiceId);
            return Optional.of(new Refund(find(invoiceId).orElseThrow(), amount, full, takenBack));
        });
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
                   promo_code, refunded_halalas, refunded_at, last_failure_reason, last_failure_at from invoices""";

    private static Invoice map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        java.sql.Date ps = rs.getDate("period_start"), pe = rs.getDate("period_end");
        Timestamp paid = rs.getTimestamp("paid_at"), refunded = rs.getTimestamp("refunded_at"),
                failed = rs.getTimestamp("last_failure_at");
        return new Invoice(rs.getObject("id", UUID.class), rs.getString("number"), rs.getString("kind"), rs.getString("plan"),
                rs.getString("cycle"), rs.getString("description"), ps == null ? null : ps.toLocalDate(),
                pe == null ? null : pe.toLocalDate(), rs.getLong("subtotal_halalas"), rs.getLong("vat_halalas"),
                rs.getLong("total_halalas"), rs.getString("status"), rs.getString("provider"), rs.getString("provider_ref"),
                rs.getString("pay_url"), rs.getTimestamp("created_at").toInstant(), paid == null ? null : paid.toInstant(),
                rs.getLong("discount_halalas"), rs.getString("promo_code"), rs.getLong("refunded_halalas"),
                refunded == null ? null : refunded.toInstant(), rs.getString("last_failure_reason"),
                failed == null ? null : failed.toInstant());
    }
}
