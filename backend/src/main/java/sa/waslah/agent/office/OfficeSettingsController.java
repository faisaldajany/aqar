package sa.waslah.agent.office;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import sa.waslah.agent.billing.Subscription;
import sa.waslah.agent.common.ApiException;
import sa.waslah.agent.report.DailyReport;
import sa.waslah.agent.tenant.TenantContext;

/** Office profile, the daily report settings, and the self-serve activation checklist. */
@RestController
public class OfficeSettingsController {

    static final ZoneId RIYADH = ZoneId.of("Asia/Riyadh");

    public record Settings(String name, String falLicense, String falStatus, String falNote, String ownerPhone,
                           boolean reportEnabled, int reportHour) {
    }

    private final JdbcClient jdbc;
    private final Subscription subscription;
    private final DailyReport reports;

    public OfficeSettingsController(JdbcClient jdbc, Subscription subscription, DailyReport reports) {
        this.jdbc = jdbc;
        this.subscription = subscription;
        this.reports = reports;
    }

    @GetMapping("/api/office")
    Settings get() {
        return jdbc.sql("select name, fal_license, fal_status, fal_note, owner_phone, report_enabled, report_hour from offices")
                .query((rs, n) -> new Settings(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getBoolean(6), rs.getInt(7)))
                .single();
    }

    @PutMapping("/api/office")
    Settings put(@RequestBody Settings s) {
        if (s.name() == null || s.name().isBlank()) throw ApiException.badRequest("bad_name", "اكتب اسم المكتب.");
        if (s.reportHour() < 0 || s.reportHour() > 23) throw ApiException.badRequest("bad_hour", "ساعة غير صحيحة.");
        String phone = s.ownerPhone() == null || s.ownerPhone().isBlank() ? null : normalizePhone(s.ownerPhone());
        String fal = falDigits(s.falLicense());
        Settings before = get();
        jdbc.sql("""
                update offices set name = ?, owner_phone = ?, report_enabled = ?, report_hour = ?, fal_license = ?,
                       fal_status = case when fal_license = ? then fal_status else 'PENDING_REVIEW' end""")
                .params(s.name().trim(), phone, s.reportEnabled(), s.reportHour(), fal, fal).update();
        if (!fal.equals(before.falLicense())) {
            jdbc.sql("update offices set fal_note = null").update();
        }
        return get();
    }

    @GetMapping("/api/onboarding")
    Map<String, Object> onboarding() {
        Instant now = Instant.now();
        Subscription.State sub = subscription.state();
        List<Map<String, Object>> steps = new ArrayList<>();
        steps.add(step("whatsapp", "اربط رقم واتساب المكتب", "/whatsapp", exists("select 1 from wa_accounts")));
        steps.add(step("listings", "أضف عقاراتك (ملف Excel أو رابط عقار)", "/listings", exists("select 1 from listings")));
        steps.add(step("marketers", "أضف المسوّقين وأحياءهم", "/marketers", exists("select 1 from marketers where active")));
        steps.add(step("booking", "راجع ساعات المعاينة ورسالة التأكيد", "/booking-settings",
                exists("select 1 from booking_settings where reviewed_at is not null")));
        steps.add(step("report", "أضف جوالك ليصلك التقرير اليومي", "/settings", exists("select 1 from offices where owner_phone is not null")));
        steps.add(step("test", "جرّب: أرسل رسالة لرقم المكتب من جوال آخر", "/inbox", exists("select 1 from conversations")));
        steps.add(step("subscribe", "فعّل الاشتراك قبل نهاية التجربة", "/billing", "ACTIVE".equals(sub.status())));
        long done = steps.stream().filter(m -> Boolean.TRUE.equals(m.get("done"))).count();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("steps", steps);
        out.put("done", done);
        out.put("total", steps.size());
        out.put("subscription", sub.status());
        out.put("trialDaysLeft", sub.trialDaysLeft(now));
        out.put("serving", sub.serving(now));
        out.put("falStatus", sub.falStatus());
        return out;
    }

    @GetMapping("/api/reports")
    List<Map<String, Object>> reports() {
        return jdbc.sql("select report_date, body, sent_to, sent_at, error from daily_reports order by report_date desc limit 14")
                .query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("date", rs.getDate(1).toLocalDate().toString());
                    m.put("body", rs.getString(2));
                    m.put("sentTo", rs.getString(3));
                    m.put("sentAt", rs.getTimestamp(4) == null ? null : rs.getTimestamp(4).toInstant().toString());
                    m.put("error", rs.getString(5));
                    return m;
                }).list();
    }

    /** Today's numbers so far, without sending anything. */
    @GetMapping("/api/reports/today")
    Map<String, Object> today() {
        DailyReport.Report r = reports.build(LocalDate.now(RIYADH));
        return Map.of("date", r.date().toString(), "body", r.text(), "numbers", r.numbers());
    }

    /** Sends today's report now (to check the template reaches the owner's phone). */
    @PostMapping("/api/reports/send-now")
    Map<String, Object> sendNow() {
        DailyReport.Report r = reports.send(TenantContext.require(), LocalDate.now(RIYADH));
        return Map.of("date", r.date().toString(), "body", r.text());
    }

    private boolean exists(String sql) {
        return jdbc.sql("select exists(" + sql + ")").query(Boolean.class).single();
    }

    private static Map<String, Object> step(String key, String label, String href, boolean done) {
        return Map.of("key", key, "label", label, "href", href, "done", done);
    }

    /** Saudi mobile in WhatsApp form: 9665XXXXXXXX. Accepts 05…, 5…, +966…, 00966…. */
    public static String normalizePhone(String raw) {
        String d = raw.replaceAll("[^0-9٠-٩]", "");
        StringBuilder b = new StringBuilder();
        for (char c : d.toCharArray()) b.append(c >= '٠' && c <= '٩' ? (char) ('0' + (c - '٠')) : c);
        d = b.toString();
        if (d.startsWith("00")) d = d.substring(2);
        if (d.startsWith("05")) d = "966" + d.substring(1);
        else if (d.startsWith("5") && d.length() == 9) d = "966" + d;
        if (!d.matches("9665\\d{8}")) {
            throw ApiException.badRequest("bad_phone", "رقم الجوال غير صحيح. مثال: 0551234567");
        }
        return d;
    }

    /**
     * FAL brokerage license numbers are numeric. We check the shape only; a person on our side confirms
     * it against REGA's register (the office keeps working meanwhile, flagged as under review).
     */
    public static String falDigits(String raw) {
        String d = raw == null ? "" : raw.replaceAll("\\D", "");
        if (d.length() < 7 || d.length() > 12) {
            throw ApiException.badRequest("bad_fal", "رقم رخصة فال غير صحيح: أرقام فقط بطول 7 إلى 12 رقماً.");
        }
        return d;
    }
}
