package sa.waslah.agent.affiliate;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import sa.waslah.agent.common.ApiException;
import sa.waslah.agent.common.ClientIp;
import sa.waslah.agent.common.RateLimiter;

/** The marketers' page (app.hidaaco.com/partner): sign-in, setting a password from a link, and their own figures. */
@RestController
public class PartnerController {

    private final PartnerAuth auth;
    private final AffiliateService affiliates;
    private final RateLimiter limits;

    public PartnerController(PartnerAuth auth, AffiliateService affiliates, RateLimiter limits) {
        this.auth = auth;
        this.affiliates = affiliates;
        this.limits = limits;
    }

    record Login(String email, String password) {
    }

    record SetPassword(String token, String password) {
    }

    record Forgot(String email) {
    }

    @PostMapping("/api/partner/login")
    ResponseEntity<Map<String, Object>> login(HttpServletRequest http, @RequestBody Login r) {
        UUID id = auth.login(http, r == null ? null : r.email(), r == null ? null : r.password());
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, auth.cookie(id).toString()).body(Map.of("ok", true));
    }

    @PostMapping("/api/partner/logout")
    ResponseEntity<Void> logout() {
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, auth.clearCookie().toString()).build();
    }

    @PostMapping("/api/partner/password")
    ResponseEntity<Map<String, Object>> setPassword(HttpServletRequest http, @RequestBody SetPassword r) {
        if (!limits.tryAcquire("partner-password:" + ClientIp.hash(http, "partner"), 10, Duration.ofMinutes(15))) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "too_many_attempts", "محاولات كثيرة. حاول بعد 15 دقيقة.");
        }
        UUID id = auth.setPassword(r == null ? null : r.token(), r == null ? null : r.password());
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, auth.cookie(id).toString()).body(Map.of("ok", true));
    }

    @PostMapping("/api/partner/forgot")
    ResponseEntity<Void> forgot(HttpServletRequest http, @RequestBody Forgot r) {
        if (limits.tryAcquire("partner-forgot:" + ClientIp.hash(http, "partner"), 5, Duration.ofMinutes(15)) && r != null) {
            affiliates.forgot(r.email());
        }
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/partner/me")
    Map<String, Object> me(HttpServletRequest http) {
        return affiliates.page(auth.require(http), false);
    }
}
