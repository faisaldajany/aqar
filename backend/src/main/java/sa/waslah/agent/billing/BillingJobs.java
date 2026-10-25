package sa.waslah.agent.billing;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import sa.waslah.agent.office.OfficeAlerts;
import sa.waslah.agent.tenant.TenantContext;

/** Daily: trial reminders and expiry, renewals, grace period, and on the 1st the previous month's overage invoice. */
@Component
public class BillingJobs {

    private static final Logger log = LoggerFactory.getLogger(BillingJobs.class);

    private final JdbcClient jdbc;
    private final Subscription subscription;
    private final BillingService billing;
    private final OfficeAlerts alerts;
    private final BillingProperties props;

    public BillingJobs(JdbcClient jdbc, Subscription subscription, BillingService billing, OfficeAlerts alerts,
                       BillingProperties props) {
        this.jdbc = jdbc;
        this.subscription = subscription;
        this.billing = billing;
        this.alerts = alerts;
        this.props = props;
    }

    @Scheduled(cron = "0 0 7 * * *", zone = "Asia/Riyadh")
    public void daily() {
        Instant now = Instant.now();
        List<UUID> offices = jdbc.sql("select distinct office_id from users").query(UUID.class).list();
        for (UUID office : offices) {
            try {
                TenantContext.run(office, () -> runFor(office, now));
            } catch (RuntimeException e) {
                log.warn("Billing job failed for office {}: {}", office, e.getMessage());
            }
        }
    }

    /** Must run under the office's tenant. */
    public void runFor(UUID office, Instant now) {
        Subscription.State s = subscription.state();
        switch (s.status()) {
            case "TRIAL" -> {
                if (!s.trialEndsAt().isAfter(now)) {
                    subscription.setStatus("EXPIRED");
                    alerts.raise(office, "TRIAL_ENDED", null,
                            "انتهت التجربة المجانية وتوقف الوكيل عن الرد. اشترك من صفحة الاشتراك ليعود فوراً.", "trial-ended");
                } else if (Duration.between(now, s.trialEndsAt()).toDays() < 3) {
                    alerts.raise(office, "TRIAL_ENDING", null,
                            "تنتهي التجربة المجانية خلال " + s.trialDaysLeft(now) + " أيام. اشترك الآن ليستمر الوكيل دون توقف.",
                            "trial-ending");
                }
            }
            case "ACTIVE" -> {
                if (s.paidUntil() != null && !s.paidUntil().isAfter(now)) {
                    subscription.setStatus("PAST_DUE");
                    String url = renewalUrl(office, s);
                    alerts.raise(office, "RENEWAL_DUE", null,
                            "حان تجديد الاشتراك. لديكم " + props.graceDays() + " أيام قبل توقف الوكيل."
                                    + (url == null ? "" : " ادفع من صفحة الاشتراك."),
                            "renewal-" + s.paidUntil());
                } else if (s.paidUntil() != null && Duration.between(now, s.paidUntil()).toDays() < 3) {
                    renewalUrl(office, s);
                    alerts.raise(office, "RENEWAL_SOON", null, "يتجدد الاشتراك خلال أيام. فاتورة التجديد في صفحة الاشتراك.",
                            "renewal-soon-" + s.paidUntil());
                }
            }
            case "PAST_DUE" -> {
                if (s.paidUntil() == null || s.paidUntil().plus(Duration.ofDays(props.graceDays())).isBefore(now)) {
                    subscription.setStatus("EXPIRED");
                    alerts.raise(office, "SUBSCRIPTION_EXPIRED", null,
                            "توقف الوكيل لعدم تجديد الاشتراك. ادفع فاتورة التجديد ليعود فوراً.", "expired-" + s.paidUntil());
                }
            }
            default -> {
            }
        }
        LocalDate today = LocalDate.ofInstant(now, BillingService.RIYADH);
        if (today.getDayOfMonth() == 1 && !"TRIAL".equals(s.status())) {
            billing.overage(office, today.minusMonths(1));
        }
    }

    private String renewalUrl(UUID office, Subscription.State s) {
        try {
            return billing.checkout(office, s.plan(), s.cycle()).payUrl();
        } catch (RuntimeException e) {
            log.warn("Renewal invoice for office {} not created: {}", office, e.getMessage());
            return null;
        }
    }
}
