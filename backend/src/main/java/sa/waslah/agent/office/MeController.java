package sa.waslah.agent.office;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import sa.waslah.agent.common.ApiException;
import sa.waslah.agent.tenant.TenantContext;

@RestController
public class MeController {

    private final JdbcClient jdbc;

    public MeController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/api/me")
    Map<String, Object> me(@AuthenticationPrincipal Jwt jwt) {
        UUID officeId = TenantContext.require();
        UUID userId = UUID.fromString(jwt.getSubject());

        Map<String, Object> user = jdbc.sql("select id, email, full_name, role from users where id = ? and office_id = ?")
                .params(userId, officeId)
                .query((rs, n) -> Map.<String, Object>of(
                        "id", rs.getObject("id", UUID.class),
                        "email", rs.getString("email"),
                        "fullName", rs.getString("full_name"),
                        "role", rs.getString("role")))
                .optional()
                .orElseThrow(() -> ApiException.unauthorized("no_user", "انتهت الجلسة."));

        Map<String, Object> office = jdbc.sql("select id, name, fal_license, status from offices")
                .query((rs, n) -> Map.<String, Object>of(
                        "id", rs.getObject("id", UUID.class),
                        "name", rs.getString("name"),
                        "falLicense", rs.getString("fal_license"),
                        "status", rs.getString("status")))
                .optional()
                .orElseThrow(() -> ApiException.notFound("no_office", "المكتب غير موجود."));

        Map<String, Object> whatsapp = jdbc.sql("""
                        select display_phone, verified_name, coexistence, status
                        from wa_accounts order by connected_at desc limit 1""")
                .query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("connected", true);
                    m.put("displayPhone", rs.getString("display_phone"));
                    m.put("verifiedName", rs.getString("verified_name"));
                    m.put("coexistence", rs.getBoolean("coexistence"));
                    m.put("status", rs.getString("status"));
                    return m;
                })
                .optional()
                .orElseGet(() -> Map.of("connected", false));

        return Map.of("user", user, "office", office, "whatsapp", whatsapp);
    }
}
