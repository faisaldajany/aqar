package sa.waslah.agent.auth;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import sa.waslah.agent.billing.PromoService;
import sa.waslah.agent.common.ApiException;
import sa.waslah.agent.tenant.TenantContext;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    public record Signup(String officeName, String falLicense, String fullName, String email, String password,
                         String promoCode) {

        public Signup(String officeName, String falLicense, String fullName, String email, String password) {
            this(officeName, falLicense, fullName, email, password, null);
        }
    }

    public record SignedIn(UUID userId, UUID officeId, String role) {
    }

    private final JdbcClient jdbc;
    private final PasswordEncoder passwords;
    private final TransactionTemplate tx;
    private final PromoService promos;

    public AuthService(JdbcClient jdbc, PasswordEncoder passwords, TransactionTemplate tx, PromoService promos) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.tx = tx;
        this.promos = promos;
    }

    public SignedIn signup(Signup s) {
        String fal = sa.waslah.agent.office.OfficeSettingsController.falDigits(s.falLicense()); // checked before anything else
        String email = normalize(s.email());
        boolean taken = jdbc.sql("select exists(select 1 from users where email = ?)")
                .param(email).query(Boolean.class).single();
        if (taken) {
            throw ApiException.conflict("email_taken", "هذا البريد مسجّل مسبقاً.");
        }
        boolean withPromo = s.promoCode() != null && !s.promoCode().isBlank();
        if (withPromo) {
            promos.usable(s.promoCode()); // a wrong code is reported before the account is created
        }
        UUID officeId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        // The office row is under RLS, so it is inserted as that office.
        TenantContext.run(officeId, () -> tx.executeWithoutResult(status -> {
            jdbc.sql("insert into offices (id, name, fal_license) values (?, ?, ?)")
                    .params(officeId, s.officeName().trim(), fal)
                    .update();
            jdbc.sql("""
                    insert into users (id, office_id, email, password_hash, full_name, role)
                    values (?, ?, ?, ?, ?, 'OWNER')""")
                    .params(userId, officeId, email, passwords.encode(s.password()), s.fullName().trim())
                    .update();
        }));
        if (withPromo) {
            try {
                TenantContext.run(officeId, () -> promos.apply(s.promoCode()));
            } catch (RuntimeException e) {
                log.warn("Promo {} not applied at signup for office {}: {}", s.promoCode(), officeId, e.getMessage());
            }
        }
        return new SignedIn(userId, officeId, "OWNER");
    }

    public SignedIn login(String email, String password) {
        Optional<UserRow> user = jdbc.sql("select id, office_id, password_hash, role, disabled_at from users where email = ?")
                .param(normalize(email))
                .query((rs, n) -> new UserRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("office_id", UUID.class),
                        rs.getString("password_hash"),
                        rs.getString("role"),
                        rs.getTimestamp("disabled_at") != null))
                .optional();
        if (user.isEmpty() || !passwords.matches(password, user.get().passwordHash())) {
            throw ApiException.unauthorized("bad_credentials", "البريد أو كلمة المرور غير صحيحة.");
        }
        UserRow u = user.get();
        // Only after the password matched, so these messages do not reveal which emails exist.
        if (u.disabled()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "account_disabled", "هذا الحساب موقوف. تواصل مع الدعم: support@hidaaco.com");
        }
        boolean suspended = TenantContext.call(u.officeId(), () ->
                jdbc.sql("select suspended_at is not null from offices").query(Boolean.class).optional().orElse(false));
        if (suspended) {
            throw new ApiException(HttpStatus.FORBIDDEN, "office_suspended", "حساب المكتب موقوف. تواصل مع الدعم: support@hidaaco.com");
        }
        jdbc.sql("update users set last_login_at = now() where id = ?").param(u.id()).update();
        return new SignedIn(u.id(), u.officeId(), u.role());
    }

    private static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private record UserRow(UUID id, UUID officeId, String passwordHash, String role, boolean disabled) {
    }
}
