package sa.waslah.agent.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import sa.waslah.agent.common.ApiException;
import sa.waslah.agent.common.ClientIp;
import sa.waslah.agent.common.RateLimiter;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    public record SignupRequest(
            @NotBlank @Size(max = 120) String officeName,
            @NotBlank @Pattern(regexp = "^[0-9\\s-]{8,16}$") String falLicense,
            @NotBlank @Size(max = 120) String fullName,
            @NotBlank @Email String email,
            @NotBlank @Size(min = 8, max = 128) String password,
            @Size(max = 40) String promoCode) {
    }

    public record LoginRequest(@NotBlank @Email String email, @NotBlank String password) {
    }

    public record ForgotRequest(@NotBlank @Email String email) {
    }

    public record ResetRequest(@NotBlank String token, @NotBlank @Size(min = 8, max = 128) String password) {
    }

    static final Duration WINDOW = Duration.ofMinutes(15);

    private final AuthService auth;
    private final SessionTokens tokens;
    private final RateLimiter limits;
    private final PasswordResetService resets;

    public AuthController(AuthService auth, SessionTokens tokens, RateLimiter limits, PasswordResetService resets) {
        this.auth = auth;
        this.tokens = tokens;
        this.limits = limits;
        this.resets = resets;
    }

    @PostMapping("/signup")
    ResponseEntity<Map<String, Object>> signup(@Valid @RequestBody SignupRequest r, HttpServletRequest http) {
        limits.check("signup:" + ClientIp.hash(http, "auth"), 10, Duration.ofHours(1));
        AuthService.SignedIn s = auth.signup(new AuthService.Signup(
                r.officeName(), r.falLicense(), r.fullName(), r.email(), r.password(), r.promoCode()));
        return withSession(s);
    }

    /**
     * Wrong passwords are limited in 15 minutes to 10 per email from one address, 50 per email from anywhere and 50 per
     * address. A stranger guessing someone's password from one address locks out only that address, not the owner.
     * Every attempt is counted before the password is checked, so parallel guesses cannot slip past the limit, and
     * given back unless the password was wrong.
     */
    @PostMapping("/login")
    ResponseEntity<Map<String, Object>> login(@Valid @RequestBody LoginRequest r, HttpServletRequest http) {
        String email = r.email().trim().toLowerCase(Locale.ROOT);
        String ip = ClientIp.hash(http, "auth");
        String fromHere = loginKey(email, ip);
        Map<String, Integer> keys = Map.of(fromHere, 10, "login-email:" + email, 50, "login-ip:" + ip, 50);
        if (!limits.tryAcquireAll(WINDOW, keys)) throw RateLimiter.tooMany();
        ResponseEntity<Map<String, Object>> ok;
        try {
            ok = withSession(auth.login(r.email(), r.password()));
        } catch (ApiException e) {
            if (!"bad_credentials".equals(e.code())) keys.keySet().forEach(limits::undo);
            throw e;
        }
        keys.keySet().forEach(limits::undo);
        limits.reset(fromHere);
        return ok;
    }

    static String loginKey(String email, String ipHash) {
        return "login:" + email + "|" + ipHash;
    }

    /** Always the same answer, whether or not the email has an account. */
    @PostMapping("/forgot")
    Map<String, Object> forgot(@Valid @RequestBody ForgotRequest r, HttpServletRequest http) {
        limits.check("forgot:" + ClientIp.hash(http, "auth"), 5, Duration.ofHours(1));
        // Over 3 an hour for one email, nothing is sent, and the answer is still the same.
        if (limits.tryAcquire("forgot-email:" + r.email().trim().toLowerCase(Locale.ROOT), 3, Duration.ofHours(1))) {
            resets.requestByEmail(r.email());
        }
        return Map.of("ok", true, "message", "إذا كان البريد مسجّلاً لدينا فسيصلك رابط لإعادة تعيين كلمة المرور خلال دقائق.");
    }

    @PostMapping("/reset")
    Map<String, Object> reset(@Valid @RequestBody ResetRequest r, HttpServletRequest http) {
        limits.check("reset:" + ClientIp.hash(http, "auth"), 10, Duration.ofHours(1));
        String email = resets.reset(r.token(), r.password()).trim().toLowerCase(Locale.ROOT);
        limits.reset("login-email:" + email);
        limits.reset(loginKey(email, ClientIp.hash(http, "auth")));
        return Map.of("ok", true);
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout() {
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, tokens.clearCookie().toString())
                .build();
    }

    private ResponseEntity<Map<String, Object>> withSession(AuthService.SignedIn s) {
        String token = tokens.issue(s.userId(), s.officeId(), s.role());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, tokens.cookie(token).toString())
                .body(Map.of("userId", s.userId(), "officeId", s.officeId()));
    }
}
