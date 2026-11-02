package sa.waslah.agent.whatsapp;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import sa.waslah.agent.config.WaslahProperties;
import sa.waslah.agent.report.ReportProperties;
import sa.waslah.agent.tenant.TenantContext;

/**
 * The four Utility templates Hidaa sends outside WhatsApp's 24-hour window, created on each office's own WhatsApp
 * Business account when it connects (a template only works on the account it was approved on). Meta reviews them,
 * usually within minutes; until one is APPROVED the message is not sent and the office is alerted, as before.
 * Parameter order is fixed by the callers, so a changed wording needs a new name (bump the _v suffix).
 * Office-side methods run under the office's tenant.
 */
@Service
public class WaTemplates {

    private static final Logger log = LoggerFactory.getLogger(WaTemplates.class);
    static final String LANGUAGE = "ar";
    /** How stale a PENDING status may be before a send re-checks it with Meta. */
    static final Duration RECHECK = Duration.ofMinutes(10);

    public enum Kind {
        HANDOFF("hidaa_handoff_v1",
                "عميل جديد يحتاجك: {{1}}. ملخص طلبه: {{2}}. افتح المحادثة من واتساب المكتب.",
                List.of("أبو فهد", "يبحث عن فيلا في النرجس بحدود مليونين، الدفع كاش")),
        REMINDER("hidaa_reminder_v1",
                "أهلاً {{1}}، نذكّرك بموعد معاينة {{2}}، والموعد {{3}}. لتغيير الموعد رد على هذه الرسالة.",
                List.of("أبو فهد", "فيلا درج داخلي في النرجس", "الثلاثاء الساعة 5:00 م")),
        FOLLOWUP("hidaa_followup_v1",
                "أهلاً {{1}}، كيف كانت زيارتك لـ{{2}}؟ رد على هذه الرسالة ونساعدك في الخطوة الجاية.",
                List.of("أبو فهد", "فيلا درج داخلي")),
        REPORT("hidaa_daily_report_v1",
                "تقرير {{1}} ليوم {{2}} من حداء: استفسارات {{3}}، عملاء جادون {{4}}، مواعيد حُجزت {{5}}، مواعيد الغد {{6}}، "
                        + "تحويلات {{7}}، رسائل بعد الدوام {{8}}. التفاصيل في لوحة حداء.",
                List.of("مكتب الأفق", "الأحد 2 نوفمبر", "14", "5", "3", "2", "1", "6"));

        public final String templateName;
        public final String body;
        public final List<String> example;

        Kind(String templateName, String body, List<String> example) {
            this.templateName = templateName;
            this.body = body;
            this.example = example;
        }
    }

    /** What to send: a template name and its language. */
    public record Resolved(String name, String language) {
    }

    private final JdbcClient jdbc;
    private final MetaGraphClient meta;
    private final WaAccounts accounts;
    private final WaslahProperties props;
    private final ReportProperties report;

    public WaTemplates(JdbcClient jdbc, MetaGraphClient meta, WaAccounts accounts, WaslahProperties props, ReportProperties report) {
        this.jdbc = jdbc;
        this.meta = meta;
        this.accounts = accounts;
        this.props = props;
        this.report = report;
    }

    /**
     * Creates the templates this account does not have yet (or that failed before). Never throws: a failure is stored as
     * ERROR with Meta's reason and retried by the next sync. In mock mode the rows are recorded as PENDING.
     */
    public void provision(WaAccounts.Account acct) {
        for (Kind k : Kind.values()) {
            Optional<String> status = jdbc.sql("select status from wa_templates where wa_account_id = ? and kind = ?")
                    .params(acct.id(), k.name()).query(String.class).optional();
            if (status.isPresent() && !"ERROR".equals(status.get())) continue;
            if (props.whatsapp().isMock()) {
                upsert(acct, k, "PENDING", null, null);
                continue;
            }
            try {
                String token = accounts.credentials(acct).accessToken();
                MetaGraphClient.TemplateState t = meta.createTemplate(acct.wabaId(), token, k.templateName, LANGUAGE, k.body, k.example);
                upsert(acct, k, t.status(), t.id(), t.rejectedReason());
            } catch (RuntimeException e) {
                log.warn("Template {} not created for account {}: {}", k.templateName, acct.id(), e.getMessage());
                upsert(acct, k, "ERROR", null, e.getMessage());
            }
        }
    }

    /** Asks Meta for the current status of every template on this account that is not approved yet. */
    public void refresh(WaAccounts.Account acct) {
        if (props.whatsapp().isMock()) return;
        String token = accounts.credentials(acct).accessToken();
        for (Kind k : Kind.values()) {
            String status = jdbc.sql("select status from wa_templates where wa_account_id = ? and kind = ?")
                    .params(acct.id(), k.name()).query(String.class).optional().orElse(null);
            if (status == null || "ERROR".equals(status) || "APPROVED".equals(status)) continue;
            try {
                meta.findTemplate(acct.wabaId(), token, k.templateName, LANGUAGE).ifPresentOrElse(
                        t -> upsert(acct, k, t.status(), t.id(), t.rejectedReason()),
                        () -> upsert(acct, k, "ERROR", null, "not found on the WhatsApp account"));
            } catch (RuntimeException e) {
                log.warn("Template {} status not refreshed for account {}: {}", k.templateName, acct.id(), e.getMessage());
            }
        }
    }

    /** Provisions missing templates and refreshes pending ones for every connected account of the current office. */
    public void syncOffice() {
        for (UUID id : jdbc.sql("select id from wa_accounts").query(UUID.class).list()) {
            accounts.byId(id).ifPresent(a -> {
                provision(a);
                refresh(a);
            });
        }
    }

    /** Every 3 hours (Cloud Scheduler calls /internal/jobs/templates when scaled to zero). */
    @Scheduled(cron = "0 20 */3 * * *", zone = "Asia/Riyadh")
    public void syncAll() {
        for (UUID office : jdbc.sql("select distinct office_id from wa_phone_routes").query(UUID.class).list()) {
            try {
                TenantContext.run(office, this::syncOffice);
            } catch (RuntimeException e) {
                log.warn("Template sync failed for office {}: {}", office, e.getMessage());
            }
        }
    }

    /**
     * The template to send for {@code kind} from this account: its own approved one. A pending one is re-checked with
     * Meta at most every 10 minutes. Falls back to a name set in configuration (Hidaa's own number), else empty.
     */
    public Optional<Resolved> resolve(UUID waAccountId, Kind kind) {
        if (waAccountId != null) {
            record Row(String name, String language, String status, Instant checkedAt) {
            }
            Optional<Row> row = jdbc.sql("select name, language, status, checked_at from wa_templates where wa_account_id = ? and kind = ?")
                    .params(waAccountId, kind.name())
                    .query((rs, n) -> new Row(rs.getString(1), rs.getString(2), rs.getString(3),
                            rs.getTimestamp(4) == null ? null : rs.getTimestamp(4).toInstant()))
                    .optional();
            if (row.isPresent() && "PENDING".equals(row.get().status()) && !props.whatsapp().isMock()
                    && (row.get().checkedAt() == null || row.get().checkedAt().isBefore(Instant.now().minus(RECHECK)))) {
                accounts.byId(waAccountId).ifPresent(this::refresh);
                row = jdbc.sql("select name, language, status, checked_at from wa_templates where wa_account_id = ? and kind = ?")
                        .params(waAccountId, kind.name())
                        .query((rs, n) -> new Row(rs.getString(1), rs.getString(2), rs.getString(3), null))
                        .optional();
            }
            if (row.isPresent() && "APPROVED".equals(row.get().status())) {
                return Optional.of(new Resolved(row.get().name(), row.get().language()));
            }
        }
        String configured = switch (kind) {
            case HANDOFF -> props.agent().handoffTemplate();
            case REMINDER -> props.agent().reminderTemplate();
            case FOLLOWUP -> props.agent().followUpTemplate();
            case REPORT -> report.template();
        };
        if (configured == null || configured.isBlank()) return Optional.empty();
        String lang = kind == Kind.REPORT ? report.templateLanguage() : props.agent().handoffTemplateLanguage();
        return Optional.of(new Resolved(configured, lang == null || lang.isBlank() ? LANGUAGE : lang));
    }

    /** The current office's templates, for the portal and the operator console. */
    public List<Map<String, Object>> statuses() {
        return jdbc.sql("select kind, name, status, reason, updated_at from wa_templates order by kind")
                .query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("kind", rs.getString(1));
                    m.put("name", rs.getString(2));
                    m.put("status", rs.getString(3));
                    m.put("reason", rs.getString(4));
                    m.put("updatedAt", rs.getTimestamp(5).toInstant().toString());
                    return m;
                }).list();
    }

    private void upsert(WaAccounts.Account acct, Kind k, String status, String metaId, String reason) {
        jdbc.sql("""
                insert into wa_templates (office_id, wa_account_id, kind, name, language, status, meta_id, reason, checked_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, now(), now())
                on conflict (wa_account_id, kind) do update set
                    name = excluded.name, status = excluded.status, meta_id = coalesce(excluded.meta_id, wa_templates.meta_id),
                    reason = excluded.reason, checked_at = now(), updated_at = now()""")
                .params(acct.officeId(), acct.id(), k.name(), k.templateName, LANGUAGE, status == null ? "PENDING" : status,
                        metaId, reason == null ? null : reason.length() > 500 ? reason.substring(0, 500) : reason)
                .update();
    }
}
