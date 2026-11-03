package sa.waslah.agent.conversation;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import sa.waslah.agent.common.ApiException;
import sa.waslah.agent.listing.MarketerStore;
import sa.waslah.agent.office.OfficeAlerts;
import sa.waslah.agent.tenant.TenantContext;
import sa.waslah.agent.whatsapp.OutboundMessageService;
import sa.waslah.agent.whatsapp.WaAccounts;
import sa.waslah.agent.whatsapp.WaTemplates;

/**
 * Marketers and their customers. When the office sets a time limit, a handoff nobody from the office answered in time
 * (no reply in the chat, not claimed in the portal) moves to the next marketer, between 08:00 and 23:00 Riyadh; when
 * every marketer had it, the office is told instead. And each marketer's numbers: customers received, answered, how
 * fast, moved on, viewings done or missed, deals.
 */
@Service
public class HandoffRouting {

    private static final Logger log = LoggerFactory.getLogger(HandoffRouting.class);
    static final ZoneId RIYADH = ZoneId.of("Asia/Riyadh");

    private final JdbcClient jdbc;
    private final MarketerStore marketers;
    private final WaTemplates templates;
    private final WaAccounts accounts;
    private final OutboundMessageService outbound;
    private final OfficeAlerts alerts;

    public HandoffRouting(JdbcClient jdbc, MarketerStore marketers, WaTemplates templates, WaAccounts accounts,
                          OutboundMessageService outbound, OfficeAlerts alerts) {
        this.jdbc = jdbc;
        this.marketers = marketers;
        this.templates = templates;
        this.accounts = accounts;
        this.outbound = outbound;
        this.alerts = alerts;
    }

    // ---- Settings ----

    public Map<String, Object> settings() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("timeoutMinutes", jdbc.sql("select handoff_timeout_minutes from offices").query(Integer.class).optional().orElse(null));
        return out;
    }

    public Map<String, Object> setTimeout(Integer minutes) {
        if (minutes != null && (minutes < 5 || minutes > 240)) throw ApiException.badRequest("bad_minutes", "من 5 إلى 240 دقيقة.");
        jdbc.sql("update offices set handoff_timeout_minutes = ?").param(minutes).update();
        return settings();
    }

    // ---- Moving unanswered handoffs on ----

    /** Every 5 minutes (Cloud Scheduler: the bookings job). */
    @Scheduled(fixedDelayString = "${waslah.booking.job-interval-ms:300000}", initialDelayString = "${waslah.booking.job-initial-delay-ms:60000}")
    public void tick() {
        int hour = ZonedDateTime.now(RIYADH).getHour();
        if (hour < 8 || hour >= 23) return;
        List<UUID> offices = jdbc.sql("select id from offices where handoff_timeout_minutes is not null and suspended_at is null")
                .query(UUID.class).list();
        for (UUID office : offices) {
            try {
                TenantContext.run(office, this::reassignStale);
            } catch (RuntimeException e) {
                log.warn("Handoff routing failed for office {}: {}", office, e.getMessage());
            }
        }
    }

    record Stale(UUID id, UUID conversationId, UUID marketerId, String marketer, String summary, String districts,
                 String customer, UUID waAccountId) {
    }

    /** Runs as the office. Returns how many handoffs moved to another marketer. */
    int reassignStale() {
        Integer minutes = jdbc.sql("select handoff_timeout_minutes from offices").query(Integer.class).optional().orElse(null);
        if (minutes == null) return 0;
        List<Stale> stale = jdbc.sql("""
                        select h.id, h.conversation_id, h.marketer_id, m.name, h.summary, l.districts,
                               coalesce(nullif(trim(l.customer_name), ''), nullif(trim(k.profile_name), ''), k.wa_id), c.wa_account_id
                        from handoffs h
                        join marketers m on m.id = h.marketer_id
                        join conversations c on c.id = h.conversation_id
                        join contacts k on k.id = c.contact_id
                        left join leads l on l.conversation_id = h.conversation_id
                        where h.status = 'OPEN' and h.claimed_at is null and h.assigned_at is not null
                          and h.assigned_at < now() - make_interval(mins => ?)
                          and not exists (select 1 from messages x where x.conversation_id = h.conversation_id
                                          and x.sender = 'HUMAN' and x.created_at > h.assigned_at)
                        order by h.assigned_at limit 50""")
                .param(minutes)
                .query((rs, n) -> new Stale(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getObject(8, UUID.class)))
                .list();
        int moved = 0;
        for (Stale h : stale) {
            List<UUID> tried = jdbc.sql("select marketer_id from handoff_assignments where handoff_id = ?").param(h.id())
                    .query(UUID.class).list();
            List<UUID> skip = new ArrayList<>(tried);
            if (!skip.contains(h.marketerId())) skip.add(h.marketerId());
            String district = h.districts() == null ? null : h.districts().split("[,،]")[0].trim();
            Optional<MarketerStore.Marketer> next = marketers.forDistrict(district, skip);
            jdbc.sql("update handoff_assignments set ended_at = now(), ended_reason = 'TIMEOUT' where handoff_id = ? and ended_at is null")
                    .param(h.id()).update();
            if (next.isEmpty()) {
                // Everyone had it: stop moving it around and tell the office.
                jdbc.sql("update handoffs set assigned_at = null where id = ?").param(h.id()).update();
                alerts.raise(TenantContext.require(), "HANDOFF_UNANSWERED", h.conversationId(),
                        "لم يرد أي مسوّق على العميل " + h.customer() + " حتى الآن. افتح التحويلات وتابعه بنفسك.",
                        "handoff-unanswered-" + h.id());
                continue;
            }
            MarketerStore.Marketer m = next.get();
            jdbc.sql("update handoffs set marketer_id = ?, assigned_at = now() where id = ?").params(m.id(), h.id()).update();
            jdbc.sql("insert into handoff_assignments (office_id, handoff_id, marketer_id) values (?, ?, ?)")
                    .params(TenantContext.require(), h.id(), m.id()).update();
            jdbc.sql("update conversations set assigned_marketer_id = ? where id = ? and assigned_marketer_id = ?")
                    .params(m.id(), h.conversationId(), h.marketerId()).update();
            String note = "حُوّل إليك لأن " + h.marketer() + " لم يرد خلال " + minutes + " دقيقة. " + (h.summary() == null ? "" : h.summary());
            alertMarketer(h.waAccountId(), m, h.customer(), note);
            alerts.raise(TenantContext.require(), "HANDOFF_MOVED", h.conversationId(),
                    "حُوّل العميل " + h.customer() + " من " + h.marketer() + " إلى " + m.name() + " لعدم الرد خلال " + minutes + " دقيقة.",
                    "handoff-moved-" + h.id() + "-" + m.id());
            moved++;
        }
        return moved;
    }

    private void alertMarketer(UUID waAccountId, MarketerStore.Marketer m, String customer, String summary) {
        if (waAccountId == null) return;
        try {
            Optional<WaTemplates.Resolved> t = templates.resolve(waAccountId, WaTemplates.Kind.HANDOFF);
            if (t.isEmpty()) return;
            WaAccounts.Account acct = accounts.byId(waAccountId).orElseThrow();
            outbound.client().sendTemplate(accounts.credentials(acct), m.waPhone(), t.get().name(), t.get().language(),
                    List.of(customer, summary.replaceAll("\\s+", " ").trim()));
        } catch (RuntimeException e) {
            log.warn("Could not alert marketer {}: {}", m.id(), e.getMessage());
        }
    }

    // ---- Performance ----

    /** Per marketer over the last {@code days}: received, answered, median time to answer, moved on, viewings, deals. */
    public List<Map<String, Object>> performance(int days) {
        Timestamp since = Timestamp.from(Instant.now().minusSeconds(days * 86400L));
        Map<UUID, Map<String, Object>> rows = new LinkedHashMap<>();
        jdbc.sql("select id, name, active from marketers order by active desc, created_at").query((rs, n) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("marketerId", rs.getObject(1, UUID.class));
            m.put("name", rs.getString(2));
            m.put("active", rs.getBoolean(3));
            for (String k : List.of("received", "answered", "movedOn", "open", "viewingsDone", "noShows", "upcoming", "deals")) m.put(k, 0);
            m.put("medianAnswerMinutes", null);
            m.put("dealValue", BigDecimal.ZERO);
            rows.put(rs.getObject(1, UUID.class), m);
            return null;
        }).list();

        // Each time a marketer had a customer (older handoffs, from before assignments were kept, count once).
        jdbc.sql("""
                        with asg as (
                            select a.marketer_id, a.handoff_id, a.assigned_at, a.ended_at, a.ended_reason
                            from handoff_assignments a where a.assigned_at >= :since
                            union all
                            select h.marketer_id, h.id, h.created_at, null, null from handoffs h
                            where h.marketer_id is not null and h.created_at >= :since
                              and not exists (select 1 from handoff_assignments a where a.handoff_id = h.id)
                        ), answered as (
                            select asg.*, h.status,
                                   least(case when h.claimed_at >= asg.assigned_at and (asg.ended_at is null or h.claimed_at <= asg.ended_at)
                                              then h.claimed_at end,
                                         (select min(x.created_at) from messages x where x.conversation_id = h.conversation_id
                                            and x.sender = 'HUMAN' and x.created_at > asg.assigned_at
                                            and (asg.ended_at is null or x.created_at <= asg.ended_at))) as answered_at
                            from asg join handoffs h on h.id = asg.handoff_id
                        )
                        select marketer_id, count(*), count(answered_at), count(*) filter (where ended_reason = 'TIMEOUT'),
                               count(*) filter (where answered_at is null and ended_at is null and status = 'OPEN'),
                               percentile_cont(0.5) within group (order by extract(epoch from answered_at - assigned_at) / 60.0)
                                   filter (where answered_at is not null)
                        from answered group by marketer_id""")
                .param("since", since)
                .query((rs, n) -> {
                    Map<String, Object> m = rows.get(rs.getObject(1, UUID.class));
                    if (m == null) return null;
                    m.put("received", rs.getInt(2));
                    m.put("answered", rs.getInt(3));
                    m.put("movedOn", rs.getInt(4));
                    m.put("open", rs.getInt(5));
                    double med = rs.getDouble(6);
                    m.put("medianAnswerMinutes", rs.wasNull() ? null : Math.round(med * 10) / 10.0);
                    return null;
                }).list();

        jdbc.sql("""
                        select marketer_id, count(*) filter (where status = 'DONE'), count(*) filter (where status = 'NO_SHOW'),
                               count(*) filter (where status in ('PENDING_APPROVAL', 'CONFIRMED') and starts_at > now())
                        from bookings where marketer_id is not null and (starts_at >= :since or starts_at > now())
                        group by marketer_id""")
                .param("since", since)
                .query((rs, n) -> {
                    Map<String, Object> m = rows.get(rs.getObject(1, UUID.class));
                    if (m == null) return null;
                    m.put("viewingsDone", rs.getInt(2));
                    m.put("noShows", rs.getInt(3));
                    m.put("upcoming", rs.getInt(4));
                    return null;
                }).list();

        // A deal counts for the marketer the customer was with: the one the office assigned, else the last handoff's.
        jdbc.sql("""
                        select coalesce(c.assigned_marketer_id,
                                        (select h.marketer_id from handoffs h where h.conversation_id = c.id and h.marketer_id is not null
                                         order by h.created_at desc limit 1)) as m,
                               count(*), coalesce(sum(l.deal_value), 0)
                        from leads l join conversations c on c.id = l.conversation_id
                        where l.stage = 'WON' and l.stage_at >= :since
                        group by 1""")
                .param("since", since)
                .query((rs, n) -> {
                    UUID id = rs.getObject(1, UUID.class);
                    Map<String, Object> m = id == null ? null : rows.get(id);
                    if (m == null) return null;
                    m.put("deals", rs.getInt(2));
                    m.put("dealValue", rs.getBigDecimal(3));
                    return null;
                }).list();

        return rows.values().stream()
                .filter(m -> Boolean.TRUE.equals(m.get("active")) || (int) m.get("received") > 0 || (int) m.get("deals") > 0)
                .toList();
    }
}
