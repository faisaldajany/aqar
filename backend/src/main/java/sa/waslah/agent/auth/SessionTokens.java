package sa.waslah.agent.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.ResponseCookie;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;
import sa.waslah.agent.config.SecurityConfig;
import sa.waslah.agent.config.WaslahProperties;
import sa.waslah.agent.tenant.TenantFilter;

@Component
public class SessionTokens {

    static final Duration TTL = Duration.ofHours(12);

    private final JwtEncoder encoder;
    private final WaslahProperties props;

    public SessionTokens(JwtEncoder encoder, WaslahProperties props) {
        this.encoder = encoder;
        this.props = props;
    }

    public String issue(UUID userId, UUID officeId, String role) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("waslah")
                .subject(userId.toString())
                .issuedAt(now)
                .expiresAt(now.plus(TTL))
                .claim(TenantFilter.OFFICE_CLAIM, officeId.toString())
                .claim("role", role)
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    public ResponseCookie cookie(String token) {
        return ResponseCookie.from(SecurityConfig.SESSION_COOKIE, token)
                .httpOnly(true)
                .secure(props.cookieSecure())
                .sameSite("Lax")
                .path("/")
                .maxAge(TTL)
                .build();
    }

    public ResponseCookie clearCookie() {
        return ResponseCookie.from(SecurityConfig.SESSION_COOKIE, "")
                .httpOnly(true)
                .secure(props.cookieSecure())
                .sameSite("Lax")
                .path("/")
                .maxAge(0)
                .build();
    }
}
