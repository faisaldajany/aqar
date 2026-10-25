package sa.waslah.agent.privacy;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import sa.waslah.agent.common.ApiException;
import sa.waslah.agent.tenant.TenantContext;

/**
 * PDPL: erase one customer's personal data on request, and delete old chat content on a schedule.
 * Counts the office relies on (conversations, bookings, billing replies) survive without personal content.
 */
@RestController
public class PrivacyService {

    private static final Logger log = LoggerFactory.getLogger(PrivacyService.class);

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final int messageDays;
    private final int webhookDays;

    public PrivacyService(JdbcClient jdbc, TransactionTemplate tx,
                          @Value("${waslah.privacy.message-retention-days:730}") int messageDays,
                          @Value("${waslah.privacy.webhook-retention-days:30}") int webhookDays) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.messageDays = messageDays;
        this.webhookDays = webhookDays;
    }

    /** The office erases a customer (e.g. the customer asked on WhatsApp). Must run under the office's tenant. */
    @DeleteMapping("/api/conversations/{id}/personal-data")
    public Map<String, Object> erase(@PathVariable UUID id) {
        UUID contact = jdbc.sql("select contact_id from conversations where id = ?").param(id).query(UUID.class)
                .optional().orElseThrow(() -> ApiException.notFound("no_conversation", "المحادثة غير موجودة."));
        tx.executeWithoutResult(s -> {
            List<UUID> convs = jdbc.sql("select id from conversations where contact_id = ?").param(contact).query(UUID.class).list();
            for (UUID c : convs) {
                jdbc.sql("delete from messages where conversation_id = ?").param(c).update();
                jdbc.sql("delete from leads where conversation_id = ?").param(c).update();
                jdbc.sql("update agent_runs set tool_calls = '[]'::jsonb, error = null where conversation_id = ?").param(c).update();
                jdbc.sql("update handoffs set summary = 'محذوف بطلب العميل' where conversation_id = ?").param(c).update();
                jdbc.sql("update bookings set customer_name = null, note = null, confirmation = null where conversation_id = ?").param(c).update();
                jdbc.sql("update conversations set agent_paused_until = ? where id = ?")
                        .params(Timestamp.from(Instant.parse("2999-01-01T00:00:00Z")), c).update();
            }
            jdbc.sql("update contacts set profile_name = null, wa_id = 'erased-' || id::text, erased_at = now() where id = ?")
                    .param(contact).update();
        });
        return Map.of("erased", true);
    }

    @Scheduled(cron = "0 30 3 * * *", zone = "Asia/Riyadh")
    public void retention() {
        Timestamp webhookCutoff = Timestamp.from(Instant.now().minus(Duration.ofDays(webhookDays)));
        int raw = jdbc.sql("delete from webhook_events where received_at < ?").param(webhookCutoff).update();
        int msgs = 0;
        for (UUID office : jdbc.sql("select distinct office_id from users").query(UUID.class).list()) {
            try {
                msgs += TenantContext.call(office, () -> purge(Instant.now()));
            } catch (RuntimeException e) {
                log.warn("Retention failed for office {}: {}", office, e.getMessage());
            }
        }
        log.info("Retention: {} webhook payloads and {} messages deleted", raw, msgs);
    }

    /** Must run under the office's tenant. */
    public int purge(Instant now) {
        Timestamp cutoff = Timestamp.from(now.minus(Duration.ofDays(messageDays)));
        int n = jdbc.sql("delete from messages where created_at < ?").param(cutoff).update();
        jdbc.sql("update agent_runs set tool_calls = '[]'::jsonb where created_at < ? and tool_calls <> '[]'::jsonb").param(cutoff).update();
        jdbc.sql("delete from daily_reports where report_date < cast(? as date)").param(cutoff).update();
        return n;
    }
}
