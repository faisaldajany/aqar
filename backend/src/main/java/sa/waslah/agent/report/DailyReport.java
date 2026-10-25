package sa.waslah.agent.report;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import sa.waslah.agent.billing.PlanLimits;
import sa.waslah.agent.tenant.TenantContext;
import sa.waslah.agent.whatsapp.WaAccounts;
import sa.waslah.agent.whatsapp.WhatsAppClient;

/**
 * Feature 8 (تقرير يومي للمدير): at the office's chosen hour, a short summary of the day goes to the owner's
 * WhatsApp from the office's own number (approved template, since the owner has no open 24-hour window),
 * and is kept in the portal.
 */
@Component
public class DailyReport {

    private static final Logger log = LoggerFactory.getLogger(DailyReport.class);
    static final ZoneId RIYADH = ZoneId.of("Asia/Riyadh");

    public record Report(LocalDate date, String text, List<String> templateParams, Map<String, Integer> numbers) {
    }

    private final JdbcClient jdbc;
    private final PlanLimits plans;
    private final WaAccounts accounts;
    private final WhatsAppClient whatsapp;
    private final ReportProperties props;

    public DailyReport(JdbcClient jdbc, PlanLimits plans, WaAccounts accounts, WhatsAppClient whatsapp, ReportProperties props) {
        this.jdbc = jdbc;
        this.plans = plans;
        this.accounts = accounts;
        this.whatsapp = whatsapp;
        this.props = props;
    }

    @Scheduled(cron = "0 0 * * * *", zone = "Asia/Riyadh")
    public void hourly() {
        ZonedDateTime now = ZonedDateTime.now(RIYADH);
        List<UUID> offices = jdbc.sql("select distinct office_id from users").query(UUID.class).list();
        for (UUID office : offices) {
            try {
                TenantContext.run(office, () -> {
                    if (due(now.getHour(), now.toLocalDate())) send(office, now.toLocalDate());
                });
            } catch (RuntimeException e) {
                log.warn("Daily report failed for office {}: {}", office, e.getMessage());
            }
        }
    }

    /** Must run under the office's tenant. */
    boolean due(int hour, LocalDate date) {
        Boolean wanted = jdbc.sql("select report_enabled and report_hour = ? from offices").param(hour)
                .query(Boolean.class).single();
        if (!Boolean.TRUE.equals(wanted)) return false;
        return !jdbc.sql("select exists(select 1 from daily_reports where report_date = ?)")
                .param(java.sql.Date.valueOf(date)).query(Boolean.class).single();
    }

    /** Builds, stores and (if possible) sends the report for {@code date}. Must run under the office's tenant. */
    public Report send(UUID office, LocalDate date) {
        Report r = build(date);
        String phone = jdbc.sql("select owner_phone from offices").query(String.class).optional().orElse(null);
        String sentTo = null, error = null;
        Optional<WaAccounts.Account> acct = jdbc.sql("select id from wa_accounts order by connected_at desc limit 1")
                .query(UUID.class).optional().flatMap(accounts::byId);
        if (phone == null) {
            error = "no owner phone";
        } else if (props.template() == null || props.template().isBlank()) {
            error = "no approved report template";
        } else if (acct.isEmpty()) {
            error = "whatsapp not connected";
        } else {
            try {
                whatsapp.sendTemplate(accounts.credentials(acct.get()), phone, props.template(),
                        props.templateLanguage(), r.templateParams());
                sentTo = phone;
            } catch (RuntimeException e) {
                error = e.getMessage();
            }
        }
        jdbc.sql("""
                insert into daily_reports (office_id, report_date, body, sent_to, sent_at, error)
                values (?, ?, ?, ?, case when cast(? as text) is null then null else now() end, ?)
                on conflict (office_id, report_date) do update
                set body = excluded.body, sent_to = excluded.sent_to, sent_at = excluded.sent_at, error = excluded.error""")
                .params(office, java.sql.Date.valueOf(date), r.text(), sentTo, sentTo, error).update();
        return r;
    }

    /** Must run under the office's tenant. */
    public Report build(LocalDate date) {
        Timestamp from = Timestamp.from(date.atStartOfDay(RIYADH).toInstant());
        Timestamp to = Timestamp.from(date.plusDays(1).atStartOfDay(RIYADH).toInstant());
        Timestamp to2 = Timestamp.from(date.plusDays(2).atStartOfDay(RIYADH).toInstant());
        int inquiries = count("select count(*) from conversations where created_at >= ? and created_at < ?", from, to);
        int qualified = count("""
                select count(*) from leads where updated_at >= ? and updated_at < ?
                and budget_max is not null and payment is not null""", from, to);
        int booked = count("select count(*) from bookings where created_at >= ? and created_at < ?", from, to);
        int tomorrow = count("""
                select count(*) from bookings where status in ('CONFIRMED', 'PENDING_APPROVAL')
                and starts_at >= ? and starts_at < ?""", to, to2);
        int handoffs = count("select count(*) from handoffs where created_at >= ? and created_at < ?", from, to);
        int afterHours = count("""
                select count(*) from messages where direction = 'IN' and created_at >= ? and created_at < ?
                and (extract(hour from created_at at time zone 'Asia/Riyadh') >= 22
                     or extract(hour from created_at at time zone 'Asia/Riyadh') < 8)""", from, to);
        int pending = jdbc.sql("select count(*) from bookings where status = 'PENDING_APPROVAL' and starts_at > now()")
                .query(Integer.class).single();
        PlanLimits.Usage u = plans.usageThisMonth();
        String office = jdbc.sql("select name from offices").query(String.class).single();
        String day = date.toString();

        StringBuilder t = new StringBuilder();
        t.append("تقرير ").append(office).append(" — ").append(day).append('\n');
        t.append("• استفسارات جديدة: ").append(inquiries).append('\n');
        t.append("• عملاء اكتمل طلبهم (ميزانية وطريقة دفع): ").append(qualified).append('\n');
        t.append("• مواعيد حُجزت اليوم: ").append(booked).append(" — مواعيد الغد: ").append(tomorrow).append('\n');
        t.append("• حُوّلوا لمسوّق: ").append(handoffs).append('\n');
        t.append("• رسائل بعد الدوام ردّ عليها الوكيل: ").append(afterHours).append('\n');
        if (pending > 0) t.append("• زيارات ذاتية بانتظار موافقتك: ").append(pending).append('\n');
        t.append("• الردود هذا الشهر: ").append(u.replies()).append(" من ").append(u.includedReplies());
        return new Report(date, t.toString(),
                List.of(office, day, String.valueOf(inquiries), String.valueOf(qualified), String.valueOf(booked),
                        String.valueOf(tomorrow), String.valueOf(handoffs), String.valueOf(afterHours)),
                Map.of("inquiries", inquiries, "qualified", qualified, "booked", booked, "tomorrow", tomorrow,
                        "handoffs", handoffs, "afterHours", afterHours, "pendingApprovals", pending));
    }

    private int count(String sql, Timestamp a, Timestamp b) {
        return jdbc.sql(sql).params(a, b).query(Integer.class).single();
    }
}
