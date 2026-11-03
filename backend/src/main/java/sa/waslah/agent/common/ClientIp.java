package sa.waslah.agent.common;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
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

    /**
     * The address that requests are grouped by: an IPv4 address as is, an IPv6 address by its /64 network. One home
     * connection or phone gets a whole /64, so counting single IPv6 addresses would let one caller use billions.
     */
    public static String group(String ip) {
        if (ip == null || ip.isBlank()) return "unknown";
        String s = ip.trim();
        if (s.startsWith("[") && s.indexOf(']') > 0) s = s.substring(1, s.indexOf(']'));
        int zone = s.indexOf('%');
        if (zone >= 0) s = s.substring(0, zone);
        // Only a literal with a colon is parsed, so InetAddress never turns this into a DNS lookup.
        if (s.indexOf(':') < 0 || !s.matches("[0-9A-Fa-f:.]{2,45}")) return s;
        try {
            InetAddress a = InetAddress.getByName(s);
            byte[] b = a.getAddress();
            if (b.length == 4) return a.getHostAddress(); // IPv4-mapped (::ffff:1.2.3.4)
            return HexFormat.of().formatHex(b, 0, 8) + "/64";
        } catch (Exception e) {
            return s;
        }
    }

    /** A short salted hash of the caller's address group: enough to group requests, not to recover the address. */
    public static String hash(HttpServletRequest r, String salt) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest((salt + "|" + group(of(r))).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d, 0, 12);
        } catch (Exception e) {
            return "unknown";
        }
    }
}
