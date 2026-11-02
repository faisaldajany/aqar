package sa.waslah.agent.common;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** The caller's IP address, for rate limits and abuse checks. Only ever stored hashed. */
public final class ClientIp {

    private ClientIp() {
    }

    /**
     * Google's load balancer appends "client, load-balancer" to X-Forwarded-For, so the client is the second-to-last
     * entry; anything before it was written by the caller and can be forged. With one entry (local Caddy, which
     * replaces the header) that entry is the client.
     */
    public static String of(HttpServletRequest r) {
        String fwd = r.getHeader("X-Forwarded-For");
        if (fwd != null && !fwd.isBlank()) {
            String[] parts = fwd.split(",");
            String pick = parts.length >= 2 ? parts[parts.length - 2] : parts[0];
            if (!pick.isBlank()) return pick.trim();
        }
        return r.getRemoteAddr();
    }

    /** A short salted hash of the IP: enough to group requests, not to recover the address. */
    public static String hash(HttpServletRequest r, String salt) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest((salt + "|" + of(r)).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d, 0, 12);
        } catch (Exception e) {
            return "unknown";
        }
    }
}
