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
                .isEqualTo(1_000_000);
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
                    insert into agent_runs (office_id, conversation_id, model, outcome, created_at)
                    select ?, ?, 'x', 'replied', ? from generate_series(1, 10005)""")
                    .params(office, conv, java.sql.Timestamp.from(mid)).update();
        });
        BillingService.Invoice inv = TenantContext.call(office, () -> billing.overage(office, lastMonth)).orElseThrow();
        assertThat(inv.subtotalHalalas()).isEqualTo(60);   // 5 replies × 12 halalas
        assertThat(inv.totalHalalas()).isEqualTo(69);
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

    private List<String> kinds() {
        return TenantContext.call(office, () -> jdbc.sql("select kind from office_alerts").query(String.class).list());
    }
}
