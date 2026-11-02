package sa.waslah.agent.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import sa.waslah.agent.tenant.TenantFilter;

/**
 * Sessions are stateless JWTs that live 12 hours. This ends one early when its user is disabled, its office is
 * suspended, or the password changed after the token was issued. The check is cached for 30 seconds per user, and
 * the operator console evicts the cache on every change, so its effect is immediate.
 */
@Component
public class SessionGuard {

    static final long TTL_MS = 30_000;

    record Status(Instant disabledAt, Instant passwordChangedAt, Instant suspendedAt, long loadedAt) {
    }

    private final JdbcClient jdbc;
    private final Map<UUID, Status> cache = new ConcurrentHashMap<>();

    public SessionGuard(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Null when the session may continue; otherwise why not. Runs under the token's office tenant. */
    public String revoked(UUID userId, UUID officeId, Instant issuedAt) {
        Status s = cache.get(userId);
        if (s == null || System.currentTimeMillis() - s.loadedAt() > TTL_MS) {
            Optional<Status> loaded = jdbc.sql("""
                            select u.disabled_at, u.password_changed_at,
                                   (select o.suspended_at from offices o where o.id = u.office_id)
                            from users u where u.id = ? and u.office_id = ?""")
                    .params(userId, officeId)
                    .query((rs, n) -> new Status(ts(rs.getTimestamp(1)), ts(rs.getTimestamp(2)), ts(rs.getTimestamp(3)),
                            System.currentTimeMillis()))
                    .optional();
            if (loaded.isEmpty()) return "unknown_user";
            s = loaded.get();
            cache.put(userId, s);
        }
        if (s.disabledAt() != null) return "disabled";
        if (s.suspendedAt() != null) return "suspended";
        // JWT times are whole seconds; a session signed in the same second as the change is the new one.
        if (s.passwordChangedAt() != null && issuedAt != null
                && issuedAt.isBefore(s.passwordChangedAt().truncatedTo(ChronoUnit.SECONDS))) {
            return "password_changed";
        }
        return null;
    }

    public void evictUser(UUID userId) {
        cache.remove(userId);
    }

    /** After an office-wide change (suspension): every user is re-checked on the next request. */
    public void evictAll() {
        cache.clear();
    }

    private static Instant ts(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    /** Runs after {@link TenantFilter}, so the office tenant is set for the lookup. */
    public static class Filter extends OncePerRequestFilter {

        private final SessionGuard guard;
        private final SessionTokens tokens;

        public Filter(SessionGuard guard, SessionTokens tokens) {
            this.guard = guard;
            this.tokens = tokens;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth instanceof JwtAuthenticationToken jwt) {
                String office = jwt.getToken().getClaimAsString(TenantFilter.OFFICE_CLAIM);
                String subject = jwt.getToken().getSubject();
                if (office != null && subject != null) {
                    String reason;
                    try {
                        reason = guard.revoked(UUID.fromString(subject), UUID.fromString(office), jwt.getToken().getIssuedAt());
                    } catch (IllegalArgumentException e) {
                        reason = "bad_token";
                    }
                    if (reason != null) {
                        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                        response.setContentType("application/json;charset=UTF-8");
                        response.addHeader(HttpHeaders.SET_COOKIE, tokens.clearCookie().toString());
                        String message = switch (reason) {
                            case "disabled" -> "هذا الحساب موقوف. تواصل مع الدعم: support@hidaaco.com";
                            case "suspended" -> "حساب المكتب موقوف. تواصل مع الدعم: support@hidaaco.com";
                            default -> "انتهت الجلسة. سجّل الدخول من جديد.";
                        };
                        response.getOutputStream().write(("{\"error\":\"session_revoked\",\"reason\":\"" + reason
                                + "\",\"message\":\"" + message + "\"}").getBytes(StandardCharsets.UTF_8));
                        return;
                    }
                }
            }
            chain.doFilter(request, response);
        }
    }
}
