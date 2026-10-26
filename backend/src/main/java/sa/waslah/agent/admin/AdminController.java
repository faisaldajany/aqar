package sa.waslah.agent.admin;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import sa.waslah.agent.billing.PlanLimits;
import sa.waslah.agent.common.ApiException;
import sa.waslah.agent.office.OfficeAlerts;
import sa.waslah.agent.tenant.TenantContext;

/**
 * Operator console for the Hidaa team (not offices): every office at a glance and the FAL license review.
 * Protected by a shared token header; disabled when waslah.admin-token is blank.
 */
@RestController
public class AdminController {

    public record FalDecision(String status, String note) {
    }

    private final JdbcClient jdbc;
    private final PlanLimits plans;
    private final OfficeAlerts alerts;
    private final String token;

    public AdminController(JdbcClient jdbc, PlanLimits plans, OfficeAlerts alerts, @Value("${waslah.admin-token:}") String token) {
        this.jdbc = jdbc;
        this.plans = plans;
        this.alerts = alerts;
        this.token = token;
    }

    @GetMapping("/admin/offices")
    List<Map<String, Object>> offices(@RequestHeader(value = "X-Admin-Token", required = false) String t) {
        check(t);
        List<Map<String, Object>> out = new ArrayList<>();
        for (UUID office : jdbc.sql("select distinct office_id from users").query(UUID.class).list()) {
            out.add(TenantContext.call(office, () -> {
                Map<String, Object> m = jdbc.sql("""
                                select o.name, o.fal_license, o.fal_status, o.subscription_status, o.plan, o.trial_ends_at,
                                       o.paid_until, o.created_at, (select count(*) from wa_accounts) as wa,
                                       (select count(*) from listings) as listings,
                                       (select min(email) from users u where u.office_id = o.id and u.role = 'OWNER') as owner
                                from offices o""")
                        .query((rs, n) -> {
                            Map<String, Object> r = new LinkedHashMap<>();
                            r.put("id", office);
                            r.put("name", rs.getString(1));
                            r.put("falLicense", rs.getString(2));
                            r.put("falStatus", rs.getString(3));
                            r.put("subscription", rs.getString(4));
                            r.put("plan", rs.getString(5));
                            r.put("trialEndsAt", rs.getTimestamp(6).toInstant().toString());
                            r.put("paidUntil", rs.getTimestamp(7) == null ? null : rs.getTimestamp(7).toInstant().toString());
                            r.put("createdAt", rs.getTimestamp(8).toInstant().toString());
                            r.put("whatsappConnected", rs.getInt(9) > 0);
                            r.put("listings", rs.getInt(10));
                            r.put("ownerEmail", rs.getString(11));
                            return r;
                        }).single();
                m.put("conversationsThisMonth", plans.usageThisMonth().conversations());
                return m;
            }));
        }
        out.sort((a, b) -> String.valueOf(b.get("createdAt")).compareTo(String.valueOf(a.get("createdAt"))));
        return out;
    }

    @PostMapping("/admin/offices/{id}/fal")
    Map<String, Object> fal(@RequestHeader(value = "X-Admin-Token", required = false) String t, @PathVariable UUID id,
                            @RequestBody FalDecision d) {
        check(t);
        if (!"VERIFIED".equals(d.status()) && !"REJECTED".equals(d.status())) {
            throw ApiException.badRequest("bad_status", "VERIFIED or REJECTED");
        }
        int n = TenantContext.call(id, () -> {
            int u = jdbc.sql("update offices set fal_status = ?, fal_note = ?").params(d.status(), d.note()).update();
            if (u == 1 && "REJECTED".equals(d.status())) {
                alerts.raise(id, "FAL_REJECTED", null, "لم نتمكن من التحقق من رخصة فال"
                        + (d.note() == null || d.note().isBlank() ? "" : ": " + d.note())
                        + ". صحّح الرقم من صفحة الإعدادات.", "fal-" + System.currentTimeMillis());
            }
            return u;
        });
        if (n == 0) throw ApiException.notFound("no_office", "office not found");
        return Map.of("status", d.status());
    }

    private void check(String given) {
        if (token == null || token.isBlank() || given == null
                || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), given.getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(HttpStatus.NOT_FOUND, "not_found", "Not found");
        }
    }
}
