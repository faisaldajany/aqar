package sa.waslah.agent.affiliate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import sa.waslah.agent.billing.PromoService;
import sa.waslah.agent.common.ApiException;
import sa.waslah.agent.common.Email;
import sa.waslah.agent.common.Mailer;
import sa.waslah.agent.tenant.TenantContext;

/**
 * Hidaa's marketers: created by the team, each with a commission rate and their own promo codes. What a marketer sees
 * on their page: their codes, their earnings (pending and paid out), and their clients — only while the client's code
 * is switched on and is still the code the client uses. A client that left (another code, or the code was switched
 * off) shows in their history as «عميل سابق», without its name. Business details only: no contacts, no conversations.
 */
@Service
public class AffiliateService {

    private static final Logger log = LoggerFactory.getLogger(AffiliateService.class);
    static final ZoneId RIYADH = ZoneId.of("Asia/Riyadh");

    private final JdbcClient jdbc;
    private final PromoService promos;
    private final PartnerAuth auth;
    private final Mailer mailer;
    private final TransactionTemplate tx;
    private final ExecutorService thread = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "partner-mail");
        t.setDaemon(true);
        return t;
    });

    public AffiliateService(JdbcClient jdbc, PromoService promos, PartnerAuth auth, Mailer mailer, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.promos = promos;
        this.auth = auth;
        this.mailer = mailer;
        this.tx = tx;
    }

    public record NewAffiliate(String fullName, String email, String phone, BigDecimal commissionPercent, String iban, String note) {
    }

    public record Changes(String fullName, String phone, BigDecimal commissionPercent, String iban, String note, Boolean active) {
    }

    // ---- The team's side ----

    public UUID create(NewAffiliate n) {
        if (n == null) throw ApiException.badRequest("bad_affiliate", "بيانات المسوّق ناقصة.");
        String name = text(n.fullName(), 100);
        if (name == null) throw ApiException.badRequest("bad_name", "«الاسم»: مطلوب.");
        String email = PartnerAuth.normalizeEmail(n.email());
        if (!email.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+") || email.length() > 200) {
            throw ApiException.badRequest("bad_email", "«البريد الإلكتروني»: اكتب بريداً صحيحاً.");
        }
        BigDecimal rate = rate(n.commissionPercent());
        if (jdbc.sql("select exists(select 1 from affiliates where email = ?)").param(email).query(Boolean.class).single()) {
            throw ApiException.conflict("email_taken", "يوجد مسوّق بهذا البريد.");
        }
        return jdbc.sql("""
                        insert into affiliates (full_name, email, phone, commission_percent, iban, note)
                        values (?, ?, ?, ?, ?, ?) returning id""")
                .params(name, email, text(n.phone(), 30), rate, iban(n.iban()), text(n.note(), 300))
                .query(UUID.class).single();
    }

    public void update(UUID id, Changes c) {
        require(id);
        if (c == null) return;
        if (c.fullName() != null) {
            String name = text(c.fullName(), 100);
            if (name == null) throw ApiException.badRequest("bad_name", "«الاسم»: مطلوب.");
            jdbc.sql("update affiliates set full_name = ? where id = ?").params(name, id).update();
        }
        if (c.commissionPercent() != null) {
            jdbc.sql("update affiliates set commission_percent = ? where id = ?").params(rate(c.commissionPercent()), id).update();
        }
        if (c.phone() != null) jdbc.sql("update affiliates set phone = ? where id = ?").params(text(c.phone(), 30), id).update();
        if (c.iban() != null) jdbc.sql("update affiliates set iban = ? where id = ?").params(iban(c.iban()), id).update();
        if (c.note() != null) jdbc.sql("update affiliates set note = ? where id = ?").params(text(c.note(), 300), id).update();
        if (c.active() != null) jdbc.sql("update affiliates set active = ? where id = ?").params(c.active(), id).update();
    }

    public List<Map<String, Object>> list() {
        return jdbc.sql("""
                        select a.id, a.full_name, a.email, a.phone, a.commission_percent, a.active, a.password_hash is not null,
                               a.last_login_at, a.created_at,
                               (select count(*) from promo_codes p where p.affiliate_id = a.id),
                               (select count(*) from affiliate_clients ac join promo_codes p on p.id = ac.promo_code_id
                                 where ac.affiliate_id = a.id and p.active),
                               (select coalesce(sum(amount_halalas), 0) from affiliate_commissions c where c.affiliate_id = a.id and c.payout_id is null),
                               (select coalesce(sum(amount_halalas), 0) from affiliate_commissions c where c.affiliate_id = a.id and c.payout_id is not null)
                        from affiliates a order by a.created_at desc""")
                .query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getObject(1, UUID.class));
                    m.put("fullName", rs.getString(2));
                    m.put("email", rs.getString(3));
                    m.put("phone", rs.getString(4));
                    m.put("commissionPercent", rs.getBigDecimal(5));
                    m.put("active", rs.getBoolean(6));
                    m.put("hasPassword", rs.getBoolean(7));
                    m.put("lastLoginAt", iso(rs.getTimestamp(8)));
                    m.put("createdAt", iso(rs.getTimestamp(9)));
                    m.put("codes", rs.getInt(10));
                    m.put("clients", rs.getInt(11));
                    m.put("pendingHalalas", rs.getLong(12));
                    m.put("paidHalalas", rs.getLong(13));
                    return m;
                }).list();
    }

    /** Pays out everything pending (a bank transfer the team made). */
    public Map<String, Object> payout(UUID id, String reference, String note) {
        require(id);
        return tx.execute(status -> {
            jdbc.sql("select id from affiliates where id = ? for update").param(id).query(UUID.class).single();
            List<Object[]> pending = jdbc.sql("""
                            select id, amount_halalas from affiliate_commissions where affiliate_id = ? and payout_id is null for update""")
                    .param(id).query((rs, n) -> new Object[]{rs.getObject(1, UUID.class), rs.getLong(2)}).list();
            long total = pending.stream().mapToLong(r -> (Long) r[1]).sum();
            if (total <= 0) throw ApiException.conflict("nothing_due", "لا يوجد مبلغ مستحق لهذا المسوّق.");
            UUID payout = jdbc.sql("insert into affiliate_payouts (affiliate_id, amount_halalas, reference, note) values (?, ?, ?, ?) returning id")
                    .params(id, total, text(reference, 100), text(note, 300)).query(UUID.class).single();
            for (Object[] r : pending) {
                jdbc.sql("update affiliate_commissions set payout_id = ? where id = ?").params(payout, r[0]).update();
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", payout);
            m.put("amountHalalas", total);
            m.put("entries", pending.size());
            return m;
        });
    }

    public record Invite(String url, boolean emailed) {
    }

    /** Emails the marketer a link to set their password (valid 7 days); the team also gets the link to send it themselves. */
    public Invite invite(UUID id) {
        Map<String, Object> a = require(id);
        if (!Boolean.TRUE.equals(a.get("active"))) throw ApiException.conflict("partner_disabled", "فعّل المسوّق أولاً.");
        PartnerAuth.Link link = auth.link(id, true);
        boolean first = !Boolean.TRUE.equals(a.get("hasPassword"));
        Email email = new Email("دعوة إلى بوابة مسوّقي حداء", Email.greet((String) a.get("fullName")), List.of(
                first ? "أضافك فريق حداء مسوّقاً بالعمولة. من بوابة المسوّقين ترى أكوادك، والمكاتب التي اشتركت بها، وعمولتك من كل دفعة."
                        : "هذا رابط لتعيين كلمة مرور جديدة لبوابة مسوّقي حداء.",
                "عمولتك: " + ((BigDecimal) a.get("commissionPercent")).stripTrailingZeros().toPlainString() + "٪ مما يدفعه المكتب قبل الضريبة، على كل دفعة تتم بكودك."),
                new Email.Button(first ? "أنشئ كلمة المرور" : "عيّن كلمة المرور", link.url()),
                "الرابط صالح لمدة 7 أيام ولمرة واحدة. بعدها تدخل بوابة المسوّقين ببريدك وكلمة المرور.");
        boolean emailed = mailer.configured() && mailer.send((String) a.get("email"), "دعوة إلى بوابة مسوّقي حداء", email);
        return new Invite(link.url(), emailed);
    }

    /** "Forgot password": emails a 1-hour link if the address is an active marketer's. Says nothing either way. */
    public void forgot(String email) {
        String e = PartnerAuth.normalizeEmail(email);
        thread.execute(() -> {
            try {
                jdbc.sql("select id, full_name from affiliates where email = ? and active").param(e)
                        .query((rs, n) -> new Object[]{rs.getObject(1, UUID.class), rs.getString(2)}).optional()
                        .ifPresent(r -> {
                            PartnerAuth.Link link = auth.link((UUID) r[0], false);
                            mailer.send(e, "تعيين كلمة مرور بوابة المسوّقين — حداء", new Email(
                                    "رابط تعيين كلمة مرور بوابة مسوّقي حداء، صالح لمدة ساعة.", Email.greet((String) r[1]),
                                    List.of("طلبت تعيين كلمة مرور جديدة لبوابة مسوّقي حداء."),
                                    new Email.Button("عيّن كلمة المرور", link.url()),
                                    "إذا لم تطلب ذلك فتجاهل هذه الرسالة؛ كلمة مرورك الحالية تبقى كما هي."));
                        });
            } catch (RuntimeException ex) {
                log.warn("Partner reset email not sent: {}", ex.getMessage());
            }
        });
    }

    // ---- The marketer's page (and the team's view of it) ----

    /**
     * Everything on a marketer's page. The team's view ({@code team}) adds the private fields (IBAN, note) and the
     * marketer's former clients, by name.
     */
    public Map<String, Object> page(UUID id, boolean team) {
        Map<String, Object> a = require(id);
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> me = new LinkedHashMap<>();
        for (String k : team ? List.of("id", "fullName", "email", "phone", "commissionPercent", "active", "hasPassword", "lastLoginAt",
                "createdAt", "iban", "note") : List.of("fullName", "email", "commissionPercent")) {
            me.put(k, a.get(k));
        }
        out.put("affiliate", me);

        // Clients: offices whose code is this marketer's. Shown to the marketer only while that code is on.
        record Client(UUID office, String code, boolean codeActive, Instant since) {
        }
        List<Client> attributed = jdbc.sql("""
                        select ac.office_id, p.code, p.active, ac.since from affiliate_clients ac
                        join promo_codes p on p.id = ac.promo_code_id where ac.affiliate_id = ? order by ac.since desc""")
                .param(id).query((rs, n) -> new Client(rs.getObject(1, UUID.class), rs.getString(2), rs.getBoolean(3),
                        rs.getTimestamp(4).toInstant())).list();
        Map<UUID, long[]> perOffice = new HashMap<>();
        jdbc.sql("""
                        select office_id, coalesce(sum(base_halalas), 0), coalesce(sum(amount_halalas), 0)
                        from affiliate_commissions where affiliate_id = ? group by office_id""")
                .param(id).query((rs, n) -> perOffice.put(rs.getObject(1, UUID.class), new long[]{rs.getLong(2), rs.getLong(3)})).list();
        Map<UUID, String> names = new HashMap<>();
        int payingClients = 0;
        List<Map<String, Object>> clients = new ArrayList<>();
        for (Client c : attributed) {
            if (!c.codeActive() && !team) continue;
            Map<String, Object> o = TenantContext.call(c.office(), () -> jdbc.sql("""
                            select name, plan, billing_cycle, subscription_status, paid_until, trial_ends_at from offices""")
                    .query((rs, n) -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("name", rs.getString(1));
                        m.put("plan", rs.getString(2));
                        m.put("cycle", rs.getString(3));
                        m.put("status", rs.getString(4));
                        m.put("paidUntil", iso(rs.getTimestamp(5)));
                        m.put("trialEndsAt", iso(rs.getTimestamp(6)));
                        return m;
                    }).optional().orElse(null));
            if (o == null) continue;
            if (c.codeActive()) {
                names.put(c.office(), (String) o.get("name"));
                if ("ACTIVE".equals(o.get("status"))) payingClients++;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            if (team) m.put("officeId", c.office());
            m.putAll(o);
            m.put("code", c.code());
            m.put("since", c.since().toString());
            long[] sums = perOffice.getOrDefault(c.office(), new long[2]);
            m.put("paidHalalas", sums[0]);
            m.put("commissionHalalas", sums[1]);
            if (team) m.put("visibleToMarketer", c.codeActive());
            clients.add(m);
        }
        out.put("clients", clients);

        // Totals.
        Instant monthStart = LocalDate.now(RIYADH).withDayOfMonth(1).atStartOfDay(RIYADH).toInstant();
        long[] t = jdbc.sql("""
                        select coalesce(sum(amount_halalas) filter (where payout_id is null), 0),
                               coalesce(sum(amount_halalas) filter (where payout_id is not null), 0),
                               coalesce(sum(amount_halalas), 0),
                               coalesce(sum(amount_halalas) filter (where created_at >= ?), 0)
                        from affiliate_commissions where affiliate_id = ?""")
                .params(Timestamp.from(monthStart), id)
                .query((rs, n) -> new long[]{rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)}).single();
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("pendingHalalas", t[0]);
        totals.put("paidHalalas", t[1]);
        totals.put("earnedHalalas", t[2]);
        totals.put("thisMonthHalalas", t[3]);
        totals.put("clients", names.size());
        totals.put("payingClients", payingClients);
        out.put("totals", totals);

        // Codes.
        out.put("codes", promos.forAffiliate(id).stream().map(p -> {
            Map<String, Object> m = new LinkedHashMap<>();
            if (team) m.put("id", p.id());
            m.put("code", p.code());
            m.put("summary", p.summary());
            m.put("newCustomersOnly", p.newCustomersOnly());
            m.put("active", p.active());
            m.put("expiresAt", p.expiresAt() == null ? null : p.expiresAt().toString());
            // The last day it can be used (expiresAt is the start of the day after).
            m.put("expiresOn", p.expiresAt() == null ? null : p.expiresAt().atZone(RIYADH).toLocalDate().minusDays(1).toString());
            m.put("redemptions", p.redemptions());
            m.put("maxRedemptions", p.maxRedemptions());
            return m;
        }).toList());

        // Ledger: a former client's entries stay, without the client's name (unless it's the team looking).
        record Entry(Instant at, String entry, String kind, UUID office, long base, BigDecimal percent, long amount, boolean paid,
                     Instant paidAt) {
        }
        List<Entry> entries = jdbc.sql("""
                        select c.created_at, c.entry, c.invoice_kind, c.office_id, c.base_halalas, c.percent, c.amount_halalas,
                               c.payout_id is not null, p.created_at
                        from affiliate_commissions c left join affiliate_payouts p on p.id = c.payout_id
                        where c.affiliate_id = ? order by c.created_at desc limit 200""")
                .param(id).query((rs, n) -> new Entry(rs.getTimestamp(1).toInstant(), rs.getString(2), rs.getString(3),
                        rs.getObject(4, UUID.class), rs.getLong(5), rs.getBigDecimal(6), rs.getLong(7), rs.getBoolean(8),
                        rs.getTimestamp(9) == null ? null : rs.getTimestamp(9).toInstant())).list();
        Map<UUID, String> teamNames = new HashMap<>();
        List<Map<String, Object>> ledger = new ArrayList<>();
        for (Entry e : entries) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("at", e.at().toString());
            m.put("entry", e.entry());
            m.put("invoiceKind", e.kind());
            m.put("client", names.containsKey(e.office()) ? names.get(e.office())
                    : team ? teamNames.computeIfAbsent(e.office(), this::officeName) : null);
            m.put("baseHalalas", e.base());
            m.put("percent", e.percent());
            m.put("amountHalalas", e.amount());
            m.put("paid", e.paid());
            m.put("paidAt", e.paidAt() == null ? null : e.paidAt().toString());
            ledger.add(m);
        }
        out.put("ledger", ledger);

        out.put("payouts", jdbc.sql("""
                        select created_at, amount_halalas, reference, note from affiliate_payouts where affiliate_id = ?
                        order by created_at desc""")
                .param(id).query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("at", iso(rs.getTimestamp(1)));
                    m.put("amountHalalas", rs.getLong(2));
                    m.put("reference", rs.getString(3));
                    if (team) m.put("note", rs.getString(4));
                    return m;
                }).list());
        return out;
    }

    /** Marketers to pick from when creating a code. */
    public List<Map<String, Object>> options() {
        return jdbc.sql("select id, full_name, commission_percent from affiliates where active order by full_name")
                .query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getObject(1, UUID.class));
                    m.put("fullName", rs.getString(2));
                    m.put("commissionPercent", rs.getBigDecimal(3));
                    return m;
                }).list();
    }

    // ---- Helpers ----

    Map<String, Object> require(UUID id) {
        return jdbc.sql("""
                        select id, full_name, email, phone, commission_percent, active, password_hash is not null, last_login_at,
                               created_at, iban, note from affiliates where id = ?""")
                .param(id).query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getObject(1, UUID.class));
                    m.put("fullName", rs.getString(2));
                    m.put("email", rs.getString(3));
                    m.put("phone", rs.getString(4));
                    m.put("commissionPercent", rs.getBigDecimal(5));
                    m.put("active", rs.getBoolean(6));
                    m.put("hasPassword", rs.getBoolean(7));
                    m.put("lastLoginAt", iso(rs.getTimestamp(8)));
                    m.put("createdAt", iso(rs.getTimestamp(9)));
                    m.put("iban", rs.getString(10));
                    m.put("note", rs.getString(11));
                    return m;
                }).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "no_affiliate", "المسوّق غير موجود."));
    }

    private String officeName(UUID office) {
        return TenantContext.call(office, () -> jdbc.sql("select name from offices").query(String.class).optional().orElse(null));
    }

    static BigDecimal rate(BigDecimal r) {
        if (r == null || r.signum() <= 0 || r.compareTo(BigDecimal.valueOf(100)) > 0 || r.stripTrailingZeros().scale() > 2) {
            throw ApiException.badRequest("bad_rate", "«نسبة العمولة»: من 0.01 إلى 100.");
        }
        return r;
    }

    static String iban(String s) {
        String v = s == null ? "" : s.replaceAll("\\s+", "").toUpperCase(java.util.Locale.ROOT);
        if (v.isEmpty()) return null;
        if (!v.matches("SA[0-9A-Z]{22}")) throw ApiException.badRequest("bad_iban", "«الآيبان»: يبدأ بـ SA ثم 22 خانة.");
        return v;
    }

    static String text(String s, int max) {
        if (s == null || s.isBlank()) return null;
        String t = s.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    private static String iso(Timestamp t) {
        return t == null ? null : t.toInstant().toString();
    }
}
