package sa.waslah.agent.auth;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import sa.waslah.agent.common.ApiException;
import sa.waslah.agent.tenant.TenantContext;

@Service
public class AuthService {

    public record Signup(String officeName, String falLicense, String fullName, String email, String password) {
    }

    public record SignedIn(UUID userId, UUID officeId, String role) {
    }

    private final JdbcClient jdbc;
    private final PasswordEncoder passwords;
    private final TransactionTemplate tx;

    public AuthService(JdbcClient jdbc, PasswordEncoder passwords, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.tx = tx;
    }

    public SignedIn signup(Signup s) {
        String email = normalize(s.email());
        boolean taken = jdbc.sql("select exists(select 1 from users where email = ?)")
                .param(email).query(Boolean.class).single();
        if (taken) {
            throw ApiException.conflict("email_taken", "هذا البريد مسجّل مسبقاً.");
        }
        UUID officeId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        // The office row is under RLS, so it is inserted as that office.
        TenantContext.run(officeId, () -> tx.executeWithoutResult(status -> {
            jdbc.sql("insert into offices (id, name, fal_license) values (?, ?, ?)")
                    .params(officeId, s.officeName().trim(), s.falLicense().replaceAll("\\D", ""))
                    .update();
            jdbc.sql("""
                    insert into users (id, office_id, email, password_hash, full_name, role)
                    values (?, ?, ?, ?, ?, 'OWNER')""")
                    .params(userId, officeId, email, passwords.encode(s.password()), s.fullName().trim())
                    .update();
        }));
        return new SignedIn(userId, officeId, "OWNER");
    }

    public SignedIn login(String email, String password) {
        Optional<UserRow> user = jdbc.sql("select id, office_id, password_hash, role from users where email = ?")
                .param(normalize(email))
                .query((rs, n) -> new UserRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("office_id", UUID.class),
                        rs.getString("password_hash"),
                        rs.getString("role")))
                .optional();
        if (user.isEmpty() || !passwords.matches(password, user.get().passwordHash())) {
            throw ApiException.unauthorized("bad_credentials", "البريد أو كلمة المرور غير صحيحة.");
        }
        UserRow u = user.get();
        return new SignedIn(u.id(), u.officeId(), u.role());
    }

    private static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private record UserRow(UUID id, UUID officeId, String passwordHash, String role) {
    }
}
