package sa.waslah.agent.auth;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import sa.waslah.agent.common.RateLimiter;

/** The signed-in user confirms their email with the 6-digit code (portal /verify-email), or asks for a new email. */
@RestController
public class VerifyEmailController {

    public record CodeRequest(String code) {
    }

    private final EmailConfirmationService confirmations;
    private final RateLimiter limits;

    public VerifyEmailController(EmailConfirmationService confirmations, RateLimiter limits) {
        this.confirmations = confirmations;
        this.limits = limits;
    }

    /** 10 tries per 15 minutes per user, on top of 5 per code. */
    @PostMapping("/api/verify-email")
    Map<String, Object> verify(@AuthenticationPrincipal Jwt jwt, @RequestBody CodeRequest r) {
        UUID user = UUID.fromString(jwt.getSubject());
        limits.check("verify-code:" + user, 10, Duration.ofMinutes(15));
        confirmations.verifyCode(user, r == null ? null : r.code());
        return Map.of("ok", true);
    }

    /** A new code and link; 3 an hour. */
    @PostMapping("/api/verify-email/resend")
    Map<String, Object> resend(@AuthenticationPrincipal Jwt jwt) {
        UUID user = UUID.fromString(jwt.getSubject());
        limits.check("verify-send:" + user, 3, Duration.ofHours(1));
        return Map.of("sent", confirmations.send(user), "verified", confirmations.verified(user));
    }
}
