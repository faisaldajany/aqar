package sa.waslah.agent.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import sa.waslah.agent.IntegrationTest;
import sa.waslah.agent.auth.AuthService;
import sa.waslah.agent.common.ApiException;
import sa.waslah.agent.listing.MarketerStore;
import sa.waslah.agent.office.OfficeSettingsController;
import sa.waslah.agent.report.DailyReport;
import sa.waslah.agent.tenant.TenantContext;
import sa.waslah.agent.whatsapp.EmbeddedSignupService;
import sa.waslah.agent.whatsapp.WebhookProcessor;

/** Trial, checkout, payment confirmation, renewal and overage, and the daily report, on a real database. */
class BillingFlowTest extends IntegrationTest {

    @Autowired AuthService auth;
    @Autowired EmbeddedSignupService signup;
    @Autowired WebhookProcessor processor;
    @Autowired MarketerStore marketers;
    @Autowired BillingService billing;
    @Autowired BillingJobs jobs;
    @Autowired Subscription subscription;
    @Autowired PaymentGateway gateway;
    @Autowired DailyReport reports;
    @Autowired JdbcClient jdbc;
    @Autowired TestRestTemplate rest;

    UUID office;
    String phoneNumberId;

    @BeforeEach
    void setUp() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        office = auth.signup(new AuthService.Signup("مكتب " + tag, "1100012345", "مالك", tag + "@bill.sa", "password123")).officeId();
        phoneNumberId = "PN-" + tag;
        TenantContext.run(office, () -> signup.complete(new EmbeddedSignupService.Request(null, null, phoneNumberId, true)));
        String body = """
                {"object":"whatsapp_business_account","entry":[{"id":"W","changes":[{"field":"messages","value":{
                "messaging_product":"whatsapp","metadata":{"phone_number_id":"%s"},
                "contacts":[{"profile":{"name":"عميل"},"wa_id":"966557778888"}],
                "messages":[{"from":"966557778888","id":"wamid.%s","timestamp":"%d","type":"text","text":{"body":"السلام"}}]}}]}]}"""
                .formatted(phoneNumberId, tag, Instant.now().getEpochSecond());
        processor.process(body.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void newOfficeIsOnA14DayTrial() {
        Subscription.State s = TenantContext.call(office, () -> subscription.state());
        assertThat(s.status()).isEqualTo("TRIAL");
        assertThat(s.serving(Instant.now())).isTrue();
        assertThat(s.trialDaysLeft(Instant.now())).isEqualTo(14);
        assertThat(s.falStatus()).isEqualTo("PENDING_REVIEW");
    }

    @Test
    void checkoutThenPayActivatesAfterTheTrial() {
        BillingService.Invoice inv = TenantContext.call(office, () -> billing.checkout(office, PlanLimits.Plan.BASIC, "MONTHLY"));
        assertThat(inv.subtotalHalalas()).isEqualTo(49_900);
        assertThat(inv.vatHalalas()).isEqualTo(7_485);
        assertThat(inv.totalHalalas()).isEqualTo(57_385);
        assertThat(inv.payUrl()).contains("/billing/pay?invoice=" + inv.id());
        assertThat(TenantContext.call(office, () -> billing.checkout(office, PlanLimits.Plan.BASIC, "MONTHLY")).id())
                .as("unpaid invoice reused").isEqualTo(inv.id());

        assertThat(TenantContext.call(office, () -> billing.confirm(inv.id())).status()).as("not paid yet").isEqualTo("DUE");
        assertThat(TenantContext.call(office, () -> billing.mockPay(inv.id())).status()).isEqualTo("PAID");

        Subscription.State s = TenantContext.call(office, () -> subscription.state());
        assertThat(s.status()).isEqualTo("ACTIVE");
        assertThat(Duration.between(s.trialEndsAt(), s.paidUntil()).toDays()).isBetween(27L, 31L);
    }

    @Test
    void downgradeIsRefusedWithTooManyMarketers() {
        TenantContext.run(office, () -> {
            for (int i = 0; i < 3; i++) marketers.insert(office, "م" + i, "96655000000" + i, List.of("الملقا"));
        });
        assertThatThrownBy(() -> TenantContext.call(office, () -> billing.checkout(office, PlanLimits.Plan.BASIC, "MONTHLY")))
                .isInstanceOf(ApiException.class).hasMessageContaining("مسوّقين");
        assertThat(TenantContext.call(office, () -> billing.checkout(office, PlanLimits.Plan.GROWTH, "ANNUAL")).subtotalHalalas())
                .isEqualTo(999_000);
    }

    @Test
    void moyasarNotificationIsVerifiedWithTheProvider() {
        BillingService.Invoice inv = TenantContext.call(office, () -> billing.checkout(office, PlanLimits.Plan.GROWTH, "MONTHLY"));
        // A notification alone does nothing: the provider still says unpaid.
        rest.postForEntity("/webhooks/moyasar", Map.of("id", inv.providerRef(), "status", "paid"), Map.class);
        assertThat(TenantContext.call(office, () -> billing.find(inv.id()).orElseThrow().status())).isEqualTo("DUE");

        ((MockPaymentGateway) gateway).pay(inv.providerRef());
        rest.postForEntity("/webhooks/moyasar", Map.of("id", inv.providerRef()), Map.class);
        assertThat(TenantContext.call(office, () -> billing.find(inv.id()).orElseThrow().status())).isEqualTo("PAID");
        assertThat(TenantContext.call(office, () -> subscription.state().plan())).isEqualTo(PlanLimits.Plan.GROWTH);
    }

    @Test
    void dashboardWebhookFindsTheInvoiceInsideThePayment() {
        BillingService.Invoice inv = TenantContext.call(office, () -> billing.checkout(office, PlanLimits.Plan.GROWTH, "MONTHLY"));
        ((MockPaymentGateway) gateway).pay(inv.providerRef());
        // Shape of Moyasar's dashboard webhook: the event's own id, and the payment (with its invoice) in "data".
        var res = rest.postForEntity("/webhooks/moyasar", Map.of("id", "evt_" + inv.providerRef(), "type", "payment_paid",
                "live", false, "data", Map.of("id", "pay_123", "status", "paid", "invoice_id", inv.providerRef())), Map.class);
        assertThat(res.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(TenantContext.call(office, () -> billing.find(inv.id()).orElseThrow().status())).isEqualTo("PAID");
    }

    @Test
    void trialEndsThenAgentStops() {
        TenantContext.run(office, () -> jdbc.sql("update offices set trial_ends_at = now() + interval '2 days'").update());
        TenantContext.run(office, () -> jobs.runFor(office, Instant.now()));
        assertThat(kinds()).contains("TRIAL_ENDING");

        TenantContext.run(office, () -> jobs.runFor(office, Instant.now().plus(3, ChronoUnit.DAYS)));
        Subscription.State s = TenantContext.call(office, () -> subscription.state());
        assertThat(s.status()).isEqualTo("EXPIRED");
        assertThat(s.serving(Instant.now())).isFalse();
        assertThat(kinds()).contains("TRIAL_ENDED");
    }

    @Test
    void lapsedRenewalGetsGraceThenStops() {
        TenantContext.run(office, () -> jdbc.sql("""
                update offices set subscription_status = 'ACTIVE', paid_until = now() - interval '1 hour'""").update());
        TenantContext.run(office, () -> jobs.runFor(office, Instant.now()));
        assertThat(TenantContext.call(office, () -> subscription.state().status())).isEqualTo("PAST_DUE");
        assertThat(TenantContext.call(office, () -> billing.list())).extracting(BillingService.Invoice::kind).contains("SUBSCRIPTION");
        assertThat(TenantContext.call(office, () -> subscription.state().serving(Instant.now()))).isTrue();

        TenantContext.run(office, () -> jobs.runFor(office, Instant.now().plus(8, ChronoUnit.DAYS)));
        assertThat(TenantContext.call(office, () -> subscription.state().status())).isEqualTo("EXPIRED");
    }

    @Test
    void overageIsInvoicedOncePerMonth() {
        LocalDate lastMonth = LocalDate.now(BillingService.RIYADH).withDayOfMonth(1).minusMonths(1);
        Instant mid = lastMonth.plusDays(14).atTime(12, 0).atZone(BillingService.RIYADH).toInstant();
        TenantContext.run(office, () -> {
            UUID conv = jdbc.sql("select id from conversations").query(UUID.class).single();
            jdbc.sql("""
                    insert into agent_sessions (office_id, conversation_id, started_at)
                    select ?, ?, ? from generate_series(1, 505)""")
                    .params(office, conv, java.sql.Timestamp.from(mid)).update();
        });
        BillingService.Invoice inv = TenantContext.call(office, () -> billing.overage(office, lastMonth)).orElseThrow();
        assertThat(inv.subtotalHalalas()).isEqualTo(250);   // 5 conversations × 0.50 SAR
        assertThat(inv.totalHalalas()).isEqualTo(288);
        assertThat(TenantContext.call(office, () -> billing.overage(office, lastMonth))).isEmpty();
        assertThat(kinds()).contains("INVOICE_DUE");
    }

    @Test
    void dailyReportIsKeptEvenWithoutATemplate() {
        TenantContext.run(office, () -> jdbc.sql("update offices set owner_phone = '966551234567'").update());
        DailyReport.Report r = TenantContext.call(office, () -> reports.send(office, LocalDate.now(BillingService.RIYADH)));
        assertThat(r.numbers().get("inquiries")).isEqualTo(1);
        assertThat(r.text()).contains("استفسارات جديدة: 1");
        String error = TenantContext.call(office, () -> jdbc.sql("select error from daily_reports").query(String.class).single());
        assertThat(error).isEqualTo("no approved report template");
    }

    @Test
    void phoneAndFalFormats() {
        assertThat(OfficeSettingsController.normalizePhone("055 123 4567")).isEqualTo("966551234567");
        assertThat(OfficeSettingsController.normalizePhone("+966 55 123 4567")).isEqualTo("966551234567");
        assertThat(OfficeSettingsController.normalizePhone("٠٥٥١٢٣٤٥٦٧")).isEqualTo("966551234567");
        assertThatThrownBy(() -> OfficeSettingsController.normalizePhone("0112345678")).isInstanceOf(ApiException.class);
        assertThat(OfficeSettingsController.falDigits("1100-012345")).isEqualTo("1100012345");
        assertThatThrownBy(() -> OfficeSettingsController.falDigits("123")).isInstanceOf(ApiException.class);
    }

    @Test
    void adminConsoleIsHiddenWithoutAToken() {
        assertThat(rest.getForEntity("/admin/offices", String.class).getStatusCode().value()).isEqualTo(404);
    }

    // ---- Moyasar events beyond "paid" ----

    MockPaymentGateway mock() {
        return (MockPaymentGateway) gateway;
    }

    BillingService.Invoice invoice(UUID id) {
        return TenantContext.call(office, () -> billing.find(id).orElseThrow());
    }

    void event(String type, Map<String, Object> data) {
        rest.postForEntity("/webhooks/moyasar", Map.of("id", "evt_" + UUID.randomUUID(), "type", type, "live", false, "data", data), Map.class);
    }

    @Test
    void failedPaymentKeepsTheReasonButOnlyWhenMoyasarSaysSo() {
        BillingService.Invoice inv = TenantContext.call(office, () -> billing.checkout(office, PlanLimits.Plan.GROWTH, "MONTHLY"));
        // A made-up payment id: Moyasar does not know it, so nothing is recorded.
        event("payment_failed", Map.of("id", "pay_forged", "invoice_id", inv.providerRef(), "source", Map.of("message", "x")));
        assertThat(invoice(inv.id()).lastFailureReason()).isNull();

        mock().record(new PaymentGateway.Payment("pay_f1", "failed", inv.totalHalalas(), 0, inv.providerRef(), "INSUFFICIENT_FUNDS"));
        event("payment_failed", Map.of("id", "pay_f1", "invoice_id", inv.providerRef()));
        BillingService.Invoice after = invoice(inv.id());
        assertThat(after.lastFailureReason()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(after.lastFailureAt()).isNotNull();
        assertThat(after.status()).isEqualTo("DUE");
    }

    @Test
    void fullRefundTakesTheSubscriptionPeriodBackOnce() {
        BillingService.Invoice inv = TenantContext.call(office, () -> billing.checkout(office, PlanLimits.Plan.GROWTH, "MONTHLY"));
        mock().pay(inv.providerRef());
        TenantContext.call(office, () -> billing.confirm(inv.id()));
        Instant paidUntil = TenantContext.call(office, () -> subscription.state().paidUntil());
        Instant trialEnd = TenantContext.call(office, () -> subscription.state().trialEndsAt());

        mock().record(new PaymentGateway.Payment("pay_r1", "refunded", inv.totalHalalas(), inv.totalHalalas(), inv.providerRef(), null));
        event("payment_refunded", Map.of("id", "pay_r1", "invoice_id", inv.providerRef()));
        BillingService.Invoice after = invoice(inv.id());
        assertThat(after.status()).isEqualTo("REFUNDED");
        assertThat(after.refundedHalalas()).isEqualTo(inv.totalHalalas());
        // Paid during the trial: the month it bought is gone, back to the trial's end.
        Instant now = TenantContext.call(office, () -> subscription.state().paidUntil());
        assertThat(Duration.between(now, paidUntil).toDays()).isBetween(27L, 31L);
        assertThat(Duration.between(trialEnd, now).abs().toDays()).isLessThanOrEqualTo(3); // month lengths differ
        assertThat(kinds()).contains("REFUND");

        // Moyasar sends it again: nothing more is taken back.
        event("payment_refunded", Map.of("id", "pay_r1", "invoice_id", inv.providerRef()));
        assertThat(TenantContext.call(office, () -> subscription.state().paidUntil())).isEqualTo(now);
    }

    @Test
    void partialRefundIsRecordedAndTheSubscriptionStays() {
        BillingService.Invoice inv = TenantContext.call(office, () -> billing.checkout(office, PlanLimits.Plan.GROWTH, "MONTHLY"));
        mock().pay(inv.providerRef());
        TenantContext.call(office, () -> billing.confirm(inv.id()));
        Instant paidUntil = TenantContext.call(office, () -> subscription.state().paidUntil());

        mock().record(new PaymentGateway.Payment("pay_p1", "paid", inv.totalHalalas(), 10_000, inv.providerRef(), null));
        event("payment_refunded", Map.of("id", "pay_p1", "invoice_id", inv.providerRef()));
        BillingService.Invoice after = invoice(inv.id());
        assertThat(after.status()).isEqualTo("PAID");
        assertThat(after.refundedHalalas()).isEqualTo(10_000);
        assertThat(TenantContext.call(office, () -> subscription.state().paidUntil())).isEqualTo(paidUntil);
    }

    @Test
    void expiredPaymentPageIsReplacedWhenTheOfficePays() {
        BillingService.Invoice inv = TenantContext.call(office, () -> billing.checkout(office, PlanLimits.Plan.GROWTH, "MONTHLY"));
        // Still good: «ادفع» gives the same page.
        assertThat(TenantContext.call(office, () -> billing.payPage(inv.id())).payUrl()).isEqualTo(inv.payUrl());

        // Moyasar says it expired (only believed because Moyasar's API says so too).
        event("invoice_expired", Map.of("id", inv.providerRef(), "status", "expired"));
        assertThat(TenantContext.call(office, () -> billing.payPage(inv.id())).payUrl()).isEqualTo(inv.payUrl());
        mock().setInvoiceStatus(inv.providerRef(), "expired");
        event("invoice_expired", Map.of("id", inv.providerRef(), "status", "expired"));
        BillingService.Invoice renewed = TenantContext.call(office, () -> billing.payPage(inv.id()));
        assertThat(renewed.payUrl()).isNotEqualTo(inv.payUrl());
        assertThat(renewed.providerRef()).isNotEqualTo(inv.providerRef());

        // Paying on the new page is found from its own notification, and a new checkout does not reuse a dead page.
        mock().pay(renewed.providerRef());
        rest.postForEntity("/webhooks/moyasar", Map.of("id", renewed.providerRef()), Map.class);
        assertThat(invoice(inv.id()).status()).isEqualTo("PAID");
    }

    @Test
    void anInvoiceMadeAtAnotherVatRateIsNotReused() {
        BillingService.Invoice inv = TenantContext.call(office, () -> billing.checkout(office, PlanLimits.Plan.GROWTH, "MONTHLY"));
        assertThat(TenantContext.call(office, () -> billing.checkout(office, PlanLimits.Plan.GROWTH, "MONTHLY")).id()).isEqualTo(inv.id());
        // As if made before the rate changed (e.g. 15% before registration was dropped).
        TenantContext.run(office, () -> jdbc.sql("update invoices set vat_halalas = 0, total_halalas = subtotal_halalas where id = ?")
                .param(inv.id()).update());
        BillingService.Invoice fresh = TenantContext.call(office, () -> billing.checkout(office, PlanLimits.Plan.GROWTH, "MONTHLY"));
        assertThat(fresh.id()).isNotEqualTo(inv.id());
        assertThat(fresh.vatHalalas()).isEqualTo(7_485);
    }

    @Test
    void oldPageIsReplacedEvenWithoutANotification() {
        BillingService.Invoice inv = TenantContext.call(office, () -> billing.checkout(office, PlanLimits.Plan.GROWTH, "MONTHLY"));
        TenantContext.run(office, () -> jdbc.sql("update invoices set pay_url_expires_at = now() - interval '1 day' where id = ?")
                .param(inv.id()).update());
        assertThat(TenantContext.call(office, () -> billing.payPage(inv.id())).payUrl()).isNotEqualTo(inv.payUrl());
        // And checkout makes a fresh invoice rather than handing out the dead page.
        TenantContext.run(office, () -> jdbc.sql("update invoices set pay_url_expires_at = now() - interval '1 day' where id = ?")
                .param(inv.id()).update());
        assertThat(TenantContext.call(office, () -> billing.checkout(office, PlanLimits.Plan.GROWTH, "MONTHLY")).id()).isNotEqualTo(inv.id());
    }

    @Test
    void payoutProblemsNeedTheSecretToBeTrusted() {
        var res = rest.postForEntity("/webhooks/moyasar", Map.of("type", "payout_failed", "data", Map.of("id", "po_1", "amount", 100_00)), Map.class);
        assertThat(res.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(res.getBody().get("ok")).isEqualTo(false);
    }

    // ---- Receipts ----

    @Autowired BillingMail mail;

    <T> T waitFor(java.util.function.Supplier<T> read, java.util.function.Predicate<T> done) throws InterruptedException {
        T v = read.get();
        for (int i = 0; i < 50 && !done.test(v); i++) {
            Thread.sleep(100);
            v = read.get();
        }
        return v;
    }

    @Test
    @SuppressWarnings("unchecked")
    void receiptKeepsHowItWasPaid() throws Exception {
        BillingService.Invoice inv = TenantContext.call(office, () -> billing.checkout(office, PlanLimits.Plan.GROWTH, "MONTHLY"));
        mock().pay(inv.providerRef());
        TenantContext.call(office, () -> billing.confirm(inv.id()));
        // Asked from the provider on the mail thread, then kept on the invoice.
        Map<String, Object> r = waitFor(() -> TenantContext.call(office, () -> billing.receipt(inv.id())), m -> m.get("paidMethod") != null);
        assertThat(r.get("paidMethod")).isEqualTo("mada");
        assertThat(r.get("paidLast4")).isEqualTo("1111");
        assertThat((String) ((Map<String, Object>) r.get("office")).get("email")).endsWith("@bill.sa");
        assertThat(((Map<String, Object>) r.get("seller")).get("name")).isEqualTo("حداء");
        assertThat(BillingMail.method("mada", "1111")).isEqualTo("مدى •••• 1111");
        assertThat(BillingMail.money(22885)).isEqualTo("228.85 ريال");
    }

    @Test
    void bankTransferReceiptAndOneDueEmailPerInvoice() {
        BillingService.Invoice inv = TenantContext.call(office, () -> billing.checkout(office, PlanLimits.Plan.GROWTH, "ANNUAL"));
        TenantContext.run(office, () -> mail.due(inv));
        java.sql.Timestamp first = TenantContext.call(office, () -> jdbc.sql("select due_emailed_at from invoices where id = ?")
                .param(inv.id()).query(java.sql.Timestamp.class).single());
        assertThat(first).isNotNull();
        TenantContext.run(office, () -> mail.due(inv));
        assertThat(TenantContext.call(office, () -> jdbc.sql("select due_emailed_at from invoices where id = ?")
                .param(inv.id()).query(java.sql.Timestamp.class).single())).isEqualTo(first);

        assertThat(TenantContext.call(office, () -> billing.markPaidByOperator(inv.id(), "TRX-778899"))).isTrue();
        Map<String, Object> r = TenantContext.call(office, () -> billing.receipt(inv.id()));
        assertThat(r.get("paidMethod")).isEqualTo("MANUAL");
        assertThat(r.get("paidReference")).isEqualTo("TRX-778899");
    }

    private List<String> kinds() {
        return TenantContext.call(office, () -> jdbc.sql("select kind from office_alerts").query(String.class).list());
    }
}
