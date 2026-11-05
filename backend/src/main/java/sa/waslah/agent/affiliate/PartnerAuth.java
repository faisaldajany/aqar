package sa.waslah.agent.affiliate;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;
import sa.waslah.agent.billing.BillingProperties;
import sa.waslah.agent.common.ApiException;
import sa.waslah.agent.common.ClientIp;
import sa.waslah.agent.common.RateLimiter;
import sa.waslah.agent.config.WaslahProperties;

/**
 * Marketers sign in with their email and a password they set from the team's invitation link. The session is a
 * 12-hour cookie sent only to /api/partner, separate from offices' sessions. It ends early when the marketer is
 * switched off or their password changes. Wrong passwords are limited per address and email.
 */
@Component
public class PartnerAuth {

    public static final String COOKIE = "hidaa_partner";
    static final Duration TTL = Duration.ofHours(12);
    static final String SCOPE = "partner";
    static final String ISSUED_MS = "iat_ms";
    static final Duration INVITE_TTL = Duration.ofDays(7);
    static final Duration RESET_TTL = Duration.ofHours(1);
    static final int MIN_PASSWORD = 8;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcClient jdbc;
    private final JwtEncoder encoder;
    private final JwtDecoder decoder;
    private final PasswordEncoder passwords;
    private final WaslahProperties props;
    private final BillingProperties billing;
    private final RateLimiter limits;
    /** Compared against when the email is unknown, so a wrong email takes as long as a wrong password. */
    private final String dummyHash;

    public PartnerAuth(JdbcClient jdbc, JwtEncoder encoder, JwtDecoder decoder, PasswordEncoder passwords, WaslahProperties props,
                       BillingProperties billing, RateLimiter limits) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.decoder = decoder;
        this.passwords = passwords;
        this.props = props;
        this.billing = billing;
        this.limits = limits;
        this.dummyHash = passwords.encode("not-a-real-password-" + UUID.randomUUID());
    }

    public static String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    /** The signed-in marketer, or 401. */
    public UUID require(HttpServletRequest r) {
        return current(r).orElseThrow(() -> ApiException.unauthorized("partner_signed_out", "سجّل الدخول إلى بوابة المسوّقين."));
    }

    public Optional<UUID> current(HttpServletRequest r) {
        Cookie[] cookies = r.getCookies();
        if (cookies == null) return Optional.empty();
        for (Cookie c : cookies) {
            if (!COOKIE.equals(c.getName()) || c.getValue().isBlank()) continue;
            try {
                Jwt jwt = decoder.decode(c.getValue());
                if (!SCOPE.equals(jwt.getClaimAsString("scope"))) continue;
                UUID id = UUID.fromString(jwt.getSubject());
                Object ms = jwt.getClaims().get(ISSUED_MS);
                Instant issued = ms instanceof Number n ? Instant.ofEpochMilli(n.longValue()) : jwt.getIssuedAt();
                boolean ok = jdbc.sql("""
                                select active and password_hash is not null and (password_changed_at is null or password_changed_at <= ?)
                                from affiliates where id = ?""")
                        .params(Timestamp.from(issued), id).query(Boolean.class).optional().orElse(false);
                if (ok) return Optional.of(id);
            } catch (JwtException | IllegalArgumentException e) {
                // expired, forged or malformed: signed out
            }
        }
        return Optional.empty();
    }

    /** Checks the email and password; returns the marketer's id. */
    public UUID login(HttpServletRequest r, String email, String password) {
        String e = normalizeEmail(email);
        String counter = "partner-login:" + ClientIp.hash(r, "partner") + ":" + e;
        if (!limits.tryAcquire(counter, 10, Duration.ofMinutes(15))) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "too_many_attempts", "محاولات كثيرة. حاول بعد 15 دقيقة.");
        }
        record Row(UUID id, String hash, boolean active) {
        }
        Optional<Row> row = jdbc.sql("select id, password_hash, active from affiliates where email = ?").param(e)
                .query((rs, n) -> new Row(rs.getObject(1, UUID.class), rs.getString(2), rs.getBoolean(3))).optional();
        String hash = row.map(Row::hash).orElse(null);
        boolean matches = passwords.matches(password == null ? "" : password, hash == null ? dummyHash : hash);
        if (row.isEmpty() || hash == null || !matches) {
            throw ApiException.unauthorized("bad_credentials", "البريد أو كلمة المرور غير صحيحة.");
        }
        if (!row.get().active()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "partner_disabled", "حسابك في بوابة المسوّقين موقوف. تواصل مع فريق حداء.");
        }
        limits.reset(counter);
        jdbc.sql("update affiliates set last_login_at = now() where id = ?").param(row.get().id()).update();
        return row.get().id();
    }

    public ResponseCookie cookie(UUID affiliateId) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer("waslah").subject(affiliateId.toString()).issuedAt(now)
                .expiresAt(now.plus(TTL)).claim("scope", SCOPE).claim(ISSUED_MS, now.toEpochMilli()).build();
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        return ResponseCookie.from(COOKIE, token).httpOnly(true).secure(props.cookieSecure()).sameSite("Lax")
                .path("/api/partner").maxAge(TTL).build();
    }

    public ResponseCookie clearCookie() {
        return ResponseCookie.from(COOKIE, "").httpOnly(true).secure(props.cookieSecure()).sameSite("Lax")
                .path("/api/partner").maxAge(0).build();
    }

    public record Link(String url, Instant expiresAt) {
    }

    /** A one-time link to set the password: 7 days for an invitation, 1 hour for a forgotten password. */
    public Link link(UUID affiliateId, boolean invitation) {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        Instant expires = Instant.now().plus(invitation ? INVITE_TTL : RESET_TTL);
        jdbc.sql("insert into affiliate_tokens (token_hash, affiliate_id, expires_at) values (?, ?, ?)")
                .params(sha256(token), affiliateId, Timestamp.from(expires)).update();
        String base = billing.portalBaseUrl() == null ? "" : billing.portalBaseUrl().trim();
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return new Link(base + "/partner/set-password?token=" + token, expires);
    }

    /** Sets the password from a link, once. Every other session ends. Returns the marketer's id. */
    public UUID setPassword(String token, String password) {
        if (password == null || password.length() < MIN_PASSWORD || password.length() > 200) {
            throw ApiException.badRequest("weak_password", "«كلمة المرور»: 8 أحرف على الأقل.");
        }
        Optional<UUID> id = jdbc.sql("""
                        update affiliate_tokens set used_at = now()
                        where token_hash = ? and used_at is null and expires_at > now() returning affiliate_id""")
                .param(sha256(token == null ? "" : token)).query(UUID.class).optional();
        if (id.isEmpty()) {
            throw ApiException.badRequest("bad_link", "الرابط غير صالح أو انتهت صلاحيته. اطلب رابطاً جديداً.");
        }
        boolean active = jdbc.sql("select active from affiliates where id = ?").param(id.get()).query(Boolean.class).single();
        if (!active) throw new ApiException(HttpStatus.FORBIDDEN, "partner_disabled", "حسابك في بوابة المسوّقين موقوف. تواصل مع فريق حداء.");
        // The app's clock, not the database's, so the session issued next is never "before" the change.
        jdbc.sql("update affiliates set password_hash = ?, password_changed_at = ?, last_login_at = now() where id = ?")
                .params(passwords.encode(password), Timestamp.from(Instant.now().minusMillis(1)), id.get()).update();
        // Other links for this marketer stop working.
        jdbc.sql("update affiliate_tokens set used_at = now() where affiliate_id = ? and used_at is null").param(id.get()).update();
        return id.get();
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
