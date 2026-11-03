package sa.waslah.agent.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import sa.waslah.agent.billing.BillingProperties;
import sa.waslah.agent.billing.PaymentGateways;
import sa.waslah.agent.common.ApiException;
import sa.waslah.agent.common.Email;
import sa.waslah.agent.common.Mailer;

/**
 * Email verification. At signup (and on "أرسل الرمز مرة أخرى") the user gets one email with a 6-digit code and a link;
 * either confirms the address. The code lasts 30 minutes and allows 5 wrong tries; the link lasts 48 hours. Each new
 * email replaces the previous one. Only hashes are stored. A confirmed company (hidaaco.com) address also makes the
 * office internal (Moyasar test payments, see {@link PaymentGateways}).
 */
@Service
public class EmailConfirmationService {

    static final Duration LINK_TTL = Duration.ofHours(48);
    static final Duration CODE_TTL = Duration.ofMinutes(30);
    static final int MAX_ATTEMPTS = 5;
    private static final SecureRandom RANDOM = new SecureRandom();

    public record Issued(String link, String code) {
    }

    private final JdbcClient jdbc;
    private final Mailer mailer;
    private final BillingProperties billing;
    private final PaymentGateways gateways;
    private final SessionGuard sessions;
    private final ExecutorService mailThread = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "verify-mail");
        t.setDaemon(true);
        return t;
    });

    public EmailConfirmationService(JdbcClient jdbc, Mailer mailer, BillingProperties billing, PaymentGateways gateways,
                                    SessionGuard sessions) {
        this.jdbc = jdbc;
        this.mailer = mailer;
        this.billing = billing;
        this.gateways = gateways;
        this.sessions = sessions;
    }

    record Who(UUID id, String email, String fullName, boolean confirmed) {
    }

    private Optional<Who> user(UUID userId) {
        return jdbc.sql("select id, email, full_name, email_confirmed_at is not null from users where id = ? and disabled_at is null")
                .param(userId).query((rs, n) -> new Who(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getBoolean(4)))
                .optional();
    }

    public boolean verified(UUID userId) {
        return user(userId).map(Who::confirmed).orElse(false);
    }

    /** Emails a code and a link unless the address is already confirmed. Returns whether it did. */
    public boolean send(UUID userId) {
        Optional<Who> u = user(userId);
        if (u.isEmpty() || u.get().confirmed()) return false;
        Issued issued = issue(userId);
        List<String> text = new ArrayList<>(List.of(
                "أهلاً بك في حداء. لتأكيد بريدك الإلكتروني اكتب هذا الرمز في صفحة التأكيد، أو اضغط الزر."));
        if (gateways.internalEmail(u.get().email())) {
            text.add("هذا البريد على نطاق حداء، فبعد التأكيد يصبح مكتبك حساباً داخلياً للتجربة: الاشتراك يُدفع ببطاقات الاختبار ولا يُخصم مبلغ حقيقي.");
        }
        Email message = new Email("رمز تأكيد بريدك في حداء: " + issued.code(), Email.greet(u.get().fullName()), text,
                issued.code(), new Email.Button("تأكيد البريد", issued.link()),
                "الرمز صالح لمدة 30 دقيقة والزر لمدة 48 ساعة. إذا لم تنشئ حساباً في حداء فتجاهل هذه الرسالة.");
        String to = u.get().email();
        String subject = "رمز التحقق من بريدك في حداء: " + issued.code();
        mailThread.execute(() -> mailer.send(to, subject, message));
        return true;
    }

    /** A new code and link for the user; earlier ones stop working. */
    Issued issue(UUID userId) {
        jdbc.sql("update email_confirmations set used_at = now() where user_id = ? and used_at is null").param(userId).update();
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        String tokenHash = PasswordResetService.sha256(token);
        Instant now = Instant.now();
        jdbc.sql("""
                        insert into email_confirmations (token_hash, user_id, expires_at, code_hash, code_expires_at)
                        values (?, ?, ?, ?, ?)""")
                .params(tokenHash, userId, Timestamp.from(now.plus(LINK_TTL)), codeHash(tokenHash, code), Timestamp.from(now.plus(CODE_TTL)))
                .update();
        String base = billing.portalBaseUrl() == null ? "" : billing.portalBaseUrl().trim();
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return new Issued(base + "/confirm-email?token=" + token, code);
    }

    /** The link from the email: one use, claimed in one statement. */
    public void confirm(String token) {
        Optional<UUID> user = token == null || token.isBlank() ? Optional.empty() : jdbc.sql("""
                        update email_confirmations c set used_at = now()
                        from users u
                        where c.token_hash = ? and c.used_at is null and c.expires_at > now()
                          and u.id = c.user_id and u.disabled_at is null
                        returning c.user_id""")
                .param(PasswordResetService.sha256(token.trim())).query(UUID.class).optional();
        if (user.isEmpty()) {
            throw ApiException.badRequest("confirm_invalid", "الرابط غير صالح أو انتهت صلاحيته. سجّل الدخول واطلب رمزاً جديداً.");
        }
        markConfirmed(user.get());
    }

    /** The 6-digit code, typed by the signed-in user. */
    public void verifyCode(UUID userId, String code) {
        String c = code == null ? "" : code.replaceAll("[\\s-]", "")
                .replace('٠', '0').replace('١', '1').replace('٢', '2').replace('٣', '3').replace('٤', '4')
                .replace('٥', '5').replace('٦', '6').replace('٧', '7').replace('٨', '8').replace('٩', '9');
        if (!c.matches("\\d{6}")) throw ApiException.badRequest("bad_code", "الرمز 6 أرقام.");
        record Pending(String tokenHash, String codeHash, int attempts) {
        }
        Optional<Pending> p = jdbc.sql("""
                        select token_hash, code_hash, attempts from email_confirmations
                        where user_id = ? and used_at is null and code_hash is not null and code_expires_at > now()
                        order by created_at desc limit 1""")
                .param(userId).query((rs, n) -> new Pending(rs.getString(1), rs.getString(2), rs.getInt(3))).optional();
        if (p.isEmpty()) throw ApiException.badRequest("code_expired", "انتهت صلاحية الرمز. اطلب رمزاً جديداً.");
        if (p.get().attempts() >= MAX_ATTEMPTS) {
            throw ApiException.badRequest("code_locked", "محاولات كثيرة لهذا الرمز. اطلب رمزاً جديداً.");
        }
        boolean right = MessageDigest.isEqual(codeHash(p.get().tokenHash(), c).getBytes(StandardCharsets.UTF_8),
                p.get().codeHash().getBytes(StandardCharsets.UTF_8));
        if (!right) {
            jdbc.sql("update email_confirmations set attempts = attempts + 1 where token_hash = ?").param(p.get().tokenHash()).update();
            int left = MAX_ATTEMPTS - p.get().attempts() - 1;
            throw ApiException.badRequest("code_wrong", left > 0 ? "الرمز غير صحيح. بقي " + left + (left == 1 ? " محاولة." : " محاولات.")
                    : "الرمز غير صحيح. اطلب رمزاً جديداً.");
        }
        int claimed = jdbc.sql("update email_confirmations set used_at = now() where token_hash = ? and used_at is null")
                .param(p.get().tokenHash()).update();
        if (claimed == 1) markConfirmed(userId);
    }

    private void markConfirmed(UUID userId) {
        jdbc.sql("update users set email_confirmed_at = coalesce(email_confirmed_at, now()) where id = ?").param(userId).update();
        jdbc.sql("update email_confirmations set used_at = now() where user_id = ? and used_at is null").param(userId).update();
        sessions.evictUser(userId);
    }

    static String codeHash(String tokenHash, String code) {
        return PasswordResetService.sha256(tokenHash + ":" + code);
    }
}
