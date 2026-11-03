package sa.waslah.agent.billing;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import sa.waslah.agent.common.Email;
import sa.waslah.agent.common.Mailer;
import sa.waslah.agent.tenant.TenantContext;

/**
 * Billing emails to the office's owners, in Hidaa's design: an invoice to pay (renewal, extra conversations), the
 * receipt when a payment goes through (with the card and Moyasar's reference, asked from Moyasar), and a refund.
 * Internal offices (the team's own, paying with test cards) get the same emails, marked as test payments. These are
 * payment receipts, not ZATCA tax invoices. Called as the office; sending happens on a background thread.
 */
@Service
public class BillingMail {

    private static final Logger log = LoggerFactory.getLogger(BillingMail.class);
    static final ZoneId RIYADH = ZoneId.of("Asia/Riyadh");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.forLanguageTag("ar-SA-u-nu-latn-ca-gregory"));
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("d MMMM yyyy، h:mm a", Locale.forLanguageTag("ar-SA-u-nu-latn-ca-gregory"));

    private final JdbcClient jdbc;
    private final Mailer mailer;
    private final BillingProperties props;
    private final PaymentGateways gateways;
    private final ExecutorService thread = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "billing-mail");
        t.setDaemon(true);
        return t;
    });

    public BillingMail(JdbcClient jdbc, Mailer mailer, BillingProperties props, PaymentGateways gateways) {
        this.jdbc = jdbc;
        this.mailer = mailer;
        this.props = props;
        this.gateways = gateways;
    }

    record Owner(String email, String fullName) {
    }

    record Office(UUID id, String name, boolean test, List<Owner> owners) {
    }

    private Office office() {
        UUID id = TenantContext.require();
        String name = jdbc.sql("select name from offices").query(String.class).optional().orElse("");
        List<Owner> owners = jdbc.sql("""
                        select email, full_name from users where office_id = ? and role = 'OWNER' and disabled_at is null
                        order by created_at""")
                .param(id).query((rs, n) -> new Owner(rs.getString(1), rs.getString(2))).list();
        return new Office(id, name, gateways.mode(id) == PaymentGateways.Mode.TEST, owners);
    }

    /** An invoice to pay, once per invoice. */
    public void due(BillingService.Invoice inv) {
        if (inv == null || !"DUE".equals(inv.status()) || inv.totalHalalas() <= 0) return;
        int claimed = jdbc.sql("update invoices set due_emailed_at = now() where id = ? and due_emailed_at is null").param(inv.id()).update();
        if (claimed == 0) return;
        Office o = office();
        List<String> text = new ArrayList<>(List.of("فاتورة جديدة لمكتب «" + o.name() + "» جاهزة للدفع."));
        if (o.test()) text.add(TEST_NOTE);
        List<Email.Line> lines = lines(inv, false);
        send(o, "فاتورة حداء " + inv.number() + ": " + money(inv.totalHalalas()), o2 -> new Email(
                "فاتورة " + inv.number() + " بمبلغ " + money(inv.totalHalalas()), null, text, null,
                new Email.Button("ادفع الفاتورة", portal("/billing")), "الدفع عبر صفحة ميسّر الآمنة (مدى، فيزا، ماستركارد، Apple Pay).", lines));
    }

    /** The receipt, after the payment is confirmed. Asks Moyasar how it was paid and keeps that on the invoice. */
    public void paid(BillingService.Invoice inv) {
        if (inv == null || inv.totalHalalas() <= 0) return;
        Office o = office();
        UUID office = o.id();
        thread.execute(() -> TenantContext.run(office, () -> {
            try {
                if (!"MANUAL".equals(inv.provider()) && inv.providerRef() != null) {
                    PaymentGateway g = gateways.byProvider(inv.provider());
                    Optional<PaymentGateway.Payment> p = g == null ? Optional.empty() : g.paidPayment(inv.providerRef());
                    p.ifPresent(x -> jdbc.sql("update invoices set paid_method = ?, paid_last4 = ?, paid_reference = ? where id = ?")
                            .params(x.method(), x.last4(), x.reference() != null ? x.reference() : x.id(), inv.id()).update());
                }
                String[] how = jdbc.sql("select paid_method, paid_last4, paid_reference, paid_at from invoices where id = ?").param(inv.id())
                        .query((rs, n) -> new String[]{rs.getString(1), rs.getString(2), rs.getString(3),
                                rs.getTimestamp(4) == null ? null : rs.getTimestamp(4).toInstant().toString()}).single();
                String paidUntil = jdbc.sql("select paid_until from offices").query(Timestamp.class).optional()
                        .map(t -> DAY.format(t.toInstant().atZone(RIYADH))).orElse(null);
                List<String> text = new ArrayList<>(List.of("وصلتنا دفعتك، شكراً لك. هذا إيصال الدفع لمكتب «" + o.name() + "»."));
                if ("SUBSCRIPTION".equals(inv.kind()) && paidUntil != null) text.add("اشتراكك فعّال حتى " + paidUntil + ".");
                if (o.test()) text.add(TEST_NOTE);
                List<Email.Line> lines = lines(inv, true);
                lines.add(new Email.Line("طريقة الدفع", method(how[0], how[1])));
                if (how[2] != null) lines.add(new Email.Line("MANUAL".equals(how[0]) ? "مرجع التحويل" : "مرجع العملية", how[2]));
                if (how[3] != null) lines.add(new Email.Line("تاريخ الدفع", TIME.format(Instant.parse(how[3]).atZone(RIYADH))));
                deliver(o, "إيصال دفع حداء " + inv.number(), new Email("إيصال الدفع " + inv.number() + ": " + money(inv.totalHalalas()), null,
                        text, null, new Email.Button("عرض الإيصال", portal("/receipt/" + inv.id())),
                        "تجد كل الإيصالات في صفحة الاشتراك في لوحة حداء.", lines));
            } catch (RuntimeException e) {
                log.warn("Receipt for invoice {} not sent: {}", inv.number(), e.getMessage());
            }
        }));
    }

    /** A refund from Moyasar: how much, and what it changed. */
    public void refunded(BillingService.Refund r) {
        BillingService.Invoice inv = r.invoice();
        Office o = office();
        List<String> text = new ArrayList<>();
        text.add(r.full() ? "استرددنا مبلغ الفاتورة " + inv.number() + " كاملاً." : "استرددنا جزءاً من مبلغ الفاتورة " + inv.number() + ".");
        if (r.periodTakenBack()) text.add("أُلغيت فترة الاشتراك التي شملتها هذه الفاتورة.");
        else if (!r.full()) text.add("اشتراكك مستمر كما هو.");
        text.add("يعود المبلغ إلى وسيلة الدفع نفسها خلال 5 إلى 14 يوم عمل حسب البنك.");
        if (o.test()) text.add(TEST_NOTE);
        List<Email.Line> lines = new ArrayList<>(List.of(
                new Email.Line("الفاتورة", inv.number()),
                new Email.Line("البيان", inv.description()),
                new Email.Line("مبلغ الفاتورة", money(inv.totalHalalas())),
                new Email.Line("المسترد", money(r.refundedHalalas()), true)));
        send(o, "استرداد من حداء: " + money(r.refundedHalalas()), o2 -> new Email("استرداد " + money(r.refundedHalalas()), null, text,
                null, new Email.Button("صفحة الاشتراك", portal("/billing")), null, lines));
    }

    // ---- Building blocks ----

    static final String TEST_NOTE = "دفع تجريبي لحساب داخلي لفريق حداء (بطاقات اختبار ميسّر): لم يُخصم أي مبلغ.";

    private List<Email.Line> lines(BillingService.Invoice inv, boolean paid) {
        List<Email.Line> l = new ArrayList<>();
        l.add(new Email.Line(paid ? "رقم الإيصال" : "رقم الفاتورة", inv.number()));
        l.add(new Email.Line("البيان", inv.description()));
        if (inv.periodStart() != null && inv.periodEnd() != null) {
            l.add(new Email.Line("الفترة", DAY.format(inv.periodStart()) + " – " + DAY.format(inv.periodEnd())));
        }
        if (inv.discountHalalas() > 0) {
            l.add(new Email.Line("الخصم" + (inv.promoCode() == null ? "" : " (" + inv.promoCode() + ")"), "− " + money(inv.discountHalalas())));
        }
        // No VAT lines while Hidaa adds no VAT (not registered yet).
        if (inv.vatHalalas() > 0) {
            l.add(new Email.Line("المبلغ قبل الضريبة", money(inv.subtotalHalalas())));
            l.add(new Email.Line("ضريبة القيمة المضافة " + Math.round(inv.vatHalalas() * 100.0 / Math.max(1, inv.subtotalHalalas())) + "٪",
                    money(inv.vatHalalas())));
        }
        l.add(new Email.Line(paid ? "المدفوع" : "الإجمالي المستحق", money(inv.totalHalalas()), true));
        return l;
    }

    static String method(String method, String last4) {
        if (method == null) return "—";
        String name = switch (method.toLowerCase(Locale.ROOT)) {
            case "mada" -> "مدى";
            case "visa" -> "فيزا";
            case "master", "mastercard" -> "ماستركارد";
            case "amex" -> "أمريكان إكسبريس";
            case "applepay" -> "Apple Pay";
            case "samsungpay" -> "Samsung Pay";
            case "stcpay" -> "STC Pay";
            case "manual" -> "تحويل بنكي";
            default -> method;
        };
        return last4 == null ? name : name + " •••• " + last4;
    }

    static String money(long halalas) {
        return String.format(Locale.ROOT, "%,.2f", halalas / 100.0) + " ريال";
    }

    private String portal(String path) {
        String base = props.portalBaseUrl() == null ? "" : props.portalBaseUrl().trim();
        return (base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + path;
    }

    private void send(Office o, String subject, java.util.function.Function<Office, Email> build) {
        Email e = build.apply(o);
        thread.execute(() -> deliver(o, subject, e));
    }

    private void deliver(Office o, String subject, Email e) {
        if (o.owners().isEmpty()) {
            log.warn("No owner to email \"{}\" for office {}", subject, o.id());
            return;
        }
        for (Owner owner : o.owners()) {
            Email personal = new Email(e.preheader(), Email.greet(owner.fullName()), e.paragraphs(), e.code(), e.button(), e.note(), e.lines());
            mailer.send(owner.email(), subject, personal);
        }
    }
}
