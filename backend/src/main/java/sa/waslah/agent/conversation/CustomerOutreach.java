package sa.waslah.agent.conversation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import sa.waslah.agent.common.ApiException;
import sa.waslah.agent.conversation.ConversationStore.Sender;
import sa.waslah.agent.listing.ListingStore;
import sa.waslah.agent.tenant.TenantContext;
import sa.waslah.agent.whatsapp.MessagingWindowPolicy;
import sa.waslah.agent.whatsapp.OutboundMessageService;
import sa.waslah.agent.whatsapp.WaAccounts;
import sa.waslah.agent.whatsapp.WaTemplates;

/**
 * Messages to past customers, never to anyone who answered «إيقاف»:
 * <ul>
 *   <li>A new listing that fits what a customer asked for (same purpose and type, one of their districts, within
 *       their budget or up to 10% over). The office sees who fits and sends with one tap; one message per customer
 *       per listing. Inside WhatsApp's 24-hour window it is a normal message, outside it the approved template.</li>
 *   <li>When the office turns it on: one check-in with a serious customer who stopped replying for N days (approved
 *       template, between 10:00 and 20:00 Riyadh), once per quiet spell, not while a person handles the chat or a
 *       viewing is coming.</li>
 * </ul>
 */
@Service
public class CustomerOutreach {

    private static final Logger log = LoggerFactory.getLogger(CustomerOutreach.class);
    static final ZoneId RIYADH = ZoneId.of("Asia/Riyadh");
    /** A customer who got a listing message less than this ago is not ticked by default. */
    static final Duration RECENT_ALERT = Duration.ofDays(3);
    static final BigDecimal OVER_BUDGET = new BigDecimal("1.10");
    static final int MAX_SEND = 100;
    static final int CHECKINS_PER_RUN = 30;

    /** «إيقاف» and the usual ways of saying stop. The whole message, so "ما أبي أوقف البحث" does not count. */
    private static final Pattern STOP = Pattern.compile(
            "^(ايقاف|إيقاف|وقف|اوقف|أوقف|توقف|الغاء|إلغاء|الغاء الاشتراك|إلغاء الاشتراك|لا ترسل|لا ترسلوا|stop|unsubscribe)[.!\\s]*$",
            Pattern.CASE_INSENSITIVE);

    public record Match(UUID conversationId, String name, String phone, String need, BigDecimal budgetMax,
                        String temperature, List<String> why, boolean alreadySent, Instant lastAlertAt,
                        boolean windowOpen, boolean suggested) {
    }

    public record Result(UUID conversationId, boolean sent, String via, String error) {
    }

    record Candidate(UUID conversationId, String name, String waId, String purpose, String type, String districts,
                     BigDecimal budgetMin, BigDecimal budgetMax, String payment, String timeline, Instant lastInboundAt,
                     UUID waAccountId, Instant lastAlertAt) {
    }

    private final JdbcClient jdbc;
    private final ListingStore listings;
    private final OutboundMessageService outbound;
    private final WaTemplates templates;
    private final WaAccounts accounts;
    private final ConversationStore conversations;
    private final MessagingWindowPolicy window;

    public CustomerOutreach(JdbcClient jdbc, ListingStore listings, OutboundMessageService outbound, WaTemplates templates,
                            WaAccounts accounts, ConversationStore conversations, MessagingWindowPolicy window) {
        this.jdbc = jdbc;
        this.listings = listings;
        this.outbound = outbound;
        this.templates = templates;
        this.accounts = accounts;
        this.conversations = conversations;
        this.window = window;
    }

    public static boolean isStop(String text) {
        return text != null && STOP.matcher(text.trim()).matches();
    }

    // ---- New listing → customers waiting for one ----

    /** Customers this listing fits, best first. Runs as the office. */
    public List<Match> matches(UUID listingId) {
        ListingStore.Listing l = listings.find(listingId)
                .orElseThrow(() -> ApiException.notFound("no_listing", "العقار غير موجود."));
        Set<UUID> sent = Set.copyOf(jdbc.sql("select conversation_id from listing_alerts where listing_id = ? and status = 'SENT'")
                .param(listingId).query(UUID.class).list());
        Instant now = Instant.now();
        List<Match> out = new ArrayList<>();
        for (Candidate c : candidates()) {
            List<String> why = fits(c, l);
            if (why == null) continue;
            boolean already = sent.contains(c.conversationId());
            boolean recent = c.lastAlertAt() != null && c.lastAlertAt().isAfter(now.minus(RECENT_ALERT));
            out.add(new Match(c.conversationId(), c.name(), CustomersController.phone(c.waId()), need(c), c.budgetMax(),
                    WorkspaceController.temperature(c.purpose(), c.budgetMax(), c.payment(), c.timeline()), why, already,
                    c.lastAlertAt(), window.isOpen(c.lastInboundAt(), now), !already && !recent));
        }
        out.sort((a, b) -> Integer.compare(rank(a), rank(b)));
        return out;
    }

    private static int rank(Match m) {
        return (m.alreadySent() ? 100 : 0) + (m.suggested() ? 0 : 10) + switch (m.temperature()) {
            case "HOT" -> 0; case "WARM" -> 1; default -> 2;
        };
    }

    /** For the listings page: how many customers each active listing fits and have not been told yet. */
    public Map<UUID, Integer> counts() {
        List<Candidate> all = candidates();
        Map<UUID, Integer> out = new LinkedHashMap<>();
        if (all.isEmpty()) return out;
        List<Map<String, Object>> sent = jdbc.sql("select listing_id, conversation_id from listing_alerts where status = 'SENT'")
                .query((rs, n) -> Map.<String, Object>of("l", rs.getObject(1, UUID.class), "c", rs.getObject(2, UUID.class))).list();
        for (ListingStore.Listing l : listings.all()) {
            if (!"ACTIVE".equals(l.status())) continue;
            int n = 0;
            for (Candidate c : all) {
                if (fits(c, l) == null) continue;
                boolean told = sent.stream().anyMatch(s -> l.id().equals(s.get("l")) && c.conversationId().equals(s.get("c")));
                if (!told) n++;
            }
            if (n > 0) out.put(l.id(), n);
        }
        return out;
    }

    /** Sends the listing to these customers (only ones it fits, not told yet, not opted out). */
    public List<Result> send(UUID listingId, List<UUID> conversationIds) {
        if (conversationIds == null || conversationIds.isEmpty()) throw ApiException.badRequest("none", "اختر العملاء أولاً.");
        if (conversationIds.size() > MAX_SEND) throw ApiException.badRequest("too_many", "حتى " + MAX_SEND + " عميل في المرة.");
        ListingStore.Listing l = listings.find(listingId)
                .orElseThrow(() -> ApiException.notFound("no_listing", "العقار غير موجود."));
        if (!"ACTIVE".equals(l.status())) throw ApiException.conflict("not_active", "العقار غير معروض الآن.");
        Map<UUID, Match> fit = new LinkedHashMap<>();
        for (Match m : matches(listingId)) fit.put(m.conversationId(), m);
        List<Result> out = new ArrayList<>();
        for (UUID id : conversationIds) {
            Match m = fit.get(id);
            if (m == null) {
                out.add(new Result(id, false, null, "لم يعد العقار يطابق طلبه، أو طلب إيقاف الرسائل."));
                continue;
            }
            if (m.alreadySent()) {
                out.add(new Result(id, false, null, "أُرسل له هذا العقار من قبل."));
                continue;
            }
            Result r = deliverListing(l, m);
            jdbc.sql("""
                            insert into listing_alerts (office_id, listing_id, conversation_id, status, via, error)
                            values (?, ?, ?, ?, ?, ?)
                            on conflict (listing_id, conversation_id) do update set status = excluded.status, via = excluded.via,
                                error = excluded.error, created_at = now()""")
                    .params(TenantContext.require(), listingId, id, r.sent() ? "SENT" : "FAILED", r.via(), r.error()).update();
            out.add(r);
        }
        return out;
    }

    private Result deliverListing(ListingStore.Listing l, Match m) {
        String name = m.name() == null ? null : m.name().split("\\s+")[0];
        String price = money(l.price());
        if (m.windowOpen()) {
            StringBuilder text = new StringBuilder("أهلاً").append(name == null ? "" : " " + name)
                    .append("، وصلنا عقار قد يناسب طلبك: ").append(l.title()).append(" في ").append(l.district())
                    .append(" بسعر ").append(price);
            if (l.areaSqm() != null) text.append("، المساحة ").append(l.areaSqm().stripTrailingZeros().toPlainString()).append(" م²");
            if (l.rooms() != null) text.append("، ").append(l.rooms()).append(" غرف");
            text.append(". تحب أرسل لك الصور والموقع، أو أحجز لك موعد معاينة؟");
            try {
                outbound.sendText(m.conversationId(), text.toString(), Sender.AGENT);
                return new Result(m.conversationId(), true, "TEXT", null);
            } catch (RuntimeException e) {
                return new Result(m.conversationId(), false, "TEXT", e.getMessage());
            }
        }
        String text = "أهلاً " + (name == null ? "عميلنا العزيز" : name) + "، وصلنا عقار قد يناسب طلبك: " + l.title()
                + " في " + l.district() + " بسعر " + price + ".";
        return template(m.conversationId(), WaTemplates.Kind.NEW_LISTING,
                List.of(name == null ? "عميلنا العزيز" : name, l.title(), l.district(), price), text);
    }

    /** An approved template outside the 24-hour window, stored in the chat so the office and the agent see it. */
    private Result template(UUID conversationId, WaTemplates.Kind kind, List<String> params, String shown) {
        ConversationStore.ConversationRef conv = conversations.find(conversationId).orElse(null);
        if (conv == null || conv.waAccountId() == null) {
            return new Result(conversationId, false, "TEMPLATE", "لا يوجد رقم واتساب مربوط بهذه المحادثة.");
        }
        Optional<WaTemplates.Resolved> t = templates.resolve(conv.waAccountId(), kind);
        if (t.isEmpty()) {
            return new Result(conversationId, false, "TEMPLATE",
                    "مضى أكثر من 24 ساعة على آخر رسالة منه، وقالب «" + (kind == WaTemplates.Kind.NEW_LISTING ? "عقار جديد" : "المتابعة")
                            + "» لم تعتمده Meta بعد. تابع حالته في صفحة ربط واتساب.");
        }
        try {
            WaAccounts.Account a = accounts.byId(conv.waAccountId()).orElseThrow();
            List<String> clean = params.stream().map(p -> p == null ? "" : p.replaceAll("\\s+", " ").trim()).toList();
            String wamid = outbound.client().sendTemplate(accounts.credentials(a), conv.waId(), t.get().name(), t.get().language(), clean);
            Instant now = Instant.now();
            conversations.insertMessage(a.officeId(), conversationId, wamid, "OUT", Sender.SYSTEM, "template", shown, null, "sent", now);
            conversations.touchOutbound(conversationId, now);
            return new Result(conversationId, true, "TEMPLATE", null);
        } catch (RuntimeException e) {
            log.warn("{} template to conversation {} not sent: {}", kind, conversationId, e.getMessage());
            return new Result(conversationId, false, "TEMPLATE", "رفض واتساب الإرسال: " + e.getMessage());
        }
    }

    // ---- Quiet customers ----

    public Map<String, Object> followUpSettings() {
        Integer days = jdbc.sql("select quiet_followup_days from offices").query(Integer.class).optional().orElse(null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("days", days);
        out.put("sentLast30Days", jdbc.sql("""
                        select count(*) from conversations where checked_in_at > now() - interval '30 days'""")
                .query(Integer.class).single());
        out.put("templateStatus", templateStatus(WaTemplates.Kind.CHECKIN));
        return out;
    }

    public Map<String, Object> setFollowUpDays(Integer days) {
        if (days != null && (days < 2 || days > 30)) throw ApiException.badRequest("bad_days", "من 2 إلى 30 يوماً.");
        jdbc.sql("update offices set quiet_followup_days = ?").param(days).update();
        return followUpSettings();
    }

    private String templateStatus(WaTemplates.Kind kind) {
        return templates.statuses().stream().filter(r -> kind.name().equals(r.get("kind")))
                .map(r -> (String) r.get("status")).findFirst().orElse(null);
    }

    /** Hourly (Cloud Scheduler: the daily-report job). Check-ins only in the daytime, Riyadh. */
    @Scheduled(cron = "0 10 * * * *", zone = "Asia/Riyadh")
    public void hourly() {
        int hour = ZonedDateTime.now(RIYADH).getHour();
        if (hour < 10 || hour >= 20) return;
        List<UUID> offices = jdbc.sql("select id from offices where quiet_followup_days is not null and suspended_at is null")
                .query(UUID.class).list();
        for (UUID office : offices) {
            try {
                TenantContext.run(office, this::checkIns);
            } catch (RuntimeException e) {
                log.warn("Check-ins failed for office {}: {}", office, e.getMessage());
            }
        }
    }

    /** Runs as the office. Returns how many were sent. */
    int checkIns() {
        Integer days = jdbc.sql("select quiet_followup_days from offices").query(Integer.class).optional().orElse(null);
        if (days == null) return 0;
        List<Candidate> quiet = jdbc.sql(CANDIDATES + """
                          and l.stage in ('NEW', 'CONTACTED', 'VIEWING')
                          and c.last_inbound_at < now() - make_interval(days => :d)
                          and c.last_inbound_at > now() - make_interval(days => :d + 14)
                          and (c.checked_in_at is null or c.checked_in_at < c.last_inbound_at)
                          and (c.agent_paused_until is null or c.agent_paused_until < now())
                          and not exists (select 1 from bookings b where b.conversation_id = c.id
                                          and b.status in ('PENDING_APPROVAL', 'CONFIRMED') and b.starts_at > now())
                        order by c.last_inbound_at desc limit :max""")
                .param("sandbox", ConversationStore.SANDBOX).param("d", days).param("max", CHECKINS_PER_RUN * 3)
                .query(CustomerOutreach::candidate).list();
        int sent = 0;
        for (Candidate c : quiet) {
            if (sent >= CHECKINS_PER_RUN) break;
            String t = WorkspaceController.temperature(c.purpose(), c.budgetMax(), c.payment(), c.timeline());
            if ("COLD".equals(t)) continue;
            String name = c.name() == null ? null : c.name().split("\\s+")[0];
            String need = need(c);
            Result r = template(c.conversationId(), WaTemplates.Kind.CHECKIN, List.of(name == null ? "عميلنا العزيز" : name, need),
                    "أهلاً " + (name == null ? "عميلنا العزيز" : name) + "، ما زلت تبحث عن " + need + "؟ (متابعة تلقائية)");
            if (!r.sent()) {
                // Usually the template is not approved yet: the same for everyone, so stop until the next run.
                log.info("Check-ins for office {} paused: {}", TenantContext.require(), r.error());
                break;
            }
            jdbc.sql("update conversations set checked_in_at = now() where id = ?").param(c.conversationId()).update();
            sent++;
        }
        return sent;
    }

    // ---- Matching ----

    private static final String CANDIDATES = """
            select c.id, coalesce(nullif(trim(l.customer_name), ''), nullif(trim(k.profile_name), '')), k.wa_id,
                   l.purpose, l.property_type, l.districts, l.budget_min, l.budget_max, l.payment, l.timeline,
                   c.last_inbound_at, c.wa_account_id,
                   (select max(a.created_at) from listing_alerts a where a.conversation_id = c.id and a.status = 'SENT')
            from leads l
            join conversations c on c.id = l.conversation_id
            join contacts k on k.id = c.contact_id
            where k.wa_id <> :sandbox and k.erased_at is null and k.marketing_opt_out_at is null
              and l.purpose is not null and l.property_type is not null and l.districts is not null
            """;

    private List<Candidate> candidates() {
        return jdbc.sql(CANDIDATES + """
                          and l.stage not in ('WON', 'LOST')
                          and coalesce(c.last_message_at, c.created_at) > now() - interval '180 days'
                        limit 5000""")
                .param("sandbox", ConversationStore.SANDBOX).query(CustomerOutreach::candidate).list();
    }

    private static Candidate candidate(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        Timestamp in = rs.getTimestamp(11), alert = rs.getTimestamp(13);
        return new Candidate(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getString(6), rs.getBigDecimal(7), rs.getBigDecimal(8), rs.getString(9), rs.getString(10),
                in == null ? null : in.toInstant(), rs.getObject(12, UUID.class), alert == null ? null : alert.toInstant());
    }

    /** Why the listing fits this customer, or null if it does not. */
    static List<String> fits(Candidate c, ListingStore.Listing l) {
        if (l.purpose() == null || !l.purpose().equals(c.purpose())) return null;
        String want = key(c.type()), have = key(l.propertyType());
        if (want.isEmpty() || have.isEmpty() || !(want.equals(have) || want.contains(have) || have.contains(want))) return null;
        String district = key(l.district());
        boolean inDistrict = false;
        for (String d : c.districts().split("[،,;/]")) {
            String k = key(d);
            if (!k.isEmpty() && !district.isEmpty() && (k.equals(district) || k.contains(district) || district.contains(k))) {
                inDistrict = true;
                break;
            }
        }
        if (!inDistrict) return null;
        List<String> why = new ArrayList<>();
        why.add("يريد " + c.type() + ("SALE".equals(c.purpose()) ? " للشراء" : " للإيجار") + " في " + l.district());
        BigDecimal price = l.price();
        if (price != null && c.budgetMax() != null && c.budgetMax().signum() > 0) {
            if (price.compareTo(c.budgetMax().multiply(OVER_BUDGET)) > 0) return null;
            if (price.compareTo(c.budgetMax()) <= 0) why.add("ضمن ميزانيته (" + money(c.budgetMax()) + ")");
            else why.add("أعلى من ميزانيته بـ" + price.subtract(c.budgetMax()).multiply(BigDecimal.valueOf(100))
                    .divide(c.budgetMax(), 0, RoundingMode.CEILING) + "٪");
        } else {
            why.add("لم يذكر ميزانيته");
        }
        if (price != null && c.budgetMin() != null && price.compareTo(c.budgetMin().multiply(new BigDecimal("0.7"))) < 0) return null;
        return why;
    }

    /** For comparing places and types: spelling variants, «حي», and a leading «ال» do not matter. */
    static String key(String s) {
        return CustomersController.norm(s).replaceFirst("^ال", "").replaceAll("\\s+ال", " ").trim();
    }

    private static String need(Candidate c) {
        String first = c.districts() == null ? "" : c.districts().split("[،,;/]")[0].trim();
        return c.type() + ("SALE".equals(c.purpose()) ? " للشراء" : " للإيجار") + (first.isEmpty() ? "" : " في " + first);
    }

    static String money(BigDecimal v) {
        if (v == null) return "";
        return String.format(Locale.ROOT, "%,d", v.setScale(0, RoundingMode.HALF_UP).longValue()) + " ريال";
    }
}
