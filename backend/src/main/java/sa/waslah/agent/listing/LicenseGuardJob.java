package sa.waslah.agent.listing;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import sa.waslah.agent.office.OfficeAlerts;
import sa.waslah.agent.tenant.TenantContext;

/**
 * Feature 9 (حارس ترخيص الإعلان): search already hides listings whose ad license has expired.
 * Every morning this job alerts each office about licenses expiring within 7 days, and once
 * about each license that has just expired, so the office renews before customers lose the listing.
 */
@Component
public class LicenseGuardJob {

    private static final Logger log = LoggerFactory.getLogger(LicenseGuardJob.class);
    static final ZoneId RIYADH = ZoneId.of("Asia/Riyadh");
    static final int WARN_DAYS = 7;

    private final JdbcClient jdbc;
    private final OfficeAlerts alerts;

    public LicenseGuardJob(JdbcClient jdbc, OfficeAlerts alerts) {
        this.jdbc = jdbc;
        this.alerts = alerts;
    }

    @Scheduled(cron = "0 0 6 * * *", zone = "Asia/Riyadh")
    public void daily() {
        LocalDate today = LocalDate.now(RIYADH);
        // users is outside RLS: it is the list of offices; each office is then processed under its own tenant.
        List<UUID> offices = jdbc.sql("select distinct office_id from users").query(UUID.class).list();
        int raised = 0;
        for (UUID office : offices) {
            try {
                raised += TenantContext.call(office, () -> runFor(office, today));
            } catch (RuntimeException e) {
                log.warn("License check failed for office {}: {}", office, e.getMessage());
            }
        }
        log.info("License guard: {} offices checked, {} alerts raised", offices.size(), raised);
    }

    /** Must run under the office's tenant. Returns the number of new alerts. */
    public int runFor(UUID office, LocalDate today) {
        record Row(UUID id, String title, String district, String license, LocalDate expiry) {
        }
        List<Row> rows = jdbc.sql("""
                select id, title, district, ad_license, license_expiry from listings
                where status = 'ACTIVE' and license_expiry <= ?""")
                .param(java.sql.Date.valueOf(today.plusDays(WARN_DAYS)))
                .query((rs, n) -> new Row(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getDate(5).toLocalDate()))
                .list();
        int raised = 0;
        for (Row r : rows) {
            String name = r.title() + " في " + r.district() + " (ترخيص " + r.license() + ")";
            boolean isNew;
            if (r.expiry().isBefore(today)) {
                isNew = alerts.raise(office, "LICENSE_EXPIRED", r.id(),
                        "انتهى ترخيص إعلان " + name + " في " + r.expiry() + ". الوكيل لا يعرضه حتى تجدّد الترخيص وتحدّث التاريخ.",
                        "lic-expired-" + r.id() + "-" + r.expiry());
            } else {
                long days = java.time.temporal.ChronoUnit.DAYS.between(today, r.expiry());
                isNew = alerts.raise(office, "LICENSE_EXPIRING", r.id(),
                        "ترخيص إعلان " + name + " ينتهي " + (days == 0 ? "اليوم" : "خلال " + days + " يوم") + " (" + r.expiry() + ").",
                        "lic-expiring-" + r.id() + "-" + r.expiry());
            }
            if (isNew) raised++;
        }
        return raised;
    }
}
