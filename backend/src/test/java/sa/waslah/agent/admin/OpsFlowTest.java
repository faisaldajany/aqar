package sa.waslah.agent.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
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
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import sa.waslah.agent.billing.Subscription;
import sa.waslah.agent.common.RateLimiter;
import sa.waslah.agent.config.WaslahProperties;
import sa.waslah.agent.tenant.TenantContext;

/** The operator console over HTTP: key login, office and user controls, promo codes, and their effect on sessions. */
class OpsFlowTest extends IntegrationTest {

    static final String KEY = "test-admin-token-0123456789";

    @Autowired TestRestTemplate http;
    @Autowired Subscription subscription;
    @Autowired JdbcClient jdbc;
    @Autowired JwtEncoder jwtEncoder;
    @Autowired JwtDecoder jwtDecoder;
    @Autowired WaslahProperties props;
    @Autowired RateLimiter limits;
    @Autowired OpsReportsController reports;

    String email;
    String officeCookie;
    UUID office;
    String ops;

    @BeforeEach
    void setUp() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        email = tag + "@ops.sa";
        ResponseEntity<Map> res = http.postForEntity("/api/auth/signup", Map.of(
                "officeName", "مكتب " + tag, "falLicense", "12345678", "fullName", "مالك",
                "email", email, "password", "password123"), Map.class);
        officeCookie = cookie(res, "waslah_session");
        office = UUID.fromString(String.valueOf(res.getBody().get("officeId")));
        ResponseEntity<Map> login = http.postForEntity("/api/ops/login", Map.of("key", KEY), Map.class);
        assertThat(login.getStatusCode().value()).isEqualTo(200);
        ops = cookie(login, "hidaa_ops");
    }

    static String cookie(ResponseEntity<?> res, String name) {
        return res.getHeaders().get(HttpHeaders.SET_COOKIE).stream().filter(c -> c.startsWith(name + "="))
                .map(c -> c.split(";")[0]).findFirst().orElseThrow();
    }

    ResponseEntity<Map> call(HttpMethod method, String path, Object body, String cookie) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (cookie != null) h.add(HttpHeaders.COOKIE, cookie);
        return http.exchange(path, method, new HttpEntity<>(body, h), Map.class);
    }

    ResponseEntity<Map> opsPost(String path, Object body) {
        return call(HttpMethod.POST, path, body == null ? Map.of() : body, ops);
    }

    int login(String password) {
        return http.postForEntity("/api/auth/login", Map.of("email", email, "password", password), Map.class)
                .getStatusCode().value();
    }

    @Test
    void consoleNeedsTheKeyOrItsSession() {
        assertThat(http.postForEntity("/api/ops/login", Map.of("key", "wrong-key"), Map.class).getStatusCode().value())
                .isEqualTo(401);
        assertThat(call(HttpMethod.GET, "/api/ops/offices", null, null).getStatusCode().value()).isEqualTo(404);
        assertThat(call(HttpMethod.GET, "/api/ops/offices", null, officeCookie).getStatusCode().value())
                .as("an office session is not an operator session").isEqualTo(404);
        // An office JWT renamed to the operator cookie is still rejected: it has no operator scope.
        String forged = "hidaa_ops=" + officeCookie.substring("waslah_session=".length());
        assertThat(call(HttpMethod.GET, "/api/ops/offices", null, forged).getStatusCode().value()).isEqualTo(404);

        ResponseEntity<List> list = http.exchange("/api/ops/offices", HttpMethod.GET, new HttpEntity<>(headers(ops)), List.class);
        assertThat(list.getStatusCode().value()).isEqualTo(200);
        assertThat(list.getBody()).anyMatch(o -> office.toString().equals(((Map<?, ?>) o).get("id")));

        HttpHeaders scripted = new HttpHeaders();
        scripted.add("X-Admin-Token", KEY);
        assertThat(http.exchange("/admin/offices", HttpMethod.GET, new HttpEntity<>(scripted), List.class)
                .getStatusCode().value()).as("scripts keep working").isEqualTo(200);
    }

    @Test
    void changingTheKeyEndsOperatorSessions() {
        OpsAuth rotated = new OpsAuth("a-different-admin-key-0123456789", jwtEncoder, jwtDecoder, props, limits);
        String old = OpsAuth.COOKIE + "=" + rotated.sessionCookie().getValue();
        assertThat(call(HttpMethod.GET, "/api/ops/session", null, old).getStatusCode().value())
                .as("a session opened with another key").isEqualTo(404);
        assertThat(call(HttpMethod.GET, "/api/ops/session", null, ops).getStatusCode().value()).isEqualTo(200);
        assertThat(new OpsAuth("too-short-key-12345678", jwtEncoder, jwtDecoder, props, limits).enabled())
                .as("keys under 24 characters switch the console off").isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void revenueManualPaymentUsageAndFunnel() {
        ResponseEntity<Map> checkout = call(HttpMethod.POST, "/api/billing/checkout", Map.of("plan", "BASIC", "cycle", "MONTHLY"), officeCookie);
        String invoice = String.valueOf(checkout.getBody().get("id"));
        ResponseEntity<Map> revenue = call(HttpMethod.GET, "/api/ops/revenue", null, ops);
        assertThat(revenue.getStatusCode().value()).isEqualTo(200);
        assertThat((List<Map<String, Object>>) revenue.getBody().get("invoices"))
                .anyMatch(i -> invoice.equals(i.get("id")) && "DUE".equals(i.get("status")) && office.toString().equals(i.get("officeId")));
        assertThat((List<?>) revenue.getBody().get("months")).hasSize(12);

        assertThat(opsPost("/api/ops/invoices/" + invoice + "/paid", Map.of("officeId", office.toString())).getStatusCode().value())
                .as("a reference is required").isEqualTo(400);
        ResponseEntity<Map> paid = opsPost("/api/ops/invoices/" + invoice + "/paid", Map.of("officeId", office.toString(), "note", "تحويل بنكي 7781"));
        assertThat(paid.getBody()).containsEntry("status", "PAID").containsEntry("changed", true);
        Subscription.State s = TenantContext.call(office, () -> subscription.state());
        assertThat(s.status()).isEqualTo("ACTIVE");
        assertThat(s.plan().name()).isEqualTo("BASIC");
        String provider = TenantContext.call(office, () -> jdbc.sql("select provider from invoices where id = ?::uuid")
                .param(invoice).query(String.class).single());
        assertThat(provider).isNotNull();
        assertThat(opsPost("/api/ops/invoices/" + invoice + "/paid", Map.of("officeId", office.toString(), "note", "مرة ثانية")).getBody())
                .containsEntry("changed", false);

        ResponseEntity<Map> usage = call(HttpMethod.GET, "/api/ops/usage", null, ops);
        assertThat((List<Map<String, Object>>) usage.getBody().get("offices"))
                .anyMatch(o -> office.toString().equals(o.get("officeId")) && ((Number) o.get("revenue")).doubleValue() == 499.0);
        assertThat(call(HttpMethod.GET, "/api/ops/usage?month=bad", null, ops).getStatusCode().value()).isEqualTo(400);

        ResponseEntity<Map> funnel = call(HttpMethod.GET, "/api/ops/funnel?days=30", null, ops);
        List<Map<String, Object>> steps = (List<Map<String, Object>>) funnel.getBody().get("steps");
        assertThat(steps.get(0).get("key")).isEqualTo("SIGNED_UP");
        assertThat(((Number) steps.get(0).get("count")).intValue()).isPositive();

        assertThat(call(HttpMethod.GET, "/api/ops/revenue", null, officeCookie).getStatusCode().value())
                .as("not for offices").isEqualTo(404);
        assertThat(reports.aiCost(1_000_000, 1_000_000)).isEqualByComparingTo("1.88");
    }

    static HttpHeaders headers(String cookie) {
        HttpHeaders h = new HttpHeaders();
        h.add(HttpHeaders.COOKIE, cookie);
        return h;
    }

    @Test
    void trialFreeMonthsAndPlan() {
        Instant before = TenantContext.call(office, () -> subscription.state().trialEndsAt());
        assertThat(opsPost("/api/ops/offices/" + office + "/trial", Map.of("days", 10)).getStatusCode().value()).isEqualTo(200);
        Instant after = TenantContext.call(office, () -> subscription.state().trialEndsAt());
        assertThat(Duration.between(before, after).toDays()).isEqualTo(10);

        ResponseEntity<Map> comp = opsPost("/api/ops/offices/" + office + "/comp", Map.of("months", 2, "plan", "GROWTH", "reason", "عميل تأسيس"));
        assertThat(comp.getStatusCode().value()).isEqualTo(200);
        Subscription.State s = TenantContext.call(office, () -> subscription.state());
        assertThat(s.status()).isEqualTo("ACTIVE");
        assertThat(s.plan().name()).isEqualTo("GROWTH");
        assertThat(Duration.between(s.trialEndsAt(), s.paidUntil()).toDays()).isBetween(58L, 62L);
        String kind = TenantContext.call(office, () -> jdbc.sql("select kind from invoices").query(String.class).single());
        assertThat(kind).isEqualTo("COMP");

        assertThat(opsPost("/api/ops/offices/" + office + "/trial", Map.of("days", 5)).getStatusCode().value())
                .as("a subscribed office gets free months, not trial days").isEqualTo(409);

        ResponseEntity<Map> plan = opsPost("/api/ops/offices/" + office + "/plan", Map.of("plan", "STARTER"));
        assertThat(plan.getBody().get("plan")).isEqualTo("STARTER");

        ResponseEntity<Map> detail = call(HttpMethod.GET, "/api/ops/offices/" + office, null, ops);
        assertThat((List<?>) detail.getBody().get("users")).hasSize(1);
        assertThat((List<?>) detail.getBody().get("audit")).hasSizeGreaterThanOrEqualTo(3);
    }

    @Test
    void freeMonthsKeepAnAnnualSubscriptionAnnual() {
        TenantContext.run(office, () -> jdbc.sql("""
                update offices set subscription_status = 'ACTIVE', billing_cycle = 'ANNUAL', paid_until = now() + interval '200 days'""")
                .update());
        assertThat(opsPost("/api/ops/offices/" + office + "/comp", Map.of("months", 1)).getStatusCode().value()).isEqualTo(200);
        Subscription.State s = TenantContext.call(office, () -> subscription.state());
        assertThat(s.cycle()).isEqualTo("ANNUAL");
        assertThat(Duration.between(Instant.now(), s.paidUntil()).toDays()).isBetween(226L, 232L);
    }

    @Test
    void planChangeVoidsUnpaidInvoicesForAnotherPlan() {
        ResponseEntity<Map> checkout = call(HttpMethod.POST, "/api/billing/checkout", Map.of("plan", "BASIC", "cycle", "MONTHLY"), officeCookie);
        assertThat(checkout.getStatusCode().value()).isEqualTo(200);
        opsPost("/api/ops/offices/" + office + "/plan", Map.of("plan", "GROWTH"));
        String status = TenantContext.call(office, () -> jdbc.sql("select status from invoices where id = ?::uuid")
                .param(String.valueOf(checkout.getBody().get("id"))).query(String.class).single());
        assertThat(status).as("paying it would switch the plan back").isEqualTo("VOID");
    }

    @Test
    void suspensionStopsTheAgentSignsEveryoneOutAndBlocksSignIn() {
        assertThat(call(HttpMethod.GET, "/api/me", null, officeCookie).getStatusCode().value()).isEqualTo(200);
        opsPost("/api/ops/offices/" + office + "/suspend", Map.of("reason", "عدم سداد"));
        assertThat(TenantContext.call(office, () -> subscription.state().serving(Instant.now()))).isFalse();
        ResponseEntity<Map> me = call(HttpMethod.GET, "/api/me", null, officeCookie);
        assertThat(me.getStatusCode().value()).isEqualTo(401);
        assertThat(me.getBody().get("reason")).isEqualTo("suspended");
        assertThat(login("password123")).isEqualTo(403);

        opsPost("/api/ops/offices/" + office + "/reactivate", null);
        assertThat(login("password123")).isEqualTo(200);
        assertThat(call(HttpMethod.GET, "/api/me", null, officeCookie).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void disabledUserIsLockedOutUntilEnabled() {
        UUID user = jdbc.sql("select id from users where email = ?").param(email).query(UUID.class).single();
        opsPost("/api/ops/users/" + user + "/disable", null);
        assertThat(call(HttpMethod.GET, "/api/me", null, officeCookie).getStatusCode().value()).isEqualTo(401);
        assertThat(login("password123")).isEqualTo(403);
        assertThat(login("wrong-password")).as("no hint for a wrong password").isEqualTo(401);
        opsPost("/api/ops/users/" + user + "/enable", null);
        assertThat(login("password123")).isEqualTo(200);
    }

    @Test
    void resetLinkSetsANewPasswordOnceAndEndsOldSessions() throws Exception {
        UUID user = jdbc.sql("select id from users where email = ?").param(email).query(UUID.class).single();
        // No pause: sessions carry their exact issue time, so one from the same second as the change is told apart.
        ResponseEntity<Map> link = opsPost("/api/ops/users/" + user + "/reset-link", Map.of("sendEmail", false));
        String url = String.valueOf(link.getBody().get("url"));
        assertThat(url).contains("/reset?token=");
        String token = url.substring(url.indexOf("token=") + 6);

        assertThat(http.postForEntity("/api/auth/reset", Map.of("token", token, "password", "short"), Map.class)
                .getStatusCode().value()).isEqualTo(400);
        assertThat(http.postForEntity("/api/auth/reset", Map.of("token", token, "password", "new-password-1"), Map.class)
                .getStatusCode().value()).isEqualTo(200);
        assertThat(http.postForEntity("/api/auth/reset", Map.of("token", token, "password", "new-password-2"), Map.class)
                .getStatusCode().value()).as("one use").isEqualTo(400);

        assertThat(call(HttpMethod.GET, "/api/me", null, officeCookie).getStatusCode().value())
                .as("the session from before the change ended").isEqualTo(401);
        assertThat(login("password123")).isEqualTo(401);
        ResponseEntity<Map> fresh = http.postForEntity("/api/auth/login", Map.of("email", email, "password", "new-password-1"), Map.class);
        assertThat(fresh.getStatusCode().value()).isEqualTo(200);
        assertThat(call(HttpMethod.GET, "/api/me", null, cookie(fresh, "waslah_session")).getStatusCode().value())
                .as("a session signed in right after the change is the new one").isEqualTo(200);
    }

    @Test
    void forgotPasswordNeverRevealsWhetherAnEmailExists() {
        Map<?, ?> known = http.postForEntity("/api/auth/forgot", Map.of("email", email), Map.class).getBody();
        Map<?, ?> unknown = http.postForEntity("/api/auth/forgot", Map.of("email", "nobody-" + email), Map.class).getBody();
        assertThat(known).isEqualTo(unknown);
        long links = jdbc.sql("select count(*) from password_resets r join users u on u.id = r.user_id where u.email = ?")
                .param(email).query(Long.class).single();
        assertThat(links).isEqualTo(1);
    }

    @Test
    void promoCodesFromTheConsole() {
        String code = "OPS" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        ResponseEntity<Map> created = opsPost("/api/ops/promos", Map.of("code", code, "kind", "PERCENT", "percentOff", 20,
                "durationPeriods", 3, "plans", List.of("BASIC"), "maxRedemptions", 50, "expiresOn", "2099-12-31"));
        assertThat(created.getStatusCode().value()).isEqualTo(200);
        assertThat(String.valueOf(created.getBody().get("summary"))).contains("20٪").contains("3 دفعات").contains("الأساسي");
        assertThat(opsPost("/api/ops/promos", Map.of("code", code, "kind", "PERCENT", "percentOff", 10)).getStatusCode().value())
                .isEqualTo(409);
        assertThat(opsPost("/api/ops/promos", Map.of("code", "x", "kind", "PERCENT", "percentOff", 10)).getStatusCode().value())
                .isEqualTo(400);

        ResponseEntity<Map> applied = call(HttpMethod.POST, "/api/billing/promo", Map.of("code", code.toLowerCase()), officeCookie);
        assertThat(applied.getStatusCode().value()).isEqualTo(200);
        Map<?, ?> basic = ((List<Map<?, ?>>) applied.getBody().get("plans")).stream()
                .filter(p -> "BASIC".equals(p.get("id"))).findFirst().orElseThrow();
        assertThat(basic.get("discountedMonthlySar")).isEqualTo("399.2");

        String id = String.valueOf(created.getBody().get("id"));
        opsPost("/api/ops/promos/" + id + "/active", Map.of("active", false));
        ResponseEntity<List> list = http.exchange("/api/ops/promos", HttpMethod.GET, new HttpEntity<>(headers(ops)), List.class);
        assertThat(list.getBody()).anyMatch(p -> code.equals(((Map<?, ?>) p).get("code")) && Boolean.FALSE.equals(((Map<?, ?>) p).get("active")));
    }
}
