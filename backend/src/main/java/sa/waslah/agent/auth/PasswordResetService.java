package sa.waslah.agent.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import sa.waslah.agent.billing.BillingProperties;
import sa.waslah.agent.common.ApiException;
import sa.waslah.agent.common.Email;
import sa.waslah.agent.common.Mailer;

/**
 * One-time password reset links. Only a SHA-256 of the token is stored. A link the user asks for lasts 1 hour; one the
 * operator creates (to send by WhatsApp) lasts 24 hours. Setting a new password ends every existing session.
 */
@Service
public class PasswordResetService {

    static final Duration USER_TTL = Duration.ofHours(1);
    static final Duration OPERATOR_TTL = Duration.ofHours(24);
    static final int MIN_LENGTH = 8;
    public static final String SUBJECT = "إعادة تعيين كلمة المرور — حداء";

    public record Link(String url, Instant expiresAt) {
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    /** One background thread for reset emails; they are rate-limited to a handful an hour. */
    private final ExecutorService mailThread = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "reset-mail");
        t.setDaemon(true);
        return t;
    });

    private final JdbcClient jdbc;
    private final PasswordEncoder passwords;
    private final Mailer mailer;
    private final BillingProperties billing;
    private final SessionGuard sessions;

    public PasswordResetService(JdbcClient jdbc, PasswordEncoder passwords, Mailer mailer, BillingProperties billing,
                                SessionGuard sessions) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.mailer = mailer;
        this.billing = billing;
        this.sessions = sessions;
    }

    public Link create(UUID userId, boolean byOperator) {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        Instant expires = Instant.now().plus(byOperator ? OPERATOR_TTL : USER_TTL);
        jdbc.sql("insert into password_resets (token_hash, user_id, created_by, expires_at) values (?, ?, ?, ?)")
                .params(sha256(token), userId, byOperator ? "OPERATOR" : "USER", Timestamp.from(expires)).update();
        return new Link(base() + "/reset?token=" + token, expires);
    }

    /**
     * "Forgot password": emails a link if the address belongs to an active user. Says nothing either way, and the email
     * goes out in the background, so the answer takes as long whether or not the address has an account.
     */
    public void requestByEmail(String email) {
        String e = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        record Who(UUID id, String fullName) {
        }
        Optional<Who> user = jdbc.sql("select id, full_name from users where email = ? and disabled_at is null")
                .param(e).query((rs, n) -> new Who(rs.getObject(1, UUID.class), rs.getString(2))).optional();
        if (user.isEmpty()) return;
        Link link = create(user.get().id(), false);
        Email message = new Email("رابط تعيين كلمة مرور جديدة لحسابك في حداء، صالح لمدة ساعة.",
                Email.greet(user.get().fullName()),
                List.of("طلبت إعادة تعيين كلمة المرور لحسابك في حداء. اضغط الزر لتعيين كلمة مرور جديدة. الرابط صالح لمدة ساعة ويُستخدم مرة واحدة."),
                new Email.Button("تعيين كلمة مرور جديدة", link.url()),
                "إذا لم تطلب ذلك فتجاهل هذه الرسالة، ولن يتغير شيء في حسابك.");
        mailThread.execute(() -> mailer.send(e, SUBJECT, message));
    }

    /** Sets the new password if the link is valid, unused and not expired; returns the user's email. */
    public String reset(String token, String newPassword) {
        if (newPassword == null || newPassword.length() < MIN_LENGTH || newPassword.length() > 128) {
            throw ApiException.badRequest("weak_password", "كلمة المرور من 8 أحرف على الأقل.");
        }
        record Claimed(UUID userId, String email) {
        }
        // Using the link and checking it are one statement: two requests with the same link cannot both get through.
        Optional<Claimed> claimed = token == null || token.isBlank() ? Optional.empty() : jdbc.sql("""
                        update password_resets r set used_at = now()
                        from users u
                        where r.token_hash = ? and r.used_at is null and r.expires_at > now()
                          and u.id = r.user_id and u.disabled_at is null
                        returning r.user_id, u.email""")
                .param(sha256(token.trim()))
                .query((rs, n) -> new Claimed(rs.getObject(1, UUID.class), rs.getString(2)))
                .optional();
        if (claimed.isEmpty()) {
            throw ApiException.badRequest("reset_invalid", "الرابط غير صالح أو انتهت صلاحيته. اطلب رابطاً جديداً.");
        }
        UUID userId = claimed.get().userId();
        // The application's clock, like the session's issue time it is compared with (SessionGuard).
        jdbc.sql("update users set password_hash = ?, password_changed_at = ? where id = ?")
                .params(passwords.encode(newPassword), Timestamp.from(Instant.now()), userId).update();
        // Every outstanding link for this user stops working, not only this one.
        jdbc.sql("update password_resets set used_at = now() where user_id = ? and used_at is null").param(userId).update();
        sessions.evictUser(userId);
        return claimed.get().email();
    }

    private String base() {
        String b = billing.portalBaseUrl() == null ? "" : billing.portalBaseUrl().trim();
        return b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
