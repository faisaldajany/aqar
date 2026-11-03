package sa.waslah.agent.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import sa.waslah.agent.tenant.TenantFilter;

/**
 * Sessions are stateless JWTs that live 12 hours. This ends one early when its user is disabled, its office is
 * suspended, or the password changed after the token was issued. The check is cached for 30 seconds per user, and
 * every change evicts the cache, so its effect is immediate. A lookup that was already running when the cache was
 * evicted may have read the old state, so its result is not kept.
 */
@Component
public class SessionGuard {

    static final long TTL_MS = 30_000;

    record Status(Instant disabledAt, Instant passwordChangedAt, Instant suspendedAt, boolean emailConfirmed, long loadedAt) {
    }

    /** What an account that has not confirmed its email may still call: who it is, signing out, and the confirmation itself. */
    static final java.util.List<String> UNVERIFIED_ALLOWED = java.util.List.of("/api/me", "/api/auth/", "/api/verify-email");

    private final JdbcClient jdbc;
    private final boolean verificationRequired;
    private final Map<UUID, Status> cache = new ConcurrentHashMap<>();
    /** Bumped by every eviction, before the entry is removed. */
    private final AtomicLong evictions = new AtomicLong();

    public SessionGuard(JdbcClient jdbc,
                        @org.springframework.beans.factory.annotation.Value("${waslah.email-verification.required:true}") boolean verificationRequired) {
        this.jdbc = jdbc;
        this.verificationRequired = verificationRequired;
    }

    /** Whether the user has confirmed their email (from the same 30-second cache as {@link #revoked}). */
    public boolean emailConfirmed(UUID userId) {
        Status s = cache.get(userId);
        if (s != null) return s.emailConfirmed();
        return jdbc.sql("select email_confirmed_at is not null from users where id = ?").param(userId)
                .query(Boolean.class).optional().orElse(false);
    }

    public boolean verificationRequired() {
        return verificationRequired;
    }

    /** Null when the session may continue; otherwise why not. Runs under the token's office tenant. */
    public String revoked(UUID userId, UUID officeId, Instant issuedAt) {
        Status s = cache.get(userId);
        if (s == null || System.currentTimeMillis() - s.loadedAt() > TTL_MS) {
            long before = evictions.get();
            Optional<Status> loaded = jdbc.sql("""
                            select u.disabled_at, u.password_changed_at,
                                   (select o.suspended_at from offices o where o.id = u.office_id),
                                   u.email_confirmed_at is not null
                            from users u where u.id = ? and u.office_id = ?""")
                    .params(userId, officeId)
                    .query((rs, n) -> new Status(ts(rs.getTimestamp(1)), ts(rs.getTimestamp(2)), ts(rs.getTimestamp(3)),
                            rs.getBoolean(4), System.currentTimeMillis()))
                    .optional();
            if (loaded.isEmpty()) return "unknown_user";
            s = loaded.get();
            cache.put(userId, s);
            // Evicted while reading: drop what was read. (An eviction after this check removes it itself.)
            if (evictions.get() != before) cache.remove(userId, s);
        }
        if (s.disabledAt() != null) return "disabled";
        if (s.suspendedAt() != null) return "suspended";
        // issuedAt is exact for sessions that carry iat_ms. Older ones have whole seconds, which is never later than
        // the real time, so one signed in the same second as the change is ended too.
        if (s.passwordChangedAt() != null && (issuedAt == null || issuedAt.isBefore(s.passwordChangedAt()))) {
            return "password_changed";
        }
        return null;
    }

    public void evictUser(UUID userId) {
        evictions.incrementAndGet();
        cache.remove(userId);
    }

    /** After an office-wide change (suspension): every user is re-checked on the next request. */
    public void evictAll() {
        evictions.incrementAndGet();
        cache.clear();
    }

    /** When the session was signed in: exact if the token has iat_ms, else the whole second of "iat". */
    static Instant issuedAt(Jwt token) {
        Object ms = token.getClaims().get(SessionTokens.ISSUED_MS);
        if (ms instanceof Number n) return Instant.ofEpochMilli(n.longValue());
        return token.getIssuedAt();
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
                        reason = guard.revoked(UUID.fromString(subject), UUID.fromString(office), issuedAt(jwt.getToken()));
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
                    // Signed in but the email is not confirmed yet: only the confirmation screen's calls go through.
                    String path = request.getRequestURI();
                    if (guard.verificationRequired() && path.startsWith("/api/")
                            && UNVERIFIED_ALLOWED.stream().noneMatch(path::startsWith)
                            && !guard.emailConfirmed(UUID.fromString(subject))) {
                        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                        response.setContentType("application/json;charset=UTF-8");
                        response.getOutputStream().write(("{\"error\":\"email_unverified\",\"message\":\""
                                + "أكّد بريدك أولاً بالرمز الذي أرسلناه.\"}").getBytes(StandardCharsets.UTF_8));
                        return;
                    }
                }
            }
            chain.doFilter(request, response);
        }
    }
}
