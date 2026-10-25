package sa.waslah.agent.booking;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Viewing appointments under the current office (RLS). */
@Repository
public class BookingStore {

    public record Booking(UUID id, UUID conversationId, UUID listingId, UUID marketerId, String mode, Instant startsAt,
                          Instant endsAt, String status, String accessMethod, String accessCode, String customerName,
                          String note, String confirmation, Instant reminderSentAt, Instant followUpAt, Instant createdAt,
                          String listingTitle, String listingDistrict, String customerWaId, String marketerName) {
    }

    private static final String SELECT = """
            select b.id, b.conversation_id, b.listing_id, b.marketer_id, b.mode, b.starts_at, b.ends_at, b.status,
                   b.access_method, b.access_code, b.customer_name, b.note, b.confirmation, b.reminder_sent_at,
                   b.follow_up_at, b.created_at, l.title, l.district, k.wa_id, m.name as marketer_name
            from bookings b
            join listings l on l.id = b.listing_id
            join conversations c on c.id = b.conversation_id
            join contacts k on k.id = c.contact_id
            left join marketers m on m.id = b.marketer_id
            """;

    private final JdbcClient jdbc;

    public BookingStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public UUID create(UUID officeId, UUID conversationId, UUID listingId, UUID marketerId, String mode, Instant start,
                       Instant end, String status, String customerName, String note) {
        return jdbc.sql("""
                insert into bookings (office_id, conversation_id, listing_id, marketer_id, mode, starts_at, ends_at, status,
                                      customer_name, note)
                values (?, ?, ?, ?::uuid, ?, ?, ?, ?, ?, ?) returning id""")
                .params(officeId, conversationId, listingId, marketerId, mode, Timestamp.from(start), Timestamp.from(end),
                        status, customerName, note)
                .query(UUID.class).single();
    }

    public Optional<Booking> find(UUID id) {
        return jdbc.sql(SELECT + " where b.id = ?").param(id).query(BookingStore::map).optional();
    }

    public List<Booking> between(Instant from, Instant to) {
        return jdbc.sql(SELECT + " where b.starts_at >= ? and b.starts_at < ? order by b.starts_at")
                .params(Timestamp.from(from), Timestamp.from(to)).query(BookingStore::map).list();
    }

    public List<Booking> pending() {
        return jdbc.sql(SELECT + " where b.status = 'PENDING_APPROVAL' and b.starts_at > now() order by b.starts_at")
                .query(BookingStore::map).list();
    }

    public List<Booking> forConversation(UUID conversationId) {
        return jdbc.sql(SELECT + " where b.conversation_id = ? and b.status in ('PENDING_APPROVAL', 'CONFIRMED') "
                        + "and b.starts_at > now() order by b.starts_at")
                .param(conversationId).query(BookingStore::map).list();
    }

    /** Intervals already taken by this marketer (with-marketer viewings) or on this listing (any viewing). */
    public List<SlotEngine.Interval> busy(UUID marketerId, UUID listingId, Instant from, Instant to) {
        return jdbc.sql("""
                select starts_at, ends_at from bookings
                where status in ('PENDING_APPROVAL', 'CONFIRMED') and starts_at < ? and ends_at > ?
                  and ((cast(? as uuid) is not null and marketer_id = ? and mode = 'WITH_MARKETER') or listing_id = ?)""")
                .params(Timestamp.from(to), Timestamp.from(from), marketerId, marketerId, listingId)
                .query((rs, n) -> new SlotEngine.Interval(rs.getTimestamp(1).toInstant(), rs.getTimestamp(2).toInstant()))
                .list();
    }

    public void decide(UUID id, String status, String accessMethod, String accessCode, String confirmation) {
        jdbc.sql("""
                update bookings set status = ?, access_method = coalesce(?, access_method), access_code = coalesce(?, access_code),
                       confirmation = coalesce(?, confirmation), decided_at = now() where id = ?""")
                .params(status, accessMethod, accessCode, confirmation, id).update();
    }

    public void setStatus(UUID id, String status) {
        jdbc.sql("update bookings set status = ? where id = ?").params(status, id).update();
    }

    public List<Booking> dueReminders(Instant now) {
        return jdbc.sql(SELECT + """
                 join booking_settings s on s.office_id = b.office_id
                 where b.status = 'CONFIRMED' and b.reminder_sent_at is null and b.starts_at > cast(? as timestamptz)
                   and b.starts_at <= cast(? as timestamptz) + make_interval(mins => s.reminder_minutes)""")
                .params(Timestamp.from(now), Timestamp.from(now)).query(BookingStore::map).list();
    }

    public List<Booking> dueFollowUps(Instant now) {
        return jdbc.sql(SELECT + """
                 join booking_settings s on s.office_id = b.office_id
                 where b.status = 'CONFIRMED' and b.follow_up_at is null and s.follow_up_enabled
                   and b.ends_at <= cast(? as timestamptz) - interval '1 hour'
                   and b.ends_at > cast(? as timestamptz) - interval '2 days'""")
                .params(Timestamp.from(now), Timestamp.from(now)).query(BookingStore::map).list();
    }

    public void markReminded(UUID id) {
        jdbc.sql("update bookings set reminder_sent_at = now() where id = ?").param(id).update();
    }

    public void markFollowedUp(UUID id) {
        jdbc.sql("update bookings set follow_up_at = now(), status = 'DONE' where id = ? and status = 'CONFIRMED'").param(id).update();
    }

    private static Booking map(ResultSet rs, int n) throws SQLException {
        return new Booking(rs.getObject("id", UUID.class), rs.getObject("conversation_id", UUID.class),
                rs.getObject("listing_id", UUID.class), rs.getObject("marketer_id", UUID.class), rs.getString("mode"),
                ts(rs, "starts_at"), ts(rs, "ends_at"), rs.getString("status"), rs.getString("access_method"),
                rs.getString("access_code"), rs.getString("customer_name"), rs.getString("note"),
                rs.getString("confirmation"), ts(rs, "reminder_sent_at"), ts(rs, "follow_up_at"), ts(rs, "created_at"),
                rs.getString("title"), rs.getString("district"), rs.getString("wa_id"), rs.getString("marketer_name"));
    }

    private static Instant ts(ResultSet rs, String col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }
}
