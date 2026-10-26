package sa.waslah.agent.conversation;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Contacts, conversations and messages. Every method runs under the caller's tenant (RLS);
 * write methods must be called inside a transaction opened after TenantContext was set.
 */
@Repository
public class ConversationStore {

    public enum Sender { CUSTOMER, AGENT, HUMAN, SYSTEM }

    public record Inbound(String waId, String profileName, String waMessageId, String type, String body,
                          String mediaId, Instant sentAt) {
    }

    public record ConversationRef(UUID id, UUID waAccountId, String waId, Instant lastInboundAt, Instant agentPausedUntil) {
        public boolean agentActive(Instant now) {
            return agentPausedUntil == null || !agentPausedUntil.isAfter(now);
        }
    }

    /** Far-future pause used when an office takes over a conversation from the portal. */
    /** WhatsApp id of the office's own test chat ("جرّب الوكيل"): replies are stored, never sent to WhatsApp. */
    public static final String SANDBOX = "sandbox";

    public static final Instant TAKEN_OVER = Instant.parse("2999-01-01T00:00:00Z");

    private final JdbcClient jdbc;

    public ConversationStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public UUID upsertContact(UUID officeId, String waId, String profileName) {
        return jdbc.sql("""
                insert into contacts (office_id, wa_id, profile_name) values (?, ?, ?)
                on conflict (office_id, wa_id)
                do update set profile_name = coalesce(excluded.profile_name, contacts.profile_name)
                returning id""")
                .params(officeId, waId, profileName)
                .query(UUID.class).single();
    }

    /** Creates or updates the conversation for a contact. Pass lastInboundAt only for customer messages. */
    public ConversationRef upsertConversation(UUID officeId, UUID contactId, UUID waAccountId, Instant messageAt, boolean inbound) {
        Timestamp at = Timestamp.from(messageAt);
        return jdbc.sql("""
                insert into conversations (id, office_id, contact_id, wa_account_id, last_inbound_at, last_message_at)
                values (?, ?, ?, ?::uuid, ?::timestamptz, ?)
                on conflict (office_id, contact_id) do update set
                    wa_account_id   = coalesce(excluded.wa_account_id, conversations.wa_account_id),
                    last_inbound_at = greatest(conversations.last_inbound_at, excluded.last_inbound_at),
                    last_message_at = greatest(conversations.last_message_at, excluded.last_message_at)
                returning id, wa_account_id, last_inbound_at, agent_paused_until""")
                .params(UUID.randomUUID(), officeId, contactId, waAccountId, inbound ? at : null, at)
                .query((rs, n) -> new ConversationRef(
                        rs.getObject("id", UUID.class),
                        rs.getObject("wa_account_id", UUID.class),
                        null,
                        toInstant(rs.getTimestamp("last_inbound_at")),
                        toInstant(rs.getTimestamp("agent_paused_until"))))
                .single();
    }

    /** Inserts a message; returns empty when this wa_message_id was already stored (webhook retry). */
    public Optional<UUID> insertMessage(UUID officeId, UUID conversationId, String waMessageId, String direction,
                                        Sender sender, String type, String body, String mediaId, String status, Instant at) {
        return jdbc.sql("""
                insert into messages (office_id, conversation_id, wa_message_id, direction, sender, type, body, media_id, status, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (office_id, wa_message_id) do nothing
                returning id""")
                .params(officeId, conversationId, waMessageId, direction, sender.name(), type, body, mediaId, status, Timestamp.from(at))
                .query(UUID.class).optional();
    }

    public void pauseAgent(UUID conversationId, Instant until) {
        jdbc.sql("update conversations set agent_paused_until = ? where id = ?")
                .params(Timestamp.from(until), conversationId).update();
    }

    public void resumeAgent(UUID conversationId) {
        jdbc.sql("update conversations set agent_paused_until = null where id = ?").param(conversationId).update();
    }

    public void touchOutbound(UUID conversationId, Instant at) {
        jdbc.sql("update conversations set last_message_at = greatest(last_message_at, ?) where id = ?")
                .params(Timestamp.from(at), conversationId).update();
    }

    public int updateStatus(String waMessageId, String status, String error) {
        return jdbc.sql("update messages set status = ?, error = coalesce(?, error) where wa_message_id = ?")
                .params(status, error, waMessageId).update();
    }

    public Optional<ConversationRef> find(UUID conversationId) {
        return jdbc.sql("""
                select c.id, c.wa_account_id, k.wa_id, c.last_inbound_at, c.agent_paused_until
                from conversations c join contacts k on k.id = c.contact_id
                where c.id = ?""")
                .param(conversationId)
                .query((rs, n) -> new ConversationRef(
                        rs.getObject("id", UUID.class),
                        rs.getObject("wa_account_id", UUID.class),
                        rs.getString("wa_id"),
                        toInstant(rs.getTimestamp("last_inbound_at")),
                        toInstant(rs.getTimestamp("agent_paused_until"))))
                .optional();
    }

    public List<Map<String, Object>> list(int limit) {
        return jdbc.sql("""
                select c.id, k.wa_id, k.profile_name, c.last_message_at, c.last_inbound_at, c.agent_paused_until,
                       (select m.body from messages m where m.conversation_id = c.id order by m.created_at desc limit 1) as last_body,
                       (select m.sender from messages m where m.conversation_id = c.id order by m.created_at desc limit 1) as last_sender
                from conversations c join contacts k on k.id = c.contact_id
                order by c.last_message_at desc nulls last
                limit ?""")
                .param(limit)
                .query((rs, n) -> {
                    Instant paused = toInstant(rs.getTimestamp("agent_paused_until"));
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("id", rs.getObject("id", UUID.class));
                    m.put("waId", rs.getString("wa_id"));
                    m.put("name", rs.getString("profile_name"));
                    m.put("lastMessageAt", iso(rs.getTimestamp("last_message_at")));
                    m.put("lastInboundAt", iso(rs.getTimestamp("last_inbound_at")));
                    m.put("agentActive", paused == null || !paused.isAfter(Instant.now()));
                    m.put("takenOver", paused != null && paused.equals(TAKEN_OVER));
                    m.put("lastBody", rs.getString("last_body"));
                    m.put("lastSender", rs.getString("last_sender"));
                    return m;
                })
                .list();
    }

    public List<Map<String, Object>> messages(UUID conversationId, int limit) {
        return jdbc.sql("""
                select id, direction, sender, type, body, media_id, status, created_at
                from (select * from messages where conversation_id = ? order by created_at desc limit ?) recent
                order by created_at""")
                .params(conversationId, limit)
                .query((rs, n) -> {
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("id", rs.getObject("id", UUID.class));
                    m.put("direction", rs.getString("direction"));
                    m.put("sender", rs.getString("sender"));
                    m.put("type", rs.getString("type"));
                    m.put("body", rs.getString("body"));
                    m.put("mediaId", rs.getString("media_id"));
                    m.put("status", rs.getString("status"));
                    m.put("createdAt", iso(rs.getTimestamp("created_at")));
                    return m;
                })
                .list();
    }

    public static Instant humanPauseUntil(Instant now, int hours) {
        return now.plus(Duration.ofHours(Math.max(1, hours)));
    }

    private static Instant toInstant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private static String iso(Timestamp t) {
        return t == null ? null : OffsetDateTime.ofInstant(t.toInstant(), ZoneOffset.UTC).toString();
    }
}
