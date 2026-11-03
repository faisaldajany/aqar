package sa.waslah.agent.auth;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
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
 * Confirms that a user owns a company (hidaaco.com) email address, by a one-time link sent to it. Only a confirmed
 * company address makes an office internal (Moyasar test payments, see {@link PaymentGateways}). Links last 48 hours;
 * only a SHA-256 of the token is stored.
 */
@Service
public class EmailConfirmationService {

    static final Duration TTL = Duration.ofHours(48);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcClient jdbc;
    private final Mailer mailer;
    private final BillingProperties billing;
    private final PaymentGateways gateways;
    private final ExecutorService mailThread = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "confirm-mail");
        t.setDaemon(true);
        return t;
    });

    public EmailConfirmationService(JdbcClient jdbc, Mailer mailer, BillingProperties billing, PaymentGateways gateways) {
        this.jdbc = jdbc;
        this.mailer = mailer;
        this.billing = billing;
        this.gateways = gateways;
    }

    record Who(UUID id, String email, String fullName, boolean confirmed) {
    }

    private Optional<Who> user(UUID userId) {
        return jdbc.sql("select id, email, full_name, email_confirmed_at is not null from users where id = ? and disabled_at is null")
                .param(userId).query((rs, n) -> new Who(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getBoolean(4)))
                .optional();
    }

    /** Emails a confirmation link if the user's address is on the company domain and not confirmed yet. Returns whether it did. */
    public boolean sendIfInternal(UUID userId) {
        Optional<Who> u = user(userId);
        if (u.isEmpty() || u.get().confirmed() || !gateways.internalEmail(u.get().email())) return false;
        String link = issue(userId);
        Email message = new Email("أكّد بريدك في حداء لتفعيل الدفع التجريبي لحسابات الفريق.",
                Email.greet(u.get().fullName()),
                List.of("هذا البريد على نطاق حداء، فحساب مكتبك حساب داخلي للتجربة: الاشتراك يُدفع فيه ببطاقات الاختبار ولا يُخصم مبلغ حقيقي.",
                        "أكّد أن البريد بريدك ليُفعَّل ذلك. الرابط صالح لمدة 48 ساعة."),
                new Email.Button("تأكيد البريد", link),
                "إذا لم تنشئ حساباً في حداء فتجاهل هذه الرسالة.");
        String to = u.get().email();
        mailThread.execute(() -> mailer.send(to, "تأكيد البريد — حداء", message));
        return true;
    }

    /** Creates a link for the user and returns it. */
    String issue(UUID userId) {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        jdbc.sql("insert into email_confirmations (token_hash, user_id, expires_at) values (?, ?, ?)")
                .params(PasswordResetService.sha256(token), userId, Timestamp.from(Instant.now().plus(TTL))).update();
        String base = billing.portalBaseUrl() == null ? "" : billing.portalBaseUrl().trim();
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base + "/confirm-email?token=" + token;
    }

    /** Uses a link: marks the address confirmed. One use; claimed in one statement. */
    public void confirm(String token) {
        Optional<UUID> user = token == null || token.isBlank() ? Optional.empty() : jdbc.sql("""
                        update email_confirmations c set used_at = now()
                        from users u
                        where c.token_hash = ? and c.used_at is null and c.expires_at > now()
                          and u.id = c.user_id and u.disabled_at is null
                        returning c.user_id""")
                .param(PasswordResetService.sha256(token.trim())).query(UUID.class).optional();
        if (user.isEmpty()) {
            throw ApiException.badRequest("confirm_invalid", "الرابط غير صالح أو انتهت صلاحيته. اطلب رابطاً جديداً من صفحة الاشتراك.");
        }
        jdbc.sql("update users set email_confirmed_at = coalesce(email_confirmed_at, now()) where id = ?").param(user.get()).update();
    }
}
