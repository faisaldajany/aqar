package sa.waslah.agent.ops;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import sa.waslah.agent.billing.BillingJobs;
import sa.waslah.agent.booking.BookingJobs;
import sa.waslah.agent.common.ApiException;
import sa.waslah.agent.listing.LicenseGuardJob;
import sa.waslah.agent.privacy.PrivacyService;
import sa.waslah.agent.report.DailyReport;
import sa.waslah.agent.support.SupportService;

/**
 * Timed jobs as HTTP calls, for Cloud Scheduler when the app scales to zero. Each job is idempotent, so a
 * retried or duplicated call is harmless. Protected by the X-Job-Token header; disabled when JOBS_TOKEN is blank.
 */
@RestController
public class JobsController {

    private static final Logger log = LoggerFactory.getLogger(JobsController.class);

    private final BookingJobs bookings;
    private final LicenseGuardJob licenses;
    private final BillingJobs billing;
    private final DailyReport reports;
    private final PrivacyService privacy;
    private final SupportService support;
    private final String token;

    public JobsController(BookingJobs bookings, LicenseGuardJob licenses, BillingJobs billing, DailyReport reports,
                          PrivacyService privacy, SupportService support, @Value("${waslah.jobs.token:}") String token) {
        this.bookings = bookings;
        this.licenses = licenses;
        this.billing = billing;
        this.reports = reports;
        this.privacy = privacy;
        this.support = support;
        this.token = token;
    }

    @PostMapping("/internal/jobs/{name}")
    Map<String, Object> run(@PathVariable String name, @RequestHeader(value = "X-Job-Token", required = false) String given) {
        if (token == null || token.isBlank() || given == null
                || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), given.getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(HttpStatus.NOT_FOUND, "not_found", "Not found");
        }
        long start = System.currentTimeMillis();
        switch (name) {
            case "bookings" -> bookings.tick();          // every 5 minutes: reminders and follow-ups
            case "daily-report" -> reports.hourly();     // hourly: each office's report at its chosen hour
            case "license-guard" -> licenses.daily();    // 06:00 Riyadh
            case "billing" -> billing.daily();           // 07:00 Riyadh
            case "retention" -> privacy.retention();     // 03:30 Riyadh
            case "support-retry" -> support.retry();     // every 10 minutes
            default -> throw ApiException.notFound("no_job", "Unknown job");
        }
        long ms = System.currentTimeMillis() - start;
        log.info("Job {} finished in {} ms", name, ms);
        return Map.of("job", name, "ms", ms);
    }
}
