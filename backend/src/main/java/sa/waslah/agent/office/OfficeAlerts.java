package sa.waslah.agent.office;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Notices for the office (license expiry, usage near the plan limit, handoffs to review). Tenant-scoped. */
@Repository
public class OfficeAlerts {

    public record Alert(UUID id, String kind, UUID subjectId, String message, boolean read, String createdAt) {
    }

    private final JdbcClient jdbc;

    public OfficeAlerts(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Adds an alert once per dedupe key; returns true if it was new. */
    public boolean raise(UUID officeId, String kind, UUID subjectId, String message, String dedupeKey) {
        return jdbc.sql("""
                insert into office_alerts (office_id, kind, subject_id, message, dedupe_key)
                values (?, ?, ?::uuid, ?, ?) on conflict (office_id, dedupe_key) do nothing""")
                .params(officeId, kind, subjectId, message, dedupeKey)
                .update() > 0;
    }

    public List<Alert> recent(boolean unreadOnly, int limit) {
        return jdbc.sql("select id, kind, subject_id, message, read_at, created_at from office_alerts "
                        + (unreadOnly ? "where read_at is null " : "") + "order by created_at desc limit ?")
                .param(limit)
                .query((rs, n) -> new Alert(rs.getObject("id", UUID.class), rs.getString("kind"),
                        rs.getObject("subject_id", UUID.class), rs.getString("message"), rs.getTimestamp("read_at") != null,
                        OffsetDateTime.ofInstant(rs.getTimestamp("created_at").toInstant(), ZoneOffset.UTC).toString()))
                .list();
    }

    public void markRead(UUID id) {
        jdbc.sql("update office_alerts set read_at = now() where id = ? and read_at is null").param(id).update();
    }

    public void markAllRead() {
        jdbc.sql("update office_alerts set read_at = now() where read_at is null").update();
    }

    public Map<String, Integer> unreadCount() {
        return Map.of("unread", jdbc.sql("select count(*) from office_alerts where read_at is null").query(Integer.class).single());
    }
}
