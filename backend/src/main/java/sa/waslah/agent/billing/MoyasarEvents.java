package sa.waslah.agent.billing;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import sa.waslah.agent.common.Mailer;
import sa.waslah.agent.office.OfficeAlerts;
import sa.waslah.agent.tenant.TenantContext;

/**
 * What a Moyasar notification means for Hidaa. The body only says which payment or invoice to look at: every status
 * (paid, failed, refunded, expired) is asked again from Moyasar's API with the key that created the invoice, and must
 * belong to that very invoice, so a forged or replayed notification changes nothing. Payout problems cannot be looked
 * up that way; they only raise an email to the team, and only from a notification carrying the webhook's secret token.
 *
 * <ul>
 *   <li>payment_paid (and an invoice's own callback): the invoice is paid and applied.</li>
 *   <li>payment_failed: the reason is kept on the invoice (billing page, /ops).</li>
 *   <li>payment_refunded, payment_voided: the refund is recorded; all of it takes a subscription's period back.
 *       The office sees a notice and the team gets an email.</li>
 *   <li>invoice_expired, invoice_canceled: the payment page is dead; the next «ادفع» makes a new one.</li>
 *   <li>payout_failed, payout_returned, payout_canceled: Moyasar could not send the money to the bank; email the team.</li>
 * </ul>
 */
@Service
public class MoyasarEvents {

    private static final Logger log = LoggerFactory.getLogger(MoyasarEvents.class);

    private final BillingService billing;
    private final PaymentGateways gateways;
    private final Mailer mailer;
    private final OfficeAlerts alerts;
    private final JdbcClient jdbc;
    private final String team;
    private final BillingMail mail;

    public MoyasarEvents(BillingService billing, PaymentGateways gateways, Mailer mailer, OfficeAlerts alerts, JdbcClient jdbc,
                         @Value("${waslah.support.forward-to:}") String team, BillingMail mail) {
        this.mail = mail;
        this.billing = billing;
        this.gateways = gateways;
        this.mailer = mailer;
        this.alerts = alerts;
        this.jdbc = jdbc;
        this.team = team;
    }

    /** @param authenticated the notification carried the webhook's secret token (or the derived callback token) */
    public Map<String, Object> handle(JsonNode body, boolean authenticated) {
        String type = body.path("type").asText("").toLowerCase(java.util.Locale.ROOT);
        JsonNode data = body.path("data");
        try {
            return switch (type) {
                case "payment_failed", "payment_faild" -> payment(data, false);
                case "payment_refunded", "payment_voided" -> payment(data, true);
                case "invoice_expired", "invoice_canceled", "invoice_cancelled" -> pageGone(data);
                case "payout_failed", "payout_returned", "payout_canceled", "payout_cancelled" ->
                        payout(type, data, body.path("live").asBoolean(true), authenticated);
                default -> paid(body);
            };
        } catch (RuntimeException e) {
            // Acknowledged anyway: Moyasar retrying the same event would hit the same problem.
            log.warn("Moyasar {} notification not processed: {}", type.isEmpty() ? "invoice" : type, e.getMessage());
            return Map.of("ok", false);
        }
    }

    /** The invoice's callback or payment_paid: re-checked with Moyasar, then paid. */
    private Map<String, Object> paid(JsonNode body) {
        List<String> refs = new ArrayList<>();
        for (JsonNode n : List.of(body.path("id"), body.path("invoice_id"), body.path("data").path("id"),
                body.path("data").path("invoice_id"))) {
            if (n.isTextual()) refs.add(n.asText());
        }
        for (String ref : refs) {
            Optional<UUID[]> hit = billing.officeForProviderRef(ref);
            if (hit.isPresent()) {
                UUID office = hit.get()[0], invoice = hit.get()[1];
                TenantContext.run(office, () -> billing.confirm(invoice));
                return Map.of("ok", true);
            }
        }
        // Not one of our invoices (another event, or another app on the same Moyasar account): acknowledged, so
        // Moyasar does not keep retrying it.
        return Map.of("ok", false);
    }

    /** payment_failed / payment_refunded / payment_voided: the payment as Moyasar reports it, on one of our invoices. */
    private Map<String, Object> payment(JsonNode data, boolean refund) {
        String paymentId = data.path("id").asText(null);
        String invoiceRef = data.path("invoice_id").asText(null);
        if (paymentId == null || invoiceRef == null) return Map.of("ok", false);
        Optional<UUID[]> hit = billing.officeForProviderRef(invoiceRef);
        if (hit.isEmpty()) return Map.of("ok", false);
        UUID office = hit.get()[0], invoice = hit.get()[1];
        return TenantContext.call(office, () -> {
            BillingService.Invoice inv = billing.find(invoice).orElse(null);
            PaymentGateway g = inv == null ? null : gateways.byProvider(inv.provider());
            Optional<PaymentGateway.Payment> p = g == null ? Optional.empty() : g.payment(paymentId);
            if (p.isEmpty() || !invoiceRef.equals(p.get().invoiceRef())) return Map.<String, Object>of("ok", false);
            PaymentGateway.Payment pay = p.get();
            if (!refund) {
                if ("failed".equalsIgnoreCase(pay.status())) billing.paymentFailed(invoice, pay.message());
                return Map.<String, Object>of("ok", true);
            }
            // A voided payment was never settled: all of it comes back.
            long back = "voided".equalsIgnoreCase(pay.status()) ? Math.max(pay.amountHalalas(), pay.refundedHalalas()) : pay.refundedHalalas();
            if (back <= 0) return Map.<String, Object>of("ok", false);
            billing.refunded(invoice, back).ifPresent(r -> tell(office, r));
            return Map.<String, Object>of("ok", true);
        });
    }

    private static final Set<String> GONE = Set.of("expired", "canceled", "cancelled");

    /** invoice_expired / invoice_canceled: only for the page the invoice uses now, and only if Moyasar says so. */
    private Map<String, Object> pageGone(JsonNode data) {
        String ref = data.path("id").asText(null);
        if (ref == null) return Map.of("ok", false);
        Optional<UUID[]> hit = billing.officeForProviderRef(ref);
        if (hit.isEmpty()) return Map.of("ok", false);
        UUID office = hit.get()[0], invoice = hit.get()[1];
        return TenantContext.call(office, () -> {
            BillingService.Invoice inv = billing.find(invoice).orElse(null);
            PaymentGateway g = inv == null ? null : gateways.byProvider(inv.provider());
            String status = g == null ? null : g.invoiceStatus(ref);
            if (status == null || !GONE.contains(status.toLowerCase(java.util.Locale.ROOT))) {
                return Map.<String, Object>of("ok", false);
            }
            billing.payPageGone(invoice, ref);
            return Map.<String, Object>of("ok", true);
        });
    }

    /** Moyasar could not send money to the bank account. */
    private Map<String, Object> payout(String type, JsonNode data, boolean live, boolean authenticated) {
        if (!authenticated) {
            log.warn("Moyasar {} ignored: set MOYASAR_WEBHOOK_SECRET so payout notifications can be trusted", type);
            return Map.of("ok", false);
        }
        String what = switch (type) {
            case "payout_failed" -> "فشل التحويل";
            case "payout_returned" -> "أعاده البنك";
            default -> "أُلغي التحويل";
        };
        StringBuilder text = new StringBuilder("لم يصل تحويل من ميسر إلى الحساب البنكي: ").append(what).append(".\n\n");
        line(text, "رقم التحويل في ميسر", data.path("id").asText(null));
        if (data.path("amount").canConvertToLong()) line(text, "المبلغ", BillingService.sar(data.path("amount").asLong()) + " ريال");
        line(text, "الحالة", data.path("status").asText(null));
        for (String k : List.of("failure_reason", "reason", "message")) line(text, "السبب", data.path(k).asText(null));
        line(text, "الوضع", live ? "حقيقي" : "تجريبي");
        text.append("\nراجع لوحة ميسر ← التحويلات، وتأكد من بيانات الحساب البنكي (IBAN).\n");
        email("حداء: تحويل من ميسر لم يصل إلى البنك", text.toString());
        return Map.of("ok", true);
    }

    /** The office sees the refund in its portal; the team gets an email (they made it in Moyasar, this confirms it). */
    private void tell(UUID office, BillingService.Refund r) {
        BillingService.Invoice inv = r.invoice();
        String amount = BillingService.sar(r.refundedHalalas()) + " ريال";
        String officeMsg = r.full()
                ? "استُرد مبلغ الفاتورة " + inv.number() + " كاملاً (" + amount + ")" + (r.periodTakenBack() ? " وأُلغيت فترة الاشتراك التي دفعتها." : ".")
                : "استُرد " + amount + " من الفاتورة " + inv.number() + ". اشتراكك مستمر كما هو.";
        officeMsg += " يصل المبلغ إلى وسيلة الدفع نفسها خلال 5 إلى 14 يوم عمل حسب البنك.";
        alerts.raise(office, "REFUND", inv.id(), officeMsg, "refund-" + inv.id() + "-" + r.refundedHalalas());
        mail.refunded(r);
        String name = jdbc.sql("select name from offices").query(String.class).optional().orElse("");
        String paidUntil = jdbc.sql("select to_char(paid_until at time zone 'Asia/Riyadh', 'YYYY-MM-DD') from offices")
                .query(String.class).optional().orElse(null);
        StringBuilder text = new StringBuilder();
        text.append(r.full() ? "استرداد كامل" : "استرداد جزئي").append(" على فاتورة ").append(inv.number())
                .append(" لمكتب ").append(name).append(".\n\n");
        line(text, "المسترد", amount + " من " + BillingService.sar(inv.totalHalalas()) + " ريال");
        line(text, "البيان", inv.description());
        if (r.periodTakenBack()) line(text, "الاشتراك", "أُعيدت الفترة، والمدفوع الآن حتى " + (paidUntil == null ? "—" : paidUntil));
        else if (!r.full()) line(text, "الاشتراك", "كما هو (استرداد جزئي)");
        text.append("\nيظهر في /ops ← الإيرادات. أصدر إشعاراً دائناً للمكتب وفق سياسة الاسترجاع.\n");
        email("حداء: استرداد " + amount + " — " + name, text.toString());
    }

    private static void line(StringBuilder b, String label, String value) {
        if (value != null && !value.isBlank()) b.append(label).append(": ").append(value).append('\n');
    }

    private void email(String subject, String text) {
        if (team == null || team.isBlank()) {
            log.warn("{} (not emailed: waslah.support.forward-to is not set)\n{}", subject, text);
            return;
        }
        mailer.send(team, subject, text);
    }
}
