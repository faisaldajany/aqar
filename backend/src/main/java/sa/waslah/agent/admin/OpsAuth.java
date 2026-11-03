package sa.waslah.agent.admin;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;
import sa.waslah.agent.common.ApiException;
import sa.waslah.agent.common.ClientIp;
import sa.waslah.agent.common.RateLimiter;
import sa.waslah.agent.config.WaslahProperties;

/**
 * Who may use the operator console: whoever holds the admin key (Secret Manager "admin-token"). Signing in with the
 * key gives an 8-hour session cookie that is only sent to /api/ops and ends when the key is changed. Scripts can send
 * the key as X-Admin-Token instead. Wrong keys are limited to 5 per 15 minutes per address, by the form and the header
 * together. With no key configured (or one shorter than 24 characters) the console does not exist: every call is 404.
 */
@Component
public class OpsAuth {

    public static final String COOKIE = "hidaa_ops";
    static final Duration TTL = Duration.ofHours(8);
    static final String SCOPE = "ops";
    static final int MIN_KEY_LENGTH = 24;
    static final int MAX_WRONG = 5;
    static final Duration WRONG_WINDOW = Duration.ofMinutes(15);

    public enum Check { OK, WRONG, LIMITED }

    private final String key;
    private final String keyId;
    private final JwtEncoder encoder;
    private final JwtDecoder decoder;
    private final WaslahProperties props;
    private final RateLimiter limits;

    public OpsAuth(@Value("${waslah.admin-token:}") String key, JwtEncoder encoder, JwtDecoder decoder, WaslahProperties props,
                   RateLimiter limits) {
        this.key = key == null ? "" : key.trim();
        this.encoder = encoder;
        this.decoder = decoder;
        this.props = props;
        this.limits = limits;
        this.keyId = keyId(this.key, props.jwtSecret());
    }

    public boolean enabled() {
        return key.length() >= MIN_KEY_LENGTH;
    }

    /** Checks a key sent by this caller, counting wrong ones. LIMITED means too many wrong keys: not even checked. */
    public Check check(HttpServletRequest r, String given) {
        String counter = "ops-key:" + ClientIp.hash(r, "ops");
        if (!limits.tryAcquire(counter, MAX_WRONG, WRONG_WINDOW)) return Check.LIMITED;
        if (!keyMatches(given)) return Check.WRONG;
        limits.reset(counter);
        return Check.OK;
    }

    public boolean keyMatches(String given) {
        return enabled() && given != null
                && MessageDigest.isEqual(key.getBytes(StandardCharsets.UTF_8), given.trim().getBytes(StandardCharsets.UTF_8));
    }

    public ResponseCookie sessionCookie() {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer("waslah").subject("operator").issuedAt(now)
                .expiresAt(now.plus(TTL)).claim("scope", SCOPE).claim(KEY_ID, keyId).build();
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        return ResponseCookie.from(COOKIE, token).httpOnly(true).secure(props.cookieSecure()).sameSite("Strict")
                .path("/api/ops").maxAge(TTL).build();
    }

    public ResponseCookie clearCookie() {
        return ResponseCookie.from(COOKIE, "").httpOnly(true).secure(props.cookieSecure()).sameSite("Strict")
                .path("/api/ops").maxAge(0).build();
    }

    /** Throws 404 unless the request carries a valid operator session or the key. */
    public void require(HttpServletRequest r) {
        if (!enabled()) throw notFound();
        if (validSession(r)) return;
        String header = r.getHeader("X-Admin-Token");
        if (header != null && check(r, header) == Check.OK) return;
        throw notFound();
    }

    private boolean validSession(HttpServletRequest r) {
        Cookie[] cookies = r.getCookies();
        if (cookies == null) return false;
        for (Cookie c : cookies) {
            if (!COOKIE.equals(c.getName()) || c.getValue().isBlank()) continue;
            try {
                Jwt jwt = decoder.decode(c.getValue());
                if (SCOPE.equals(jwt.getClaimAsString("scope")) && "operator".equals(jwt.getSubject())
                        && keyId.equals(jwt.getClaimAsString(KEY_ID))) {
                    return true;
                }
            } catch (JwtException e) {
                // expired or forged: fall through
            }
        }
        return false;
    }

    static final String KEY_ID = "kh";

    /**
     * Which key a session was opened with, so that changing the key ends every session. An HMAC under the JWT secret:
     * the cookie says nothing about the key itself.
     */
    static String keyId(String key, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(("ops-key|" + key).getBytes(StandardCharsets.UTF_8)), 0, 16);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "not_found", "Not found");
    }
}
