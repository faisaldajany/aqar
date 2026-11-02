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
    void operatorCanSwitchACodeOff() {
        PromoService.Promo p = percent("OFF", 10, 1);
        promos.setActive(p.id(), false);
        assertThatThrownBy(() -> promos.usable(p.code())).hasMessageContaining("غير فعّال");
        promos.setActive(p.id(), true);
        assertThat(promos.usable(p.code()).code()).isEqualTo(p.code());
    }
}
