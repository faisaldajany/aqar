package sa.waslah.agent.admin;

import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import sa.waslah.agent.auth.PasswordResetService;
import sa.waslah.agent.auth.SessionGuard;
import sa.waslah.agent.billing.BillingService;
import sa.waslah.agent.billing.PaymentGateways;
import sa.waslah.agent.billing.PlanLimits;
import sa.waslah.agent.billing.PromoService;
import sa.waslah.agent.common.ApiException;
import sa.waslah.agent.common.ClientIp;
import sa.waslah.agent.common.Email;
import sa.waslah.agent.common.Mailer;
import sa.waslah.agent.common.RateLimiter;
import sa.waslah.agent.office.OfficeAlerts;
import sa.waslah.agent.tenant.TenantContext;
import sa.waslah.agent.whatsapp.WaTemplates;
import sa.waslah.agent.whatsapp.WhatsAppSetup;

/**
 * The operator console for the Hidaa team (portal /ops): every office and its users, subscriptions, the FAL review,
 * promo codes, support messages, and an audit log of every change made here. Offices are tenant data, so each office
 * is read and changed under its own tenant; users, promo codes and the audit log are not tenant data.
 * The old /admin/offices paths still answer to the X-Admin-Token header for scripts.
 */
@RestController
public class OpsController {

    public record Login(String key) {
    }

    public record FalDecision(String status, String note) {
    }

    public record Days(Integer days) {
    }

    public record Comp(Integer months, String plan, String reason) {
    }

    public record PlanChange(String plan) {
    }

    public record Reason(String reason) {
    }

    public record ResetLink(Boolean sendEmail) {
    }

    public record Active(Boolean active) {
    }

    private final JdbcClient jdbc;
    private final OpsAuth ops;
    private final PlanLimits plans;
    private final OfficeAlerts alerts;
    private final BillingService billing;
    private final PromoService promos;
    private final PasswordResetService resets;
    private final SessionGuard sessions;
    private final Mailer mailer;
    private final WaTemplates templates;
    private final PaymentGateways gateways;
    private final WhatsAppSetup waSetup;

    public OpsController(JdbcClient jdbc, OpsAuth ops, PlanLimits plans, OfficeAlerts alerts, BillingService billing,
                         PromoService promos, PasswordResetService resets, SessionGuard sessions, Mailer mailer,
                         WaTemplates templates, PaymentGateways gateways, WhatsAppSetup waSetup) {
        this.waSetup = waSetup;
        this.gateways = gateways;
        this.templates = templates;
        this.jdbc = jdbc;
        this.ops = ops;
        this.plans = plans;
        this.alerts = alerts;
        this.billing = billing;
        this.promos = promos;
        this.resets = resets;
        this.sessions = sessions;
        this.mailer = mailer;
    }

    // ---- Session ----

    /** 5 wrong keys per 15 minutes per address (shared with the header); a disabled console is a 404. */
    @PostMapping("/api/ops/login")
    ResponseEntity<Map<String, Object>> login(@RequestBody Login r, HttpServletRequest http) {
        if (!ops.enabled()) throw OpsAuth.notFound();
        switch (ops.check(http, r == null ? null : r.key())) {
            case LIMITED -> throw RateLimiter.tooMany();
            case WRONG -> throw new ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED, "bad_key", "مفتاح الإدارة غير صحيح.");
            case OK -> { }
        }
        audit(http, "LOGIN", null, null, null);
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, ops.sessionCookie().toString()).body(Map.of("ok", true));
    }

    @PostMapping("/api/ops/logout")
    ResponseEntity<Void> logout() {
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, ops.clearCookie().toString()).build();
    }

    @GetMapping("/api/ops/session")
    Map<String, Object> session(HttpServletRequest http) {
        ops.require(http);
        return Map.of("ok", true, "mailConfigured", mailer.configured());
    }

    // ---- Offices ----

    @GetMapping("/api/ops/overview")
    Map<String, Object> overview(HttpServletRequest http) {
        ops.require(http);
        List<Map<String, Object>> all = allOffices();
        Map<String, Integer> byStatus = new LinkedHashMap<>();
        for (String s : List.of("TRIAL", "ACTIVE", "PAST_DUE", "EXPIRED", "CANCELLED")) byStatus.put(s, 0);
        int suspended = 0, falPending = 0, whatsapp = 0, conversations = 0;
        BigDecimal mrr = BigDecimal.ZERO;
        for (Map<String, Object> o : all) {
            byStatus.merge((String) o.get("subscription"), 1, Integer::sum);
            if (Boolean.TRUE.equals(o.get("suspended"))) suspended++;
            if ("PENDING_REVIEW".equals(o.get("falStatus"))) falPending++;
            if (Boolean.TRUE.equals(o.get("whatsappConnected"))) whatsapp++;
            conversations += (Integer) o.get("conversationsThisMonth");
            if (("ACTIVE".equals(o.get("subscription")) || "PAST_DUE".equals(o.get("subscription")))
                    && !Boolean.TRUE.equals(o.get("suspended")) && !"TEST".equals(o.get("paymentMode"))) {
                PlanLimits.Plan p = PlanLimits.Plan.valueOf((String) o.get("plan"));
                mrr = mrr.add("ANNUAL".equals(o.get("cycle"))
                        ? p.annualSar.divide(BigDecimal.valueOf(12), 0, RoundingMode.HALF_UP) : p.monthlySar);
            }
        }
        int unforwarded = jdbc.sql("select count(*) from support_messages where forwarded_at is null and created_at > now() - interval '30 days'")
                .query(Integer.class).single();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("offices", all.size());
        out.put("byStatus", byStatus);
        out.put("suspended", suspended);
        out.put("falPending", falPending);
        out.put("whatsappConnected", whatsapp);
        out.put("conversationsThisMonth", conversations);
        out.put("mrrSar", mrr);
        out.put("supportUnforwarded", unforwarded);
        out.put("mailConfigured", mailer.configured());
        return out;
    }

    @GetMapping({"/api/ops/offices", "/admin/offices"})
    List<Map<String, Object>> offices(HttpServletRequest http) {
        ops.require(http);
        return allOffices();
    }

    @GetMapping("/api/ops/offices/{id}")
    Map<String, Object> office(HttpServletRequest http, @PathVariable UUID id) {
        ops.require(http);
        Map<String, Object> o = summary(id).orElseThrow(() -> ApiException.notFound("no_office", "المكتب غير موجود."));
        o.put("users", jdbc.sql("""
                        select id, full_name, email, role, created_at, last_login_at, disabled_at
                        from users where office_id = ? order by created_at""")
                .param(id).query((rs, n) -> {
                    Map<String, Object> u = new LinkedHashMap<>();
                    u.put("id", rs.getObject(1, UUID.class));
                    u.put("fullName", rs.getString(2));
                    u.put("email", rs.getString(3));
                    u.put("role", rs.getString(4));
                    u.put("createdAt", iso(rs.getTimestamp(5)));
                    u.put("lastLoginAt", iso(rs.getTimestamp(6)));
                    u.put("disabled", rs.getTimestamp(7) != null);
                    return u;
                }).list());
        TenantContext.run(id, () -> {
            o.put("invoices", billing.list().stream().limit(20).toList());
            o.put("promo", promos.current().map(a -> Map.of("code", a.promo().code(), "summary", a.promo().summary(),
                    "periodsUsed", a.periodsUsed(), "usedUp", a.usedUp())).orElse(null));
            PlanLimits.Usage u = plans.usageThisMonth();
            o.put("usage", Map.of("conversations", u.conversations(), "included", u.included(), "overage", u.overage()));
            o.put("templates", templates.statuses());
            // Where the office is in the WhatsApp setup, for support calls ("I'm stuck at…").
            o.put("waSetup", waSetup.state());
        });
        o.put("audit", auditRows(id, 50));
        return o;
    }

    @PostMapping({"/api/ops/offices/{id}/fal", "/admin/offices/{id}/fal"})
    Map<String, Object> fal(HttpServletRequest http, @PathVariable UUID id, @RequestBody FalDecision d) {
        ops.require(http);
        if (d == null || (!"VERIFIED".equals(d.status()) && !"REJECTED".equals(d.status()))) {
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
        if (n == 0) throw ApiException.notFound("no_office", "المكتب غير موجود.");
        audit(http, "FAL_" + d.status(), id, null, d.note());
        return Map.of("status", d.status());
    }

    /** Adds days to a trial (also re-opens an expired trial that never subscribed). */
    @PostMapping("/api/ops/offices/{id}/trial")
    Map<String, Object> extendTrial(HttpServletRequest http, @PathVariable UUID id, @RequestBody Days d) {
        ops.require(http);
        int days = d == null || d.days() == null ? 0 : d.days();
        if (days < 1 || days > 180) throw ApiException.badRequest("bad_days", "عدد الأيام من 1 إلى 180.");
        requireOffice(id);
        TenantContext.run(id, () -> {
            String status = jdbc.sql("select subscription_status from offices").query(String.class).single();
            boolean paid = jdbc.sql("select exists(select 1 from invoices where kind = 'SUBSCRIPTION' and status = 'PAID')")
                    .query(Boolean.class).single();
            if (paid || !("TRIAL".equals(status) || "EXPIRED".equals(status))) {
                throw ApiException.conflict("not_trial", "المكتب مشترك. استخدم الأشهر المجانية بدلاً من تمديد التجربة.");
            }
            jdbc.sql("""
                    update offices set subscription_status = 'TRIAL',
                           trial_ends_at = greatest(trial_ends_at, now()) + make_interval(days => ?)""").param(days).update();
            alerts.raise(id, "TRIAL_EXTENDED", null, "مدّد فريق حداء تجربتكم المجانية " + days + " يوماً.",
                    "trial-extended-" + System.currentTimeMillis());
        });
        audit(http, "TRIAL_EXTENDED", id, null, days + " days");
        return summary(id).orElseThrow();
    }

    /** Free months on a plan, recorded as a zero invoice. */
    @PostMapping("/api/ops/offices/{id}/comp")
    Map<String, Object> comp(HttpServletRequest http, @PathVariable UUID id, @RequestBody Comp c) {
        ops.require(http);
        requireOffice(id);
        int months = c == null || c.months() == null ? 0 : c.months();
        String reason = c == null || c.reason() == null || c.reason().isBlank() ? null : c.reason().trim();
        PlanLimits.Plan plan = TenantContext.call(id, () -> c == null || c.plan() == null || c.plan().isBlank()
                ? plans.plan() : parsePlan(c.plan()));
        TenantContext.run(id, () -> {
            billing.comp(id, plan, months, reason);
            alerts.raise(id, "COMP_MONTHS", null, "أضاف فريق حداء " + months + (months == 1 ? " شهراً مجانياً" : " أشهر مجانية")
                    + " على باقة " + plan.arabicName + ".", "comp-" + System.currentTimeMillis());
        });
        audit(http, "COMP_MONTHS", id, plan.name(), months + " months" + (reason == null ? "" : ": " + reason));
        return summary(id).orElseThrow();
    }

    @PostMapping("/api/ops/offices/{id}/plan")
    Map<String, Object> changePlan(HttpServletRequest http, @PathVariable UUID id, @RequestBody PlanChange p) {
        ops.require(http);
        requireOffice(id);
        PlanLimits.Plan plan = parsePlan(p == null ? null : p.plan());
        String warning = TenantContext.call(id, () -> {
            jdbc.sql("update offices set plan = ?").param(plan.name()).update();
            // An unpaid invoice for another plan would switch the plan back when paid.
            jdbc.sql("update invoices set status = 'VOID' where kind = 'SUBSCRIPTION' and status = 'DUE' and plan <> ?")
                    .param(plan.name()).update();
            int active = jdbc.sql("select count(*) from marketers where active").query(Integer.class).single();
            return active > plan.marketers
                    ? "لدى المكتب " + active + " مسوّقين نشطين وباقة " + plan.arabicName + " تسمح بـ " + plan.marketers + "."
                    : null;
        });
        audit(http, "PLAN_CHANGED", id, plan.name(), warning);
        Map<String, Object> out = summary(id).orElseThrow();
        out.put("warning", warning);
        return out;
    }

    /** The agent stops and every user of the office is signed out and cannot sign in until reactivated. */
    @PostMapping("/api/ops/offices/{id}/suspend")
    Map<String, Object> suspend(HttpServletRequest http, @PathVariable UUID id, @RequestBody(required = false) Reason r) {
        ops.require(http);
        requireOffice(id);
        String reason = r == null || r.reason() == null || r.reason().isBlank() ? null : r.reason().trim();
        TenantContext.run(id, () -> jdbc.sql("update offices set suspended_at = now(), suspended_reason = ?").param(reason).update());
        sessions.evictAll();
        audit(http, "SUSPENDED", id, null, reason);
        return summary(id).orElseThrow();
    }

    @PostMapping("/api/ops/offices/{id}/reactivate")
    Map<String, Object> reactivate(HttpServletRequest http, @PathVariable UUID id) {
        ops.require(http);
        requireOffice(id);
        TenantContext.run(id, () -> jdbc.sql("update offices set suspended_at = null, suspended_reason = null").update());
        sessions.evictAll();
        audit(http, "REACTIVATED", id, null, null);
        return summary(id).orElseThrow();
    }

    /** Creates missing WhatsApp templates on the office's account and refreshes Meta's review. */
    @PostMapping("/api/ops/offices/{id}/templates/sync")
    Map<String, Object> syncTemplates(HttpServletRequest http, @PathVariable UUID id) {
        ops.require(http);
        requireOffice(id);
        List<Map<String, Object>> rows = TenantContext.call(id, () -> {
            templates.syncOffice();
            return templates.statuses();
        });
        audit(http, "TEMPLATES_SYNC", id, null, null);
        return Map.of("templates", rows);
    }

    // ---- Users ----

    @PostMapping("/api/ops/users/{id}/disable")
    Map<String, Object> disableUser(HttpServletRequest http, @PathVariable UUID id) {
        return setDisabled(http, id, true);
    }

    @PostMapping("/api/ops/users/{id}/enable")
    Map<String, Object> enableUser(HttpServletRequest http, @PathVariable UUID id) {
        return setDisabled(http, id, false);
    }

    private Map<String, Object> setDisabled(HttpServletRequest http, UUID id, boolean disabled) {
        ops.require(http);
        UUID office = userOffice(id);
        jdbc.sql("update users set disabled_at = " + (disabled ? "now()" : "null") + " where id = ?").param(id).update();
        sessions.evictUser(id);
        audit(http, disabled ? "USER_DISABLED" : "USER_ENABLED", office, id.toString(), null);
        return Map.of("id", id, "disabled", disabled);
    }

    /** A 24-hour reset link to send the user (WhatsApp, or email when mail is set up). */
    @PostMapping("/api/ops/users/{id}/reset-link")
    Map<String, Object> resetLink(HttpServletRequest http, @PathVariable UUID id, @RequestBody(required = false) ResetLink r) {
        ops.require(http);
        UUID office = userOffice(id);
        PasswordResetService.Link link = resets.create(id, true);
        boolean emailed = false;
        if (r != null && Boolean.TRUE.equals(r.sendEmail())) {
            record Who(String email, String fullName) {
            }
            Who who = jdbc.sql("select email, full_name from users where id = ?").param(id)
                    .query((rs, n) -> new Who(rs.getString(1), rs.getString(2))).single();
            emailed = mailer.send(who.email(), PasswordResetService.SUBJECT, new Email(
                    "رابط من فريق حداء لتعيين كلمة مرور جديدة، صالح لمدة 24 ساعة.",
                    Email.greet(who.fullName()),
                    List.of("أرسل لك فريق حداء رابطاً لتعيين كلمة مرور جديدة لحسابك. الرابط صالح لمدة 24 ساعة ويُستخدم مرة واحدة."),
                    new Email.Button("تعيين كلمة مرور جديدة", link.url()),
                    "إذا لم تطلب ذلك من فريق حداء فتواصل معنا على support@hidaaco.com."));
        }
        audit(http, "RESET_LINK", office, id.toString(), emailed ? "emailed" : "shown");
        return Map.of("url", link.url(), "expiresAt", link.expiresAt().toString(), "emailed", emailed);
    }

    // ---- Promo codes ----

    @GetMapping("/api/ops/promos")
    List<Map<String, Object>> promoList(HttpServletRequest http) {
        ops.require(http);
        return promos.list().stream().map(OpsController::promoView).toList();
    }

    @PostMapping("/api/ops/promos")
    Map<String, Object> promoCreate(HttpServletRequest http, @RequestBody PromoService.NewPromo n) {
        ops.require(http);
        if (n == null) throw ApiException.badRequest("bad_promo", "بيانات الكود ناقصة.");
        PromoService.Promo p = promos.create(n);
        audit(http, "PROMO_CREATED", null, p.code(), p.summary());
        return promoView(p);
    }

    @PutMapping("/api/ops/promos/{id}")
    Map<String, Object> promoUpdate(HttpServletRequest http, @PathVariable UUID id, @RequestBody PromoService.NewPromo n) {
        ops.require(http);
        PromoService.Promo p = promos.update(id, n);
        audit(http, "PROMO_UPDATED", null, p.code(), p.summary());
        return promoView(p);
    }

    @PostMapping("/api/ops/promos/{id}/active")
    Map<String, Object> promoActive(HttpServletRequest http, @PathVariable UUID id, @RequestBody Active a) {
        ops.require(http);
        boolean on = a != null && Boolean.TRUE.equals(a.active());
        promos.setActive(id, on);
        audit(http, on ? "PROMO_ON" : "PROMO_OFF", null, id.toString(), null);
        return Map.of("id", id, "active", on);
    }

    static final java.time.ZoneId RIYADH_ZONE = java.time.ZoneId.of("Asia/Riyadh");

    static Map<String, Object> promoView(PromoService.Promo p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.id());
        m.put("code", p.code());
        m.put("kind", p.kind());
        m.put("summary", p.summary());
        m.put("redemptions", p.redemptions());
        m.put("maxRedemptions", p.maxRedemptions());
        m.put("newCustomersOnly", p.newCustomersOnly());
        m.put("startsAt", p.startsAt() == null ? null : p.startsAt().toString());
        m.put("expiresAt", p.expiresAt() == null ? null : p.expiresAt().toString());
        m.put("active", p.active());
        m.put("note", p.note());
        m.put("createdAt", p.createdAt().toString());
        m.put("affiliateId", p.affiliateId());
        // What the edit form needs, and the dates as the team typed them (the last day is inclusive).
        m.put("percentOff", p.percentOff());
        m.put("amountOffHalalas", p.amountOffHalalas());
        m.put("trialDays", p.trialDays());
        m.put("durationPeriods", p.durationPeriods());
        m.put("plans", p.plans());
        m.put("cycles", p.cycles());
        m.put("startsOn", p.startsAt() == null ? null : p.startsAt().atZone(RIYADH_ZONE).toLocalDate().toString());
        m.put("expiresOn", p.expiresAt() == null ? null : p.expiresAt().atZone(RIYADH_ZONE).toLocalDate().minusDays(1).toString());
        return m;
    }

    // ---- Support and audit ----

    @GetMapping("/api/ops/support")
    List<Map<String, Object>> support(HttpServletRequest http) {
        ops.require(http);
        return jdbc.sql("""
                        select id, created_at, name, email, phone, topic, message, source, forwarded_at, forward_error
                        from support_messages order by created_at desc limit 200""")
                .query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getObject(1, UUID.class));
                    m.put("createdAt", iso(rs.getTimestamp(2)));
                    m.put("name", rs.getString(3));
                    m.put("email", rs.getString(4));
                    m.put("phone", rs.getString(5));
                    m.put("topic", rs.getString(6));
                    m.put("message", rs.getString(7));
                    m.put("source", rs.getString(8));
                    m.put("forwarded", rs.getTimestamp(9) != null);
                    m.put("forwardError", rs.getString(10));
                    return m;
                }).list();
    }

    @GetMapping("/api/ops/audit")
    List<Map<String, Object>> audit(HttpServletRequest http) {
        ops.require(http);
        return auditRows(null, 200);
    }

    // ---- Helpers ----

    private List<Map<String, Object>> allOffices() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (UUID office : jdbc.sql("select distinct office_id from users").query(UUID.class).list()) {
            summary(office).ifPresent(out::add);
        }
        out.sort((a, b) -> String.valueOf(b.get("createdAt")).compareTo(String.valueOf(a.get("createdAt"))));
        return out;
    }

    private Optional<Map<String, Object>> summary(UUID office) {
        Map<String, Object> users = jdbc.sql("""
                        select count(*), max(last_login_at),
                               (select min(email) from users u2 where u2.office_id = ? and u2.role = 'OWNER')
                        from users where office_id = ?""")
                .params(office, office).query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("users", rs.getInt(1));
                    m.put("lastLoginAt", iso(rs.getTimestamp(2)));
                    m.put("ownerEmail", rs.getString(3));
                    return m;
                }).single();
        return TenantContext.call(office, () -> jdbc.sql("""
                        select o.name, o.fal_license, o.fal_status, o.fal_note, o.subscription_status, o.plan, o.billing_cycle,
                               o.trial_ends_at, o.paid_until, o.created_at, o.suspended_at, o.suspended_reason, o.owner_phone,
                               (select count(*) from wa_accounts) as wa, (select count(*) from listings) as listings,
                               (select code from promo_codes p where p.id = o.promo_code_id) as promo
                        from offices o""")
                .query((rs, n) -> {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("id", office);
                    r.put("name", rs.getString(1));
                    r.put("falLicense", rs.getString(2));
                    r.put("falStatus", rs.getString(3));
                    r.put("falNote", rs.getString(4));
                    r.put("subscription", rs.getString(5));
                    r.put("plan", rs.getString(6));
                    r.put("cycle", rs.getString(7));
                    r.put("trialEndsAt", iso(rs.getTimestamp(8)));
                    r.put("paidUntil", iso(rs.getTimestamp(9)));
                    r.put("createdAt", iso(rs.getTimestamp(10)));
                    r.put("suspended", rs.getTimestamp(11) != null);
                    r.put("suspendedReason", rs.getString(12));
                    r.put("ownerPhone", rs.getString(13));
                    r.put("whatsappConnected", rs.getInt(14) > 0);
                    r.put("listings", rs.getInt(15));
                    r.put("promoCode", rs.getString(16));
                    r.putAll(users);
                    r.put("paymentMode", gateways.mode(office).name());
                    return r;
                })
                .optional()
                .map(r -> {
                    r.put("conversationsThisMonth", plans.usageThisMonth().conversations());
                    return r;
                }));
    }

    private void requireOffice(UUID id) {
        boolean exists = TenantContext.call(id, () -> jdbc.sql("select exists(select 1 from offices)").query(Boolean.class).single());
        if (!exists) throw ApiException.notFound("no_office", "المكتب غير موجود.");
    }

    private UUID userOffice(UUID userId) {
        return jdbc.sql("select office_id from users where id = ?").param(userId).query(UUID.class).optional()
                .orElseThrow(() -> ApiException.notFound("no_user", "المستخدم غير موجود."));
    }

    private static PlanLimits.Plan parsePlan(String p) {
        try {
            return PlanLimits.Plan.valueOf(p == null ? "" : p);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("bad_plan", "باقة غير معروفة.");
        }
    }

    private void audit(HttpServletRequest http, String action, UUID office, String target, String detail) {
        jdbc.sql("insert into ops_audit (action, office_id, target, detail, ip_hash) values (?, ?, ?, ?, ?)")
                .params(action, office, target, detail == null ? null : detail.length() > 500 ? detail.substring(0, 500) : detail,
                        ClientIp.hash(http, "ops"))
                .update();
    }

    private List<Map<String, Object>> auditRows(UUID office, int limit) {
        return jdbc.sql("select at, action, office_id, target, detail from ops_audit"
                        + (office == null ? "" : " where office_id = :office") + " order by at desc limit :lim")
                .params(office == null ? Map.<String, Object>of("lim", limit) : Map.<String, Object>of("office", office, "lim", limit))
                .query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("at", iso(rs.getTimestamp(1)));
                    m.put("action", rs.getString(2));
                    m.put("officeId", rs.getObject(3, UUID.class));
                    m.put("target", rs.getString(4));
                    m.put("detail", rs.getString(5));
                    return m;
                }).list();
    }

    private static String iso(Timestamp t) {
        return t == null ? null : t.toInstant().toString();
    }
}
