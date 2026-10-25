package sa.waslah.agent.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    public record SignupRequest(
            @NotBlank @Size(max = 120) String officeName,
            @NotBlank @Pattern(regexp = "^[0-9\\s-]{8,16}$") String falLicense,
            @NotBlank @Size(max = 120) String fullName,
            @NotBlank @Email String email,
            @NotBlank @Size(min = 8, max = 128) String password) {
    }

    public record LoginRequest(@NotBlank @Email String email, @NotBlank String password) {
    }

    private final AuthService auth;
    private final SessionTokens tokens;

    public AuthController(AuthService auth, SessionTokens tokens) {
        this.auth = auth;
        this.tokens = tokens;
    }

    @PostMapping("/signup")
    ResponseEntity<Map<String, Object>> signup(@Valid @RequestBody SignupRequest r) {
        AuthService.SignedIn s = auth.signup(new AuthService.Signup(
                r.officeName(), r.falLicense(), r.fullName(), r.email(), r.password()));
        return withSession(s);
    }

    @PostMapping("/login")
    ResponseEntity<Map<String, Object>> login(@Valid @RequestBody LoginRequest r) {
        return withSession(auth.login(r.email(), r.password()));
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
