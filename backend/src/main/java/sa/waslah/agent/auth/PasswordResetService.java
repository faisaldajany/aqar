package sa.waslah.agent.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import sa.waslah.agent.billing.BillingProperties;
import sa.waslah.agent.common.ApiException;
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

    public record Link(String url, Instant expiresAt) {
    }

    private static final SecureRandom RANDOM = new SecureRandom();

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

    /** "Forgot password": emails a link if the address belongs to an active user. Says nothing either way. */
    public void requestByEmail(String email) {
        String e = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        Optional<UUID> user = jdbc.sql("select id from users where email = ? and disabled_at is null")
                .param(e).query(UUID.class).optional();
        if (user.isEmpty()) return;
        Link link = create(user.get(), false);
        mailer.send(e, "إعادة تعيين كلمة المرور — حداء", """
                مرحباً،

                طلبت إعادة تعيين كلمة المرور لحسابك في حداء. افتح الرابط التالي خلال ساعة لتعيين كلمة مرور جديدة:

                %s

                إذا لم تطلب ذلك فتجاهل هذه الرسالة، ولن يتغير شيء في حسابك.

                فريق حداء
                support@hidaaco.com
                """.formatted(link.url()));
    }

    /** Sets the new password if the link is valid, unused and not expired; returns the user's email. */
    public String reset(String token, String newPassword) {
        if (newPassword == null || newPassword.length() < MIN_LENGTH || newPassword.length() > 128) {
            throw ApiException.badRequest("weak_password", "كلمة المرور من 8 أحرف على الأقل.");
        }
        record Row(UUID userId, Instant expiresAt, Instant usedAt, String email, Instant disabledAt) {
        }
        Optional<Row> row = token == null || token.isBlank() ? Optional.empty() : jdbc.sql("""
                        select r.user_id, r.expires_at, r.used_at, u.email, u.disabled_at
                        from password_resets r join users u on u.id = r.user_id where r.token_hash = ?""")
                .param(sha256(token.trim()))
                .query((rs, n) -> new Row(rs.getObject(1, UUID.class), rs.getTimestamp(2).toInstant(),
                        rs.getTimestamp(3) == null ? null : rs.getTimestamp(3).toInstant(), rs.getString(4),
                        rs.getTimestamp(5) == null ? null : rs.getTimestamp(5).toInstant()))
                .optional();
        if (row.isEmpty() || row.get().usedAt() != null || !row.get().expiresAt().isAfter(Instant.now())
                || row.get().disabledAt() != null) {
            throw ApiException.badRequest("reset_invalid", "الرابط غير صالح أو انتهت صلاحيته. اطلب رابطاً جديداً.");
        }
        UUID userId = row.get().userId();
        jdbc.sql("update users set password_hash = ?, password_changed_at = now() where id = ?")
                .params(passwords.encode(newPassword), userId).update();
        // Every outstanding link for this user stops working, not only this one.
        jdbc.sql("update password_resets set used_at = now() where user_id = ? and used_at is null").param(userId).update();
        sessions.evictUser(userId);
        return row.get().email();
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
