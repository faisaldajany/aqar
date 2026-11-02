package sa.waslah.agent.admin;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
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
import sa.waslah.agent.config.WaslahProperties;

/**
 * Who may use the operator console: whoever holds the admin key (Secret Manager "admin-token"). Signing in with the
 * key gives an 8-hour session cookie that is only sent to /api/ops. Scripts can send the key as X-Admin-Token
 * instead. With no key configured (or one shorter than 16 characters) the console does not exist: every call is 404.
 */
@Component
public class OpsAuth {

    public static final String COOKIE = "hidaa_ops";
    static final Duration TTL = Duration.ofHours(8);
    static final String SCOPE = "ops";

    private final String key;
    private final JwtEncoder encoder;
    private final JwtDecoder decoder;
    private final WaslahProperties props;

    public OpsAuth(@Value("${waslah.admin-token:}") String key, JwtEncoder encoder, JwtDecoder decoder, WaslahProperties props) {
        this.key = key == null ? "" : key.trim();
        this.encoder = encoder;
        this.decoder = decoder;
        this.props = props;
    }

    public boolean enabled() {
        return key.length() >= 16;
    }

    public boolean keyMatches(String given) {
        return enabled() && given != null
                && MessageDigest.isEqual(key.getBytes(StandardCharsets.UTF_8), given.trim().getBytes(StandardCharsets.UTF_8));
    }

    public ResponseCookie sessionCookie() {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer("waslah").subject("operator").issuedAt(now)
                .expiresAt(now.plus(TTL)).claim("scope", SCOPE).build();
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        return ResponseCookie.from(COOKIE, token).httpOnly(true).secure(props.cookieSecure()).sameSite("Strict")
                .path("/api/ops").maxAge(TTL).build();
    }

    public ResponseCookie clearCookie() {
        return ResponseCookie.from(COOKIE, "").httpOnly(true).secure(props.cookieSecure()).sameSite("Strict")
                .path("/api/ops").maxAge(0).build();
    }

    /** Throws 404 unless the request carries the key or a valid operator session. */
    public void require(HttpServletRequest r) {
        if (!enabled()) throw notFound();
        if (keyMatches(r.getHeader("X-Admin-Token"))) return;
        Cookie[] cookies = r.getCookies();
        if (cookies != null) {
            for (Cookie c : cookies) {
                if (!COOKIE.equals(c.getName()) || c.getValue().isBlank()) continue;
                try {
                    Jwt jwt = decoder.decode(c.getValue());
                    if (SCOPE.equals(jwt.getClaimAsString("scope")) && "operator".equals(jwt.getSubject())) return;
                } catch (JwtException e) {
                    // expired or forged: fall through
                }
            }
        }
        throw notFound();
    }

    static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "not_found", "Not found");
    }
}
