package sa.waslah.agent.affiliate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import sa.waslah.agent.IntegrationTest;
import sa.waslah.agent.auth.AuthService;
import sa.waslah.agent.billing.BillingService;
import sa.waslah.agent.billing.PaymentGateways;
import sa.waslah.agent.billing.PlanLimits;
import sa.waslah.agent.billing.PromoService;
import sa.waslah.agent.common.ApiException;
import sa.waslah.agent.tenant.TenantContext;

/** Marketers: their codes, clients, commissions, refunds, payouts, and their own sign-in, on a real database. */
@SuppressWarnings({"unchecked", "rawtypes"})
class AffiliateTest extends IntegrationTest {

    static final String KEY = "test-admin-token-0123456789";
    static final String IBAN = "SA0380000000608010167519";

    @Autowired AuthService auth;
    @Autowired PromoService promos;
    @Autowired BillingService billing;
    @Autowired AffiliateService affiliates;
    @Autowired AffiliateLedger ledger;
    @Autowired PartnerAuth partnerAuth;
    @Autowired JdbcClient jdbc;
    @Autowired TestRestTemplate http;

    String tag;
    UUID marketer;
    PromoService.Promo code;

    @BeforeEach
    void setUp() {
        tag = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        marketer = affiliates.create(new AffiliateService.NewAffiliate("مسوّق " + tag, tag.toLowerCase() + "@partner.sa", "0550000000",
                new BigDecimal("20"), null, null));
        code = promos.create(new PromoService.NewPromo("MK" + tag, "PERCENT", 10, null, null, null, List.of(), List.of(),
                true, null, null, null, null, marketer));
    }

    UUID officeWith(String promo) {
        String t = UUID.randomUUID().toString().substring(0, 8);
        return auth.signup(new AuthService.Signup("مكتب " + t, "1100012345", "مالك", t + "@aff.sa", "password123", promo)).officeId();
    }

    BillingService.Invoice pay(UUID office) {
        BillingService.Invoice inv = TenantContext.call(office, () -> billing.checkout(office, PlanLimits.Plan.BASIC, "MONTHLY"));
        TenantContext.call(office, () -> billing.mockPay(inv.id()));
        return TenantContext.call(office, () -> billing.find(inv.id()).orElseThrow());
    }

    Map<String, Object> page() {
        return affiliates.page(marketer, false);
    }

    static Map<String, Object> totals(Map<String, Object> page) {
        return (Map<String, Object>) page.get("totals");
    }

    static List<Map<String, Object>> list(Map<String, Object> page, String key) {
        return (List<Map<String, Object>>) page.get(key);
    }

    long rows() {
        return jdbc.sql("select count(*) from affiliate_commissions where affiliate_id = ?").param(marketer).query(Long.class).single();
    }

    @Test
    void aPaymentWithTheCodeEarnsTheMarketerTheirRate() {
        UUID office = officeWith(code.code());
        BillingService.Invoice inv = pay(office);
        assertThat(inv.subtotalHalalas()).as("499 SAR less 10%").isEqualTo(44_910);

        Map<String, Object> p = page();
        assertThat(totals(p).get("pendingHalalas")).as("20% of what the office paid before VAT").isEqualTo(8_982L);
        assertThat(totals(p).get("clients")).isEqualTo(1);
        assertThat(totals(p).get("payingClients")).isEqualTo(1);
        Map<String, Object> client = list(p, "clients").get(0);
        assertThat((String) client.get("name")).startsWith("مكتب ");
        assertThat(client.get("status")).isEqualTo("ACTIVE");
        assertThat(client.get("paidHalalas")).isEqualTo(44_910L);
        assertThat(client.get("commissionHalalas")).isEqualTo(8_982L);
        assertThat(client).as("the marketer gets business details only").doesNotContainKeys("officeId", "email", "phone");
        assertThat(list(p, "ledger")).singleElement().satisfies(e -> {
            assertThat(e.get("entry")).isEqualTo("EARNED");
            assertThat(e.get("client")).isEqualTo(client.get("name"));
            assertThat(e.get("paid")).isEqualTo(false);
        });
        assertThat(list(p, "codes")).singleElement().satisfies(c -> assertThat(c.get("code")).isEqualTo(code.code()));

        TenantContext.run(office, () -> ledger.onPaid(inv.id()));
        assertThat(rows()).as("once per invoice").isEqualTo(1);
    }

    @Test
    void switchingTheCodeOffHidesTheClientAndStopsCommission() {
        UUID office = officeWith(code.code());
        pay(office);
        promos.setActive(code.id(), false);

        Map<String, Object> p = page();
        assertThat(list(p, "clients")).isEmpty();
        assertThat(list(p, "ledger").get(0).get("client")).as("a former client has no name").isNull();
        assertThat(totals(p).get("pendingHalalas")).as("what was earned stays").isEqualTo(8_982L);
        assertThat(list(affiliates.page(marketer, true), "clients")).singleElement()
                .satisfies(c -> assertThat(c.get("visibleToMarketer")).isEqualTo(false));

        pay(office);
        assertThat(rows()).as("no commission while the code is off").isEqualTo(1);

        promos.setActive(code.id(), true);
        assertThat(list(page(), "clients")).hasSize(1);
    }

    @Test
    void anotherCodeEndsTheAttribution() {
        UUID office = officeWith(code.code());
        PromoService.Promo other = promos.create(new PromoService.NewPromo("OT" + tag, "PERCENT", 5, null, null, null, List.of(),
                List.of(), true, null, null, null, null, null));
        TenantContext.call(office, () -> promos.apply(other.code()));
        assertThat(list(affiliates.page(marketer, true), "clients")).isEmpty();
        pay(office);
        assertThat(rows()).isZero();
    }

    @Test
    void removingTheCodeEndsTheAttribution() {
        UUID office = officeWith(code.code());
        TenantContext.run(office, () -> promos.remove());
        assertThat(list(affiliates.page(marketer, true), "clients")).isEmpty();
    }

    @Test
    void aMarketersTrialCodeMakesTheOfficeTheirs() {
        promos.setActive(code.id(), false); // one active code per marketer
        PromoService.Promo trial = promos.create(new PromoService.NewPromo("TR" + tag, "TRIAL_DAYS", null, null, 16, null, List.of(),
                List.of(), true, null, null, null, null, marketer));
        UUID office = officeWith(trial.code());
        assertThat(list(page(), "clients")).singleElement().satisfies(c -> assertThat(c.get("status")).isEqualTo("TRIAL"));
        pay(office);
        assertThat(totals(page()).get("pendingHalalas")).as("20% of the full 499").isEqualTo(9_980L);
    }

    @Test
    void aMarketerHasOneActiveCodeAtATime() {
        PromoService.NewPromo second = new PromoService.NewPromo("MK2" + tag, "PERCENT", 15, null, null, null, List.of(), List.of(),
                true, null, null, null, null, marketer);
        assertThatThrownBy(() -> promos.create(second)).isInstanceOf(ApiException.class).hasMessageContaining(code.code());

        promos.setActive(code.id(), false);
        PromoService.Promo replacement = promos.create(second);
        assertThatThrownBy(() -> promos.setActive(code.id(), true)).as("the old one cannot come back while the new one is on")
                .isInstanceOf(ApiException.class).hasMessageContaining(replacement.code());

        PromoService.Promo plain = promos.create(new PromoService.NewPromo("PL" + tag, "PERCENT", 5, null, null, null, List.of(),
                List.of(), true, null, null, null, null, null));
        assertThatThrownBy(() -> promos.update(plain.id(), new PromoService.NewPromo(plain.code(), "PERCENT", 5, null, null, null,
                List.of(), List.of(), true, null, null, null, null, marketer))).as("nor by giving them another code")
                .isInstanceOf(ApiException.class);
    }

    @Test
    void anotherMarketerTakesOverTheCodesClients() {
        UUID office = officeWith(code.code());
        pay(office);
        UUID other = affiliates.create(new AffiliateService.NewAffiliate("ثاني " + tag, "o" + tag.toLowerCase() + "@partner.sa", null,
                BigDecimal.TEN, null, null));
        promos.update(code.id(), new PromoService.NewPromo(code.code(), "PERCENT", 10, null, null, null, List.of(), List.of(), true,
                null, null, null, null, other));

        assertThat(list(page(), "clients")).isEmpty();
        assertThat(totals(page()).get("pendingHalalas")).as("what the first marketer earned stays theirs").isEqualTo(8_982L);
        assertThat(list(affiliates.page(other, false), "clients")).hasSize(1);
        pay(office);
        assertThat(totals(affiliates.page(other, false)).get("pendingHalalas")).as("10% of 449.10").isEqualTo(4_491L);
    }

    @Test
    void refundsTakeTheCommissionBack() {
        UUID office = officeWith(code.code());
        BillingService.Invoice inv = pay(office);
        TenantContext.call(office, () -> billing.refunded(inv.id(), inv.totalHalalas() / 2));
        long kept = Math.round(44_910.0 * (inv.totalHalalas() - inv.totalHalalas() / 2) / inv.totalHalalas());
        assertThat(totals(page()).get("pendingHalalas")).isEqualTo(AffiliateLedger.share(kept, new BigDecimal("20")));

        TenantContext.call(office, () -> billing.refunded(inv.id(), inv.totalHalalas()));
        assertThat(totals(page()).get("pendingHalalas")).isEqualTo(0L);
        long n = rows();
        TenantContext.call(office, () -> billing.refunded(inv.id(), inv.totalHalalas()));
        assertThat(rows()).as("a repeated notification changes nothing").isEqualTo(n);
    }

    @Test
    void aPayoutSettlesWhatIsPending() {
        UUID office = officeWith(code.code());
        BillingService.Invoice inv = pay(office);
        Map<String, Object> out = affiliates.payout(marketer, "TRX-1", null);
        assertThat(out.get("amountHalalas")).isEqualTo(8_982L);

        Map<String, Object> p = page();
        assertThat(totals(p).get("pendingHalalas")).isEqualTo(0L);
        assertThat(totals(p).get("paidHalalas")).isEqualTo(8_982L);
        assertThat(list(p, "ledger").get(0).get("paid")).isEqualTo(true);
        assertThat(list(p, "payouts")).singleElement().satisfies(x -> assertThat(x.get("reference")).isEqualTo("TRX-1"));
        assertThatThrownBy(() -> affiliates.payout(marketer, null, null)).isInstanceOf(ApiException.class);

        // Refunded after it was paid out: the marketer owes it back from the next payout.
        TenantContext.call(office, () -> billing.refunded(inv.id(), inv.totalHalalas()));
        assertThat(totals(page()).get("pendingHalalas")).isEqualTo(-8_982L);
        assertThatThrownBy(() -> affiliates.payout(marketer, null, null)).isInstanceOf(ApiException.class);
    }

    @Test
    void testPaymentsEarnNothing() {
        UUID office = officeWith(code.code());
        BillingService.Invoice inv = TenantContext.call(office, () -> billing.checkout(office, PlanLimits.Plan.BASIC, "MONTHLY"));
        TenantContext.run(office, () -> jdbc.sql("update invoices set provider = ? where id = ?").params(PaymentGateways.TEST, inv.id()).update());
        assertThat(TenantContext.call(office, () -> billing.markPaidByOperator(inv.id(), null))).isTrue();
        assertThat(rows()).isZero();
    }

    @Test
    void ratesAndEmailsAreChecked() {
        assertThatThrownBy(() -> affiliates.create(new AffiliateService.NewAffiliate("س", "x" + tag + "@p.sa", null, BigDecimal.ZERO, null, null)))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> affiliates.create(new AffiliateService.NewAffiliate("س", tag.toLowerCase() + "@partner.sa", null,
                BigDecimal.TEN, null, null))).as("one marketer per email").isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> affiliates.create(new AffiliateService.NewAffiliate("س", "y" + tag + "@p.sa", null, BigDecimal.TEN,
                "SA12", null))).isInstanceOf(ApiException.class);
    }

    // ---- Sign-in ----

    static String cookie(ResponseEntity<?> res, String name) {
        return res.getHeaders().get(HttpHeaders.SET_COOKIE).stream().filter(c -> c.startsWith(name + "="))
                .map(c -> c.split(";")[0]).findFirst().orElseThrow();
    }

    ResponseEntity<Map> call(HttpMethod method, String path, Object body, String cookie, boolean opsKey) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (cookie != null) h.add(HttpHeaders.COOKIE, cookie);
        if (opsKey) h.add("X-Admin-Token", KEY);
        return http.exchange(path, method, new HttpEntity<>(body, h), Map.class);
    }

    @Test
    void marketersHaveTheirOwnSession() {
        affiliates.update(marketer, new AffiliateService.Changes(null, null, null, IBAN, "ملاحظة داخلية", null));
        assertThat(call(HttpMethod.GET, "/api/partner/me", null, null, false).getStatusCode().value()).isEqualTo(401);

        String token = partnerAuth.link(marketer, true).url().split("token=")[1];
        assertThat(call(HttpMethod.POST, "/api/partner/password", Map.of("token", token, "password", "short"), null, false)
                .getStatusCode().value()).isEqualTo(400);
        ResponseEntity<Map> set = call(HttpMethod.POST, "/api/partner/password", Map.of("token", token, "password", "partner-pass-123"), null, false);
        assertThat(set.getStatusCode().value()).isEqualTo(200);
        String session = cookie(set, PartnerAuth.COOKIE);
        ResponseEntity<Map> me = call(HttpMethod.GET, "/api/partner/me", null, session, false);
        assertThat(me.getStatusCode().value()).isEqualTo(200);
        assertThat(((Map) me.getBody().get("affiliate")).get("fullName")).isEqualTo("مسوّق " + tag);
        assertThat(((Map) me.getBody().get("affiliate"))).doesNotContainKeys("iban", "note");
        assertThat(call(HttpMethod.POST, "/api/partner/password", Map.of("token", token, "password", "another-pass-1"), null, false)
                .getStatusCode().value()).as("a link works once").isEqualTo(400);

        String email = tag.toLowerCase() + "@partner.sa";
        assertThat(call(HttpMethod.POST, "/api/partner/login", Map.of("email", email, "password", "wrong-pass-1"), null, false)
                .getStatusCode().value()).isEqualTo(401);
        ResponseEntity<Map> login = call(HttpMethod.POST, "/api/partner/login", Map.of("email", " " + email.toUpperCase() + " ",
                "password", "partner-pass-123"), null, false);
        assertThat(login.getStatusCode().value()).isEqualTo(200);

        // An office's session is not a marketer's, even under the marketer cookie's name.
        String t = UUID.randomUUID().toString().substring(0, 8);
        ResponseEntity<Map> office = http.postForEntity("/api/auth/signup", Map.of("officeName", "مكتب " + t, "falLicense", "12345678",
                "fullName", "مالك", "email", t + "@aff.sa", "password", "password123"), Map.class);
        String officeJwt = cookie(office, "waslah_session").substring("waslah_session=".length());
        assertThat(call(HttpMethod.GET, "/api/partner/me", null, PartnerAuth.COOKIE + "=" + officeJwt, false).getStatusCode().value())
                .isEqualTo(401);

        // Switching the marketer off ends their session.
        affiliates.update(marketer, new AffiliateService.Changes(null, null, null, null, null, false));
        assertThat(call(HttpMethod.GET, "/api/partner/me", null, session, false).getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void theTeamConsoleNeedsTheOperator() {
        affiliates.update(marketer, new AffiliateService.Changes(null, null, null, IBAN, "ملاحظة داخلية", null));
        assertThat(call(HttpMethod.GET, "/api/ops/affiliates", null, null, false).getStatusCode().value()).isEqualTo(404);
        ResponseEntity<List> all = http.exchange("/api/ops/affiliates", HttpMethod.GET, new HttpEntity<>(headers()), List.class);
        assertThat(all.getStatusCode().value()).isEqualTo(200);
        assertThat(all.getBody()).anyMatch(a -> marketer.toString().equals(((Map) a).get("id")));
        ResponseEntity<Map> created = call(HttpMethod.POST, "/api/ops/affiliates", Map.of("fullName", "سارة", "email", "s" + tag + "@p.sa",
                "commissionPercent", 15), null, true);
        assertThat(created.getStatusCode().value()).isEqualTo(200);
        assertThat((String) created.getBody().get("inviteUrl")).contains("/partner/set-password?token=");
        ResponseEntity<Map> page = call(HttpMethod.GET, "/api/ops/affiliates/" + marketer, null, null, true);
        assertThat(((Map) page.getBody().get("affiliate"))).containsEntry("iban", IBAN).containsKey("note");
    }

    HttpHeaders headers() {
        HttpHeaders h = new HttpHeaders();
        h.add("X-Admin-Token", KEY);
        return h;
    }
}
