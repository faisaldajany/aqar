package sa.waslah.agent.affiliate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import sa.waslah.agent.billing.PaymentGateways;
import sa.waslah.agent.tenant.TenantContext;

/**
 * Which marketer an office belongs to, and what the marketer earns from it. The rules:
 * <ul>
 *   <li>An office belongs to a marketer while the last code it applied is that marketer's. Applying another code, or
 *       removing this one, ends it.</li>
 *   <li>Each paid subscription or extra-conversations invoice of the office earns the marketer their rate on what the
 *       office paid before VAT, while the code is switched on and the marketer is active. Test payments (the team's
 *       internal offices) and free periods earn nothing.</li>
 *   <li>A refund takes back the same share of the commission (a negative entry), whether or not it was paid out yet.</li>
 * </ul>
 * Called from promo and billing code, under the office's tenant.
 */
@Service
public class AffiliateLedger {

    private final JdbcClient jdbc;

    public AffiliateLedger(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The office applied this code: it now belongs to the code's marketer, or to none for a code without one. */
    public void attribute(UUID promoCodeId, UUID affiliateId) {
        UUID office = TenantContext.require();
        if (affiliateId == null) {
            jdbc.sql("delete from affiliate_clients where office_id = ?").param(office).update();
            return;
        }
        jdbc.sql("""
                        insert into affiliate_clients (office_id, affiliate_id, promo_code_id) values (?, ?, ?)
                        on conflict (office_id) do update set affiliate_id = excluded.affiliate_id,
                            promo_code_id = excluded.promo_code_id,
                            since = case when affiliate_clients.promo_code_id = excluded.promo_code_id
                                         then affiliate_clients.since else now() end""")
                .params(office, affiliateId, promoCodeId).update();
    }

    /** The office removed this code: it no longer belongs to the code's marketer. */
    public void detach(UUID promoCodeId) {
        jdbc.sql("delete from affiliate_clients where office_id = ? and promo_code_id = ?")
                .params(TenantContext.require(), promoCodeId).update();
    }

    /** The team gave the code to another marketer (or none): its current clients go with it, from now on. */
    public void reassign(UUID promoCodeId, UUID affiliateId) {
        if (affiliateId == null) {
            jdbc.sql("delete from affiliate_clients where promo_code_id = ?").param(promoCodeId).update();
        } else {
            jdbc.sql("update affiliate_clients set affiliate_id = ? where promo_code_id = ?").params(affiliateId, promoCodeId).update();
        }
    }

    record Link(UUID affiliateId, UUID promoCodeId, BigDecimal percent) {
    }

    /** The office's marketer, if it still earns from it (code on, marketer active). */
    Optional<Link> link(UUID office) {
        return jdbc.sql("""
                        select ac.affiliate_id, ac.promo_code_id, a.commission_percent
                        from affiliate_clients ac
                        join promo_codes p on p.id = ac.promo_code_id
                        join affiliates a on a.id = ac.affiliate_id
                        where ac.office_id = ? and p.active and a.active""")
                .param(office)
                .query((rs, n) -> new Link(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getBigDecimal(3)))
                .optional();
    }

    /** An invoice of the current office was just paid: the marketer's commission, once per invoice. */
    public void onPaid(UUID invoiceId) {
        UUID office = TenantContext.require();
        record Inv(String kind, String provider, long subtotal) {
        }
        Optional<Inv> inv = jdbc.sql("select kind, provider, subtotal_halalas from invoices where id = ? and status = 'PAID'")
                .param(invoiceId).query((rs, n) -> new Inv(rs.getString(1), rs.getString(2), rs.getLong(3))).optional();
        if (inv.isEmpty() || inv.get().subtotal() <= 0) return;
        if (!"SUBSCRIPTION".equals(inv.get().kind()) && !"OVERAGE".equals(inv.get().kind())) return;
        if (PaymentGateways.TEST.equals(inv.get().provider())) return;
        Optional<Link> link = link(office);
        if (link.isEmpty()) return;
        long base = inv.get().subtotal();
        jdbc.sql("""
                        insert into affiliate_commissions (affiliate_id, office_id, invoice_id, promo_code_id, entry, invoice_kind,
                                                           base_halalas, percent, amount_halalas)
                        values (?, ?, ?, ?, 'EARNED', ?, ?, ?, ?)
                        on conflict (invoice_id) where entry = 'EARNED' do nothing""")
                .params(link.get().affiliateId(), office, invoiceId, link.get().promoCodeId(), inv.get().kind(), base,
                        link.get().percent(), share(base, link.get().percent()))
                .update();
    }

    /**
     * The invoice's refunded total changed: the commission becomes the rate on what the office kept, and the difference
     * is a negative entry. Repeating it changes nothing.
     */
    public void onRefund(UUID invoiceId) {
        UUID office = TenantContext.require();
        record Earned(UUID affiliateId, UUID promoCodeId, BigDecimal percent, String kind) {
        }
        Optional<Earned> earned = jdbc.sql("""
                        select affiliate_id, promo_code_id, percent, invoice_kind from affiliate_commissions
                        where invoice_id = ? and entry = 'EARNED'""")
                .param(invoiceId)
                .query((rs, n) -> new Earned(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getBigDecimal(3), rs.getString(4)))
                .optional();
        if (earned.isEmpty()) return;
        long[] inv = jdbc.sql("select subtotal_halalas, total_halalas, refunded_halalas from invoices where id = ?").param(invoiceId)
                .query((rs, n) -> new long[]{rs.getLong(1), rs.getLong(2), rs.getLong(3)}).single();
        long subtotal = inv[0], total = inv[1], refunded = Math.min(inv[2], inv[1]);
        long keptBase = total <= 0 ? 0 : BigDecimal.valueOf(subtotal).multiply(BigDecimal.valueOf(total - refunded))
                .divide(BigDecimal.valueOf(total), 0, RoundingMode.HALF_UP).longValueExact();
        long[] sums = jdbc.sql("select coalesce(sum(base_halalas), 0), coalesce(sum(amount_halalas), 0) from affiliate_commissions where invoice_id = ?")
                .param(invoiceId).query((rs, n) -> new long[]{rs.getLong(1), rs.getLong(2)}).single();
        long delta = share(keptBase, earned.get().percent()) - sums[1];
        if (delta >= 0) return;
        jdbc.sql("""
                        insert into affiliate_commissions (affiliate_id, office_id, invoice_id, promo_code_id, entry, invoice_kind,
                                                           base_halalas, percent, amount_halalas)
                        values (?, ?, ?, ?, 'REFUND', ?, ?, ?, ?)""")
                .params(earned.get().affiliateId(), office, invoiceId, earned.get().promoCodeId(), earned.get().kind(),
                        keptBase - sums[0], earned.get().percent(), delta)
                .update();
    }

    static long share(long baseHalalas, BigDecimal percent) {
        return BigDecimal.valueOf(baseHalalas).multiply(percent).divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP).longValueExact();
    }
}
