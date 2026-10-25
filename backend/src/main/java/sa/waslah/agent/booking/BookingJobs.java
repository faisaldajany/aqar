package sa.waslah.agent.booking;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import sa.waslah.agent.config.WaslahProperties;
import sa.waslah.agent.office.OfficeAlerts;
import sa.waslah.agent.tenant.TenantContext;

/**
 * Every five minutes, per office: reminds customers before a confirmed viewing, and an hour after
 * it asks how it went (the agent then handles the reply and suggests alternatives or hands off).
 * Outside WhatsApp's 24-hour window these need approved templates; without one the office is alerted.
 */
@Component
public class BookingJobs {

    private static final Logger log = LoggerFactory.getLogger(BookingJobs.class);

    private final JdbcClient jdbc;
    private final BookingStore bookings;
    private final BookingService service;
    private final OfficeAlerts alerts;
    private final WaslahProperties props;

    public BookingJobs(JdbcClient jdbc, BookingStore bookings, BookingService service, OfficeAlerts alerts, WaslahProperties props) {
        this.jdbc = jdbc;
        this.bookings = bookings;
        this.service = service;
        this.alerts = alerts;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${waslah.booking.job-interval-ms:300000}", initialDelayString = "${waslah.booking.job-initial-delay-ms:60000}")
    public void tick() {
        List<UUID> offices = jdbc.sql("select distinct office_id from users").query(UUID.class).list();
        for (UUID office : offices) {
            try {
                TenantContext.run(office, () -> runFor(office, Instant.now()));
            } catch (RuntimeException e) {
                log.warn("Booking jobs failed for office {}: {}", office, e.getMessage());
            }
        }
    }

    /** Must run under the office's tenant. */
    public void runFor(UUID office, Instant now) {
        LocalDate today = LocalDate.now(SlotEngine.RIYADH);
        for (BookingStore.Booking b : bookings.dueReminders(now)) {
            String when = SlotEngine.dayLabel(b.startsAt(), today) + " الساعة " + SlotEngine.time(b.startsAt().atZone(SlotEngine.RIYADH).toLocalTime());
            String text = "تذكير: موعد " + ("SELF_VISIT".equals(b.mode()) ? "زيارتك الذاتية" : "معاينتك") + " لـ" + b.listingTitle()
                    + " في " + b.listingDistrict() + " " + when + ". لو احتجت تغيّر الموعد رد على هذه الرسالة.";
            boolean sent = service.sendToCustomer(b.conversationId(), text, props.agent().reminderTemplate(),
                    List.of(name(b), b.listingTitle(), when));
            bookings.markReminded(b.id());
            if (!sent) {
                alerts.raise(office, "REMINDER_NOT_SENT", b.id(), "لم يُرسل تذكير موعد " + b.listingTitle() + " (" + when
                        + ") لأن نافذة 24 ساعة انتهت ولا يوجد قالب تذكير معتمد. ذكّر العميل يدوياً.", "reminder-" + b.id());
            }
        }
        for (BookingStore.Booking b : bookings.dueFollowUps(now)) {
            String text = "هلا " + name(b) + "، كيف كانت زيارتك لـ" + b.listingTitle()
                    + "؟ إذا أعجبك أحوّلك للمسوّق يكمل معك، وإذا تبي أقترح عليك عقارات ثانية قريبة منه.";
            service.sendToCustomer(b.conversationId(), text, props.agent().followUpTemplate(), List.of(name(b), b.listingTitle()));
            bookings.markFollowedUp(b.id());
        }
    }

    private static String name(BookingStore.Booking b) {
        return b.customerName() == null || b.customerName().isBlank() ? "حياك الله" : b.customerName();
    }
}
