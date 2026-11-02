package sa.waslah.agent.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import sa.waslah.agent.IntegrationTest;
import sa.waslah.agent.auth.AuthService;
import sa.waslah.agent.common.ApiException;
import sa.waslah.agent.tenant.TenantContext;

/** Promo codes from creation to discounted invoices, renewals, trial extensions and limits, on a real database. */
class PromoFlowTest extends IntegrationTest {

    @Autowired AuthService auth;
    @Autowired PromoService promos;
    @Autowired BillingService billing;
    @Autowired BillingJobs jobs;
    @Autowired Subscription subscription;
    @Autowired JdbcClient jdbc;

    String tag;
    UUID office;

    @BeforeEach
    void setUp() {
        tag = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        office = newOffice();
    }

    UUID newOffice() {
        String t = UUID.randomUUID().toString().substring(0, 8);
        return auth.signup(new AuthService.Signup("مكتب " + t, "1100012345", "مالك", t + "@promo.sa", "password123")).officeId();
    }

    PromoService.Promo percent(String code, int pct, Integer periods) {
        return promos.create(new PromoService.NewPromo(code + tag, "PERCENT", pct, null, null, periods, List.of(), List.of(),
                true, null, null, null, null));
    }

    <T> T as(UUID o, java.util.concurrent.Callable<T> c) {
        return TenantContext.call(o, () -> {
            try {
                return c.call();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    @Test
    void percentOffForTheFirstTwoPaymentsThenFullPrice() {
        PromoService.Promo p = percent("LAUNCH", 30, 2);
        as(office, () -> promos.apply(" " + p.code().toLowerCase() + " "));

        BillingService.Invoice first = as(office, () -> billing.checkout(office, PlanLimits.Plan.BASIC, "MONTHLY"));
        assertThat(first.discountHalalas()).isEqualTo(14_970);
        assertThat(first.subtotalHalalas()).isEqualTo(34_930);
        assertThat(first.vatHalalas()).as("VAT on the discounted amount").isEqualTo(5_240);
        assertThat(first.totalHalalas()).isEqualTo(40_170);
        assertThat(first.promoCode()).isEqualTo(p.code());
        assertThat(first.description()).contains(p.code());
        as(office, () -> billing.mockPay(first.id()));
        assertThat(promos.find(p.code()).orElseThrow().redemptions()).isEqualTo(1);

        BillingService.Invoice second = as(office, () -> billing.checkout(office, PlanLimits.Plan.BASIC, "MONTHLY"));
        assertThat(second.discountHalalas()).isEqualTo(14_970);
        as(office, () -> billing.mockPay(second.id()));

        BillingService.Invoice third = as(office, () -> billing.checkout(office, PlanLimits.Plan.BASIC, "MONTHLY"));
        assertThat(third.discountHalalas()).isZero();
        assertThat(third.subtotalHalalas()).isEqualTo(49_900);
        assertThat(as(office, () -> promos.current().orElseThrow().usedUp())).isTrue();
        assertThat(promos.find(p.code()).orElseThrow().redemptions()).as("one office, one use").isEqualTo(1);
    }

    @Test
    void fullDiscountActivatesWithoutAPaymentAndRenewsByItself() {
        PromoService.Promo free = percent("FREE", 100, 2);
        as(office, () -> promos.apply(free.code()));
        BillingService.Invoice inv = as(office, () -> billing.checkout(office, PlanLimits.Plan.GROWTH, "MONTHLY"));
        assertThat(inv.status()).isEqualTo("PAID");
        assertThat(inv.totalHalalas()).isZero();
        assertThat(inv.payUrl()).isNull();
        Subscription.State s = as(office, () -> subscription.state());
        assertThat(s.status()).isEqualTo("ACTIVE");
        assertThat(s.plan()).isEqualTo(PlanLimits.Plan.GROWTH);

        // At the end of the paid month the second free month renews on its own: no PAST_DUE.
        Instant end = s.paidUntil().plusSeconds(3600);
        as(office, () -> {
            jobs.runFor(office, end);
            return null;
        });
        Subscription.State after = as(office, () -> subscription.state());
        assertThat(after.status()).isEqualTo("ACTIVE");
        assertThat(after.paidUntil()).isAfter(s.paidUntil());
    }

    @Test
    void fixedAmountOnlyOnItsPlanAndCycle() {
        PromoService.Promo p = promos.create(new PromoService.NewPromo("GROW" + tag, "FIXED", null, new BigDecimal("100"),
                null, null, List.of("GROWTH"), List.of("MONTHLY"), true, null, null, null, "حملة النمو"));
        assertThat(p.summary()).contains("100 ريال").contains("النمو").contains("كل الدفعات");
        as(office, () -> promos.apply(p.code()));
        assertThat(as(office, () -> billing.checkout(office, PlanLimits.Plan.BASIC, "MONTHLY")).discountHalalas()).isZero();
        assertThat(as(office, () -> billing.checkout(office, PlanLimits.Plan.GROWTH, "ANNUAL")).discountHalalas()).isZero();
        BillingService.Invoice g = as(office, () -> billing.checkout(office, PlanLimits.Plan.GROWTH, "MONTHLY"));
        assertThat(g.subtotalHalalas()).isEqualTo(89_900);
        // Only one open subscription invoice: the earlier ones were voided.
        long due = as(office, () -> jdbc.sql("select count(*) from invoices where status = 'DUE'").query(Long.class).single());
        assertThat(due).isEqualTo(1);
    }

    @Test
    void trialDaysAtSignupAndOnlyOnce() {
        PromoService.Promo p = promos.create(new PromoService.NewPromo("CITY" + tag, "TRIAL_DAYS", null, null, 16, null,
                List.of(), List.of(), true, null, null, null, null));
        String t = UUID.randomUUID().toString().substring(0, 8);
        UUID o = auth.signup(new AuthService.Signup("مكتب " + t, "1100012345", "مالك", t + "@promo.sa", "password123",
                p.code())).officeId();
        assertThat(as(o, () -> subscription.state().trialDaysLeft(Instant.now()))).isEqualTo(30);
        assertThat(promos.find(p.code()).orElseThrow().redemptions()).isEqualTo(1);
        assertThatThrownBy(() -> as(o, () -> promos.apply(p.code())))
                .isInstanceOf(ApiException.class).hasMessageContaining("من قبل");
    }

    @Test
    void wrongCodeAtSignupCreatesNoAccount() {
        String t = UUID.randomUUID().toString().substring(0, 8);
        assertThatThrownBy(() -> auth.signup(new AuthService.Signup("مكتب " + t, "1100012345", "مالك", t + "@promo.sa",
                "password123", "NOPE" + tag))).isInstanceOf(ApiException.class).hasMessageContaining("غير صحيح");
        long users = jdbc.sql("select count(*) from users where email = ?").param(t + "@promo.sa").query(Long.class).single();
        assertThat(users).isZero();
    }

    @Test
    void limitsExpiryNewCustomersAndOneDiscountAtATime() {
        PromoService.Promo once = promos.create(new PromoService.NewPromo("ONCE" + tag, "PERCENT", 10, null, null, 1,
                List.of(), List.of(), false, 1, null, null, null));
        as(office, () -> promos.apply(once.code()));
        BillingService.Invoice inv = as(office, () -> billing.checkout(office, PlanLimits.Plan.BASIC, "MONTHLY"));
        as(office, () -> billing.mockPay(inv.id()));
        UUID other = newOffice();
        assertThatThrownBy(() -> as(other, () -> promos.apply(once.code())))
                .isInstanceOf(ApiException.class).hasMessageContaining("عدد مرات");

        PromoService.Promo expired = promos.create(new PromoService.NewPromo("OLD" + tag, "PERCENT", 10, null, null, null,
                List.of(), List.of(), false, null, LocalDate.now().minusDays(10), LocalDate.now().minusDays(2), null));
        assertThatThrownBy(() -> promos.usable(expired.code())).hasMessageContaining("انتهت");

        PromoService.Promo newOnly = percent("NEW", 20, 1);
        assertThatThrownBy(() -> as(office, () -> promos.apply(newOnly.code())))
                .as("this office already paid").hasMessageContaining("الجدد");

        PromoService.Promo a = percent("AAA", 15, 3), b = percent("BBB", 25, 3);
        UUID fresh = newOffice();
        as(fresh, () -> promos.apply(a.code()));
        as(fresh, () -> promos.apply(b.code()));   // a was not used yet: replaced
        assertThat(as(fresh, () -> promos.current().orElseThrow().promo().code())).isEqualTo(b.code());
        BillingService.Invoice paid = as(fresh, () -> billing.checkout(fresh, PlanLimits.Plan.BASIC, "MONTHLY"));
        as(fresh, () -> billing.mockPay(paid.id()));
        PromoService.Promo c = promos.create(new PromoService.NewPromo("CCC" + tag, "PERCENT", 50, null, null, 1,
                List.of(), List.of(), false, null, null, null, null));
        assertThatThrownBy(() -> as(fresh, () -> promos.apply(c.code())))
                .as("b is running").hasMessageContaining("خصم فعّال");
    }

    @Test
    void aCappedCodesUseIsTakenWhenAppliedAndGivenBackWhenRemovedUnused() {
        PromoService.Promo one = promos.create(new PromoService.NewPromo("CAP" + tag, "PERCENT", 10, null, null, 1,
                List.of(), List.of(), false, 1, null, null, null));
        as(office, () -> promos.apply(one.code()));
        assertThat(promos.find(one.code()).orElseThrow().redemptions()).isEqualTo(1);
        assertThat(as(office, () -> promos.apply(one.code())).code()).as("applying the held code again is fine").isEqualTo(one.code());
        UUID other = newOffice();
        assertThatThrownBy(() -> as(other, () -> promos.apply(one.code()))).hasMessageContaining("عدد مرات");

        BillingService.Invoice due = as(office, () -> billing.checkout(office, PlanLimits.Plan.BASIC, "MONTHLY"));
        assertThat(due.discountHalalas()).isPositive();
        as(office, () -> {
            promos.remove();
            return null;
        });
        assertThat(promos.find(one.code()).orElseThrow().redemptions()).isZero();
        String status = as(office, () -> jdbc.sql("select status from invoices where id = ?").param(due.id()).query(String.class).single());
        assertThat(status).as("its unpaid invoice went with it").isEqualTo("VOID");
        as(other, () -> promos.apply(one.code()));
        assertThat(promos.find(one.code()).orElseThrow().redemptions()).isEqualTo(1);
    }

    @Test
    void parallelCheckoutsGetOneFreePeriod() throws Exception {
        PromoService.Promo free = percent("ONEFREE", 100, 1);
        as(office, () -> promos.apply(free.code()));
        Instant trialEnd = as(office, () -> subscription.state().trialEndsAt());
        List<Object> results = parallel(6, () -> as(office, () -> billing.checkout(office, PlanLimits.Plan.BASIC, "MONTHLY")));
        assertThat(results).allMatch(r -> r instanceof BillingService.Invoice);
        long free100 = as(office, () -> jdbc.sql("select count(*) from invoices where status = 'PAID' and total_halalas = 0")
                .query(Long.class).single());
        assertThat(free100).isEqualTo(1);
        Subscription.State s = as(office, () -> subscription.state());
        assertThat(s.paidUntil()).isBefore(trialEnd.plus(java.time.Duration.ofDays(32)));
        assertThat(as(office, () -> promos.current().orElseThrow().periodsUsed())).isEqualTo(1);
    }

    @Test
    void parallelTrialCodeExtendsTheTrialOnce() throws Exception {
        PromoService.Promo p = promos.create(new PromoService.NewPromo("TWICE" + tag, "TRIAL_DAYS", null, null, 16, null,
                List.of(), List.of(), true, null, null, null, null));
        List<Object> results = parallel(6, () -> as(office, () -> promos.apply(p.code())));
        assertThat(results).filteredOn(r -> r instanceof PromoService.Promo).hasSize(1);
        assertThat(as(office, () -> subscription.state().trialDaysLeft(Instant.now()))).isEqualTo(30);
        assertThat(promos.find(p.code()).orElseThrow().redemptions()).isEqualTo(1);
    }

    /** Runs {@code task} on n threads at once; each result is the value or the exception. */
    static List<Object> parallel(int n, java.util.concurrent.Callable<Object> task) throws Exception {
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(n);
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        try {
            List<java.util.concurrent.Future<Object>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    go.await();
                    try {
                        return task.call();
                    } catch (RuntimeException e) {
                        return e;
                    }
                }));
            }
            go.countDown();
            List<Object> out = new java.util.ArrayList<>();
            for (java.util.concurrent.Future<Object> f : futures) out.add(f.get(60, java.util.concurrent.TimeUnit.SECONDS));
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void operatorCanSwitchACodeOff() {
        PromoService.Promo p = percent("OFF", 10, 1);
        promos.setActive(p.id(), false);
        assertThatThrownBy(() -> promos.usable(p.code())).hasMessageContaining("غير فعّال");
        promos.setActive(p.id(), true);
        assertThat(promos.usable(p.code()).code()).isEqualTo(p.code());
    }
}
