package sa.waslah.agent.billing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import sa.waslah.agent.affiliate.AffiliateLedger;
import sa.waslah.agent.common.ApiException;
import sa.waslah.agent.tenant.TenantContext;

/**
 * Promo codes. How a code works:
 * <ul>
 *   <li>PERCENT or FIXED: a discount on subscription invoices (never overage), for the first {@code durationPeriods}
 *       paid invoices (null = every renewal). One invoice is one period: a month on monthly, a year on annual.
 *       VAT is charged on the discounted amount. A 100% discount activates the period without a payment page.</li>
 *   <li>TRIAL_DAYS: extra days on the free trial, for offices that have not subscribed yet.</li>
 *   <li>A code can be limited to plans and cycles, a date window, a total number of uses, and new customers
 *       (no paid subscription yet). Each office can use each code once, and holds one discount at a time.</li>
 *   <li>A use is taken from the code's limit when the code is applied, atomically, so a capped code never goes over
 *       its cap; removing a discount before any invoice used it gives the use back. A discount the office has started
 *       keeps going for its duration even if the code later expires or is switched off.</li>
 *   <li>A code can belong to a marketer: the office that applies it becomes the marketer's client, who earns on its
 *       payments while the code is on (see {@link AffiliateLedger}).</li>
 * </ul>
 * Office-side methods run under the office's tenant.
 */
@Service
public class PromoService {

    static final ZoneId RIYADH = ZoneId.of("Asia/Riyadh");

    public record Promo(UUID id, String code, String kind, Integer percentOff, Long amountOffHalalas, Integer trialDays,
                        Integer durationPeriods, List<String> plans, List<String> cycles, boolean newCustomersOnly,
                        Integer maxRedemptions, int redemptions, Instant startsAt, Instant expiresAt, boolean active,
                        String note, Instant createdAt, UUID affiliateId) {

        public boolean discount() {
            return "PERCENT".equals(kind) || "FIXED".equals(kind);
        }

        /** Arabic one-liner for the portal and the admin page, e.g. "خصم 30٪ لأول 3 دفعات على باقة النمو". */
        public String summary() {
            String what = switch (kind) {
                case "PERCENT" -> "خصم " + percentOff + "٪";
                case "FIXED" -> "خصم " + BillingService.sar(amountOffHalalas) + " ريال";
                default -> trialDays + " يوماً إضافية في التجربة المجانية";
            };
            if (discount()) {
                what += durationPeriods == null ? " على كل الدفعات"
                        : durationPeriods == 1 ? " على أول دفعة" : " لأول " + durationPeriods + " دفعات";
                if (!plans.isEmpty()) {
                    what += " على باقة " + String.join(" أو ", plans.stream()
                            .map(p -> PlanLimits.Plan.valueOf(p).arabicName).toList());
                }
                if (cycles.size() == 1) what += "ANNUAL".equals(cycles.get(0)) ? " (الدفع السنوي)" : " (الدفع الشهري)";
            }
            return what;
        }
    }

    /** The office's discount and how many invoices it has been used on. */
    public record Applied(Promo promo, int periodsUsed) {

        public Integer remainingPeriods() {
            return promo.durationPeriods() == null ? null : Math.max(0, promo.durationPeriods() - periodsUsed);
        }

        public boolean usedUp() {
            return promo.durationPeriods() != null && periodsUsed >= promo.durationPeriods();
        }
    }

    /** @param affiliateId the marketer the code belongs to (they earn from the offices that use it), or null */
    public record NewPromo(String code, String kind, Integer percentOff, BigDecimal amountOffSar, Integer trialDays,
                           Integer durationPeriods, List<String> plans, List<String> cycles, Boolean newCustomersOnly,
                           Integer maxRedemptions, LocalDate startsOn, LocalDate expiresOn, String note, UUID affiliateId) {
    }

    private static final List<String> FIELDS = List.of("id", "code", "kind", "percent_off", "amount_off_halalas",
            "trial_days", "duration_periods", "plans", "cycles", "new_customers_only", "max_redemptions", "redemptions",
            "starts_at", "expires_at", "active", "note", "created_at", "affiliate_id");
    private static final String COLUMNS = String.join(", ", FIELDS);
    private static final String P_COLUMNS = String.join(", ", FIELDS.stream().map(f -> "p." + f).toList());

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final AffiliateLedger affiliates;

    public PromoService(JdbcClient jdbc, TransactionTemplate tx, AffiliateLedger affiliates) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.affiliates = affiliates;
    }

    /** Uppercase, no spaces: " launch30 " is LAUNCH30. */
    public static String normalize(String code) {
        return code == null ? "" : code.trim().replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }

    public Optional<Promo> find(String code) {
        String c = normalize(code);
        if (c.isEmpty()) return Optional.empty();
        return jdbc.sql("select " + COLUMNS + " from promo_codes where code = ?").param(c).query(PromoService::map).optional();
    }

    /** Checks that apply to everyone (exists, switched on, in its date window, uses left). Throws with an Arabic message. */
    public Promo usable(String code) {
        Promo p = find(code).filter(Promo::active)
                .orElseThrow(() -> ApiException.badRequest("promo_invalid", "الكود غير صحيح أو غير فعّال."));
        Instant now = Instant.now();
        if (p.startsAt() != null && p.startsAt().isAfter(now)) {
            throw ApiException.badRequest("promo_not_started", "هذا الكود لم يبدأ بعد.");
        }
        if (p.expiresAt() != null && !p.expiresAt().isAfter(now)) {
            throw ApiException.badRequest("promo_expired", "انتهت صلاحية هذا الكود.");
        }
        if (p.maxRedemptions() != null && p.redemptions() >= p.maxRedemptions()) {
            throw ApiException.badRequest("promo_used_up", "انتهى عدد مرات استخدام هذا الكود.");
        }
        return p;
    }

    /**
     * Applies a code to the current office, serialized per office. A use is taken from the code's limit here, in the
     * same transaction as the office's claim, so a capped code never goes over its cap and two parallel requests
     * cannot both use it; an office that removes a discount before any invoice used it gives the use back.
     * Trial codes take effect now; discounts wait for the next subscription invoice.
     */
    public Promo apply(String code) {
        // The code the office already holds: nothing to do (even if it has since reached its cap with this office's use).
        Optional<Applied> held = current();
        if (held.isPresent() && held.get().promo().code().equals(normalize(code))) return held.get().promo();
        Promo p = usable(code);
        UUID office = TenantContext.require();
        return tx.execute(status -> {
            lockOffice();
            boolean customer = hasPaidSubscription();
            if (p.newCustomersOnly() && customer) {
                throw ApiException.badRequest("promo_new_only", "هذا الكود للمشتركين الجدد فقط.");
            }

            if ("TRIAL_DAYS".equals(p.kind())) {
                String st = jdbc.sql("select subscription_status from offices").query(String.class).single();
                if (customer || !("TRIAL".equals(st) || "EXPIRED".equals(st))) {
                    throw ApiException.badRequest("promo_trial_only", "أيام التجربة الإضافية للحسابات التي لم تشترك بعد.");
                }
                claim(office, p);
                // A marketer's trial code makes the office theirs; a plain one leaves the office's marketer as it is.
                if (p.affiliateId() != null) affiliates.attribute(p.id(), p.affiliateId());
                jdbc.sql("""
                        update offices set subscription_status = 'TRIAL',
                               trial_ends_at = greatest(trial_ends_at, now()) + make_interval(days => ?)""")
                        .param(p.trialDays()).update();
                return p;
            }

            Optional<Applied> current = current();
            if (current.isPresent() && current.get().promo().id().equals(p.id())) {
                affiliates.attribute(p.id(), p.affiliateId());
                return p;
            }
            if (current.isPresent() && current.get().periodsUsed() > 0 && !current.get().usedUp()) {
                throw ApiException.conflict("promo_active", "لديك خصم فعّال حالياً (" + current.get().promo().code()
                        + "). يمكنك استخدام كود جديد بعد انتهائه.");
            }
            boolean claimedBefore = jdbc.sql("select exists(select 1 from promo_redemptions where promo_code_id = ?)")
                    .param(p.id()).query(Boolean.class).single();
            if (claimedBefore) {
                // Claimed earlier and kept (e.g. replaced after use): only if no paid invoice ever carried it.
                if (usedOnPaidInvoice(p.id())) throw ApiException.conflict("promo_already_used", "استخدمت هذا الكود من قبل.");
            } else {
                claim(office, p);
            }
            current.ifPresent(this::releaseIfUnused);
            jdbc.sql("update offices set promo_code_id = ?, promo_periods_used = 0").param(p.id()).update();
            affiliates.attribute(p.id(), p.affiliateId());
            return p;
        });
    }

    /** Removes the office's discount; if no invoice used it yet, its use goes back to the code. */
    public void remove() {
        tx.executeWithoutResult(status -> {
            lockOffice();
            current().ifPresent(a -> {
                releaseIfUnused(a);
                affiliates.detach(a.promo().id());
            });
            jdbc.sql("update offices set promo_code_id = null, promo_periods_used = 0").update();
        });
    }

    public Optional<Applied> current() {
        return jdbc.sql("select " + P_COLUMNS + ", o.promo_periods_used from offices o join promo_codes p on p.id = o.promo_code_id")
                .query((rs, n) -> new Applied(map(rs, n), rs.getInt("promo_periods_used")))
                .optional();
    }

    /** The discount on one subscription invoice, in halalas; 0 when the code does not apply to this plan, cycle or time. */
    public long discountHalalas(Applied a, PlanLimits.Plan plan, String cycle, long grossHalalas) {
        Promo p = a.promo();
        if (!p.discount() || a.usedUp() || grossHalalas <= 0) return 0;
        if (!p.plans().isEmpty() && !p.plans().contains(plan.name())) return 0;
        if (!p.cycles().isEmpty() && !p.cycles().contains(cycle)) return 0;
        if (a.periodsUsed() == 0) {
            // Not started yet: the code must still be on and in its window (its use was already taken when applied).
            // Once started, it runs its course.
            Instant now = Instant.now();
            if (!p.active() || (p.expiresAt() != null && !p.expiresAt().isAfter(now))
                    || (p.startsAt() != null && p.startsAt().isAfter(now))) {
                return 0;
            }
        }
        long off = "PERCENT".equals(p.kind())
                ? BigDecimal.valueOf(grossHalalas).multiply(BigDecimal.valueOf(p.percentOff()))
                        .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP).longValueExact()
                : p.amountOffHalalas();
        return Math.min(off, grossHalalas);
    }

    /** A discounted subscription invoice was paid: one more period of the office's discount is used. */
    public void onPaid(UUID promoCodeId) {
        if (promoCodeId == null) return;
        jdbc.sql("update offices set promo_periods_used = promo_periods_used + 1 where promo_code_id = ?")
                .param(promoCodeId).update();
    }

    /** The office's claim and the code's count, both or neither (the caller's transaction rolls back on a throw). */
    private void claim(UUID office, Promo p) {
        int added = jdbc.sql("insert into promo_redemptions (office_id, promo_code_id) values (?, ?) on conflict do nothing")
                .params(office, p.id()).update();
        if (added == 0) throw ApiException.conflict("promo_already_used", "استخدمت هذا الكود من قبل.");
        int counted = jdbc.sql("""
                        update promo_codes set redemptions = redemptions + 1
                        where id = ? and (max_redemptions is null or redemptions < max_redemptions)""")
                .param(p.id()).update();
        if (counted == 0) throw ApiException.badRequest("promo_used_up", "انتهى عدد مرات استخدام هذا الكود.");
    }

    private void releaseIfUnused(Applied a) {
        if (a.periodsUsed() > 0 || usedOnPaidInvoice(a.promo().id())) return;
        // Its unpaid invoices go too, so the discount is not paid for after its use was given back.
        jdbc.sql("update invoices set status = 'VOID' where promo_code_id = ? and status = 'DUE'").param(a.promo().id()).update();
        int removed = jdbc.sql("delete from promo_redemptions where promo_code_id = ?").param(a.promo().id()).update();
        if (removed == 1) {
            jdbc.sql("update promo_codes set redemptions = greatest(redemptions - 1, 0) where id = ?").param(a.promo().id()).update();
        }
    }

    private boolean usedOnPaidInvoice(UUID promoId) {
        return jdbc.sql("select exists(select 1 from invoices where promo_code_id = ? and status = 'PAID')")
                .param(promoId).query(Boolean.class).single();
    }

    /** Any paid subscription (a 100% promo period included): the office is no longer a new customer. */
    private boolean hasPaidSubscription() {
        return jdbc.sql("select exists(select 1 from invoices where kind = 'SUBSCRIPTION' and status = 'PAID')")
                .query(Boolean.class).single();
    }

    /** Serializes promo and checkout changes for the current office (its offices row, under RLS). */
    void lockOffice() {
        jdbc.sql("select id from offices for update").query(UUID.class).optional();
    }

    // ---- Operator side (not tenant data) ----

    public List<Promo> list() {
        return jdbc.sql("select " + COLUMNS + " from promo_codes order by created_at desc").query(PromoService::map).list();
    }

    /** A marketer's codes, newest first. */
    public List<Promo> forAffiliate(UUID affiliateId) {
        return jdbc.sql("select " + COLUMNS + " from promo_codes where affiliate_id = ? order by created_at desc")
                .param(affiliateId).query(PromoService::map).list();
    }

    public Promo create(NewPromo n) {
        String code = normalize(n.code());
        if (!code.matches("[A-Z0-9_-]{3,32}")) {
            throw ApiException.badRequest("bad_code", "الكود من 3 إلى 32 حرفاً إنجليزياً أو رقماً (بدون مسافات).");
        }
        String kind = n.kind() == null ? "" : n.kind();
        Integer percent = null, trial = null;
        Long amount = null;
        switch (kind) {
            case "PERCENT" -> {
                if (n.percentOff() == null || n.percentOff() < 1 || n.percentOff() > 100) {
                    throw ApiException.badRequest("bad_percent", "نسبة الخصم من 1 إلى 100.");
                }
                percent = n.percentOff();
            }
            case "FIXED" -> {
                if (n.amountOffSar() == null || n.amountOffSar().signum() <= 0) {
                    throw ApiException.badRequest("bad_amount", "اكتب مبلغ الخصم بالريال.");
                }
                amount = BillingService.halalas(n.amountOffSar());
            }
            case "TRIAL_DAYS" -> {
                if (n.trialDays() == null || n.trialDays() < 1 || n.trialDays() > 180) {
                    throw ApiException.badRequest("bad_days", "أيام التجربة الإضافية من 1 إلى 180.");
                }
                trial = n.trialDays();
            }
            default -> throw ApiException.badRequest("bad_kind", "اختر نوع الكود.");
        }
        Integer duration = "TRIAL_DAYS".equals(kind) ? null : n.durationPeriods();
        if (duration != null && (duration < 1 || duration > 36)) {
            throw ApiException.badRequest("bad_duration", "عدد الدفعات المخصومة من 1 إلى 36، أو اتركه فارغاً لكل الدفعات.");
        }
        List<String> plans = n.plans() == null ? List.of() : n.plans().stream().filter(p -> p != null && !p.isBlank()).toList();
        for (String p : plans) {
            try {
                PlanLimits.Plan.valueOf(p);
            } catch (IllegalArgumentException e) {
                throw ApiException.badRequest("bad_plan", "باقة غير معروفة: " + p);
            }
        }
        List<String> cycles = n.cycles() == null ? List.of() : n.cycles().stream().filter(c -> c != null && !c.isBlank()).toList();
        if (cycles.stream().anyMatch(c -> !"MONTHLY".equals(c) && !"ANNUAL".equals(c))) {
            throw ApiException.badRequest("bad_cycle", "طريقة الدفع شهري أو سنوي.");
        }
        if (n.maxRedemptions() != null && n.maxRedemptions() < 1) {
            throw ApiException.badRequest("bad_max", "عدد الاستخدامات 1 أو أكثر، أو اتركه فارغاً.");
        }
        Instant starts = n.startsOn() == null ? null : n.startsOn().atStartOfDay(RIYADH).toInstant();
        Instant expires = n.expiresOn() == null ? null : n.expiresOn().plusDays(1).atStartOfDay(RIYADH).toInstant();
        if (starts != null && expires != null && !expires.isAfter(starts)) {
            throw ApiException.badRequest("bad_dates", "تاريخ الانتهاء بعد تاريخ البداية.");
        }
        if (find(code).isPresent()) throw ApiException.conflict("code_taken", "هذا الكود موجود.");
        if (n.affiliateId() != null && !jdbc.sql("select exists(select 1 from affiliates where id = ? and active)")
                .param(n.affiliateId()).query(Boolean.class).single()) {
            throw ApiException.badRequest("bad_affiliate", "المسوّق غير موجود أو موقوف.");
        }
        UUID id = jdbc.sql("""
                        insert into promo_codes (code, kind, percent_off, amount_off_halalas, trial_days, duration_periods, plans,
                                                 cycles, new_customers_only, max_redemptions, starts_at, expires_at, note, affiliate_id)
                        values (?, ?, ?, ?, ?, ?, string_to_array(?, ','), string_to_array(?, ','), ?, ?, ?, ?, ?, ?) returning id""")
                .params(code, kind, percent, amount, trial, duration, String.join(",", plans), String.join(",", cycles),
                        n.newCustomersOnly() == null || n.newCustomersOnly(), n.maxRedemptions(),
                        starts == null ? null : Timestamp.from(starts), expires == null ? null : Timestamp.from(expires),
                        n.note() == null || n.note().isBlank() ? null : n.note().trim(), n.affiliateId())
                .query(UUID.class).single();
        return jdbc.sql("select " + COLUMNS + " from promo_codes where id = ?").param(id).query(PromoService::map).single();
    }

    public void setActive(UUID id, boolean active) {
        int n = jdbc.sql("update promo_codes set active = ? where id = ?").params(active, id).update();
        if (n == 0) throw new ApiException(HttpStatus.NOT_FOUND, "no_promo", "الكود غير موجود.");
    }

    static Promo map(ResultSet rs, int n) throws SQLException {
        return new Promo(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("kind"),
                (Integer) rs.getObject("percent_off"), rs.getObject("amount_off_halalas") == null ? null : rs.getLong("amount_off_halalas"),
                (Integer) rs.getObject("trial_days"), (Integer) rs.getObject("duration_periods"),
                strings(rs.getArray("plans")), strings(rs.getArray("cycles")), rs.getBoolean("new_customers_only"),
                (Integer) rs.getObject("max_redemptions"), rs.getInt("redemptions"), instant(rs.getTimestamp("starts_at")),
                instant(rs.getTimestamp("expires_at")), rs.getBoolean("active"), rs.getString("note"),
                rs.getTimestamp("created_at").toInstant(), rs.getObject("affiliate_id", UUID.class));
    }

    private static List<String> strings(Array a) throws SQLException {
        if (a == null) return List.of();
        Object raw = a.getArray();
        if (!(raw instanceof String[] arr)) return List.of();
        return new ArrayList<>(Arrays.stream(arr).filter(s -> s != null && !s.isBlank()).toList());
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
