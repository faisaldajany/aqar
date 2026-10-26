package sa.waslah.agent.support;

import jakarta.mail.internet.MimeMessage;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import sa.waslah.agent.common.ApiException;

/**
 * Public support form (portal /contact and the website). Every message is stored first, then forwarded
 * by email to the support inbox with Reply-To set to the sender; unsent messages are retried, and the
 * operator console lists them, so nothing is lost while email is not set up.
 */
@RestController
public class SupportService {

    private static final Logger log = LoggerFactory.getLogger(SupportService.class);
    static final int MAX_PER_HOUR = 5;
    static final List<String> TOPICS = List.of("سؤال عن الخدمة", "مشكلة تقنية", "الاشتراك والفواتير", "ربط واتساب", "اقتراح", "أخرى");

    public record Request(String name, String email, String phone, String topic, String message, String office,
                          String source, String website) {
    }

    private final JdbcClient jdbc;
    private final ObjectProvider<JavaMailSender> mail;
    private final String mailHost, forwardTo, from, adminToken;

    public SupportService(JdbcClient jdbc, ObjectProvider<JavaMailSender> mail,
                          @Value("${spring.mail.host:}") String mailHost,
                          @Value("${waslah.support.forward-to:}") String forwardTo,
                          @Value("${waslah.support.from:no-reply@hidaaco.com}") String from,
                          @Value("${waslah.admin-token:}") String adminToken) {
        this.jdbc = jdbc;
        this.mail = mail;
        this.mailHost = mailHost;
        this.forwardTo = forwardTo;
        this.from = from;
        this.adminToken = adminToken;
    }

    @GetMapping("/api/public/support/topics")
    List<String> topics() {
        return TOPICS;
    }

    @PostMapping("/api/public/support")
    Map<String, Object> submit(@RequestBody Request r, HttpServletRequest http) {
        // Bots fill the hidden "website" field; pretend success and drop it.
        if (r.website() != null && !r.website().isBlank()) return Map.of("ok", true);
        String name = trim(r.name(), 120), email = trim(r.email(), 200), phone = trim(r.phone(), 30);
        String message = trim(r.message(), 4000), office = trim(r.office(), 200);
        String topic = TOPICS.contains(r.topic()) ? r.topic() : "أخرى";
        if (name == null) throw ApiException.badRequest("no_name", "اكتب اسمك.");
        if (email == null && phone == null) throw ApiException.badRequest("no_contact", "اكتب بريدك أو جوالك لنرد عليك.");
        if (email != null && !email.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) throw ApiException.badRequest("bad_email", "البريد غير صحيح.");
        if (phone != null && !phone.replaceAll("[\\s+\\-]", "").matches("[0-9٠-٩]{9,15}")) throw ApiException.badRequest("bad_phone", "الجوال غير صحيح.");
        if (message == null || message.length() < 10) throw ApiException.badRequest("short", "اكتب رسالتك (10 أحرف على الأقل).");

        String ip = ipHash(http);
        int recent = jdbc.sql("select count(*) from support_messages where ip_hash = ? and created_at > now() - interval '1 hour'")
                .param(ip).query(Integer.class).single();
        if (recent >= MAX_PER_HOUR) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "too_many", "وصلتنا رسائلك. حاول مرة أخرى بعد ساعة.");
        }
        String body = office == null ? message : "المكتب: " + office + "\n\n" + message;
        String source = "website".equals(r.source()) ? "website" : "portal";
        UUID id = jdbc.sql("""
                insert into support_messages (name, email, phone, topic, message, source, ip_hash)
                values (?, ?, ?, ?, ?, ?, ?) returning id""")
                .params(name, email, phone, topic, body, source, ip).query(UUID.class).single();
        forward(id);
        String ref = id.toString().substring(0, 8).toUpperCase();
        return Map.of("ok", true, "reference", ref);
    }

    /** Retries messages that could not be emailed (e.g. mail server down or not yet configured). */
    @Scheduled(fixedDelay = 600_000, initialDelay = 120_000)
    public void retry() {
        if (!mailConfigured()) return;
        jdbc.sql("select id from support_messages where forwarded_at is null and created_at > now() - interval '3 days'")
                .query(UUID.class).list().forEach(this::forward);
    }

    /** Operator console: latest messages, so none are lost while email is not set up. */
    @GetMapping("/admin/support")
    List<Map<String, Object>> list(@RequestHeader(value = "X-Admin-Token", required = false) String t) {
        if (adminToken == null || adminToken.isBlank() || t == null
                || !MessageDigest.isEqual(adminToken.getBytes(StandardCharsets.UTF_8), t.getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(HttpStatus.NOT_FOUND, "not_found", "Not found");
        }
        return jdbc.sql("""
                select id, created_at, name, email, phone, topic, message, source, forwarded_at, forward_error
                from support_messages order by created_at desc limit 200""")
                .query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getObject(1, UUID.class));
                    m.put("createdAt", rs.getTimestamp(2).toInstant().toString());
                    m.put("name", rs.getString(3));
                    m.put("email", rs.getString(4));
                    m.put("phone", rs.getString(5));
                    m.put("topic", rs.getString(6));
                    m.put("message", rs.getString(7));
                    m.put("source", rs.getString(8));
                    m.put("forwarded", rs.getTimestamp(9) != null);
                    m.put("forwardError", rs.getString(10));
                    return m;
                }).list();
    }

    void forward(UUID id) {
        if (!mailConfigured() || forwardTo == null || forwardTo.isBlank()) {
            jdbc.sql("update support_messages set forward_error = 'mail not configured' where id = ?").param(id).update();
            return;
        }
        Map<String, Object> m = jdbc.sql("select name, email, phone, topic, message, source, created_at from support_messages where id = ?")
                .param(id).query((rs, n) -> {
                    Map<String, Object> x = new LinkedHashMap<>();
                    x.put("name", rs.getString(1));
                    x.put("email", rs.getString(2));
                    x.put("phone", rs.getString(3));
                    x.put("topic", rs.getString(4));
                    x.put("message", rs.getString(5));
                    x.put("source", rs.getString(6));
                    x.put("at", rs.getTimestamp(7).toInstant());
                    return x;
                }).single();
        try {
            JavaMailSender sender = mail.getObject();
            MimeMessage msg = sender.createMimeMessage();
            MimeMessageHelper h = new MimeMessageHelper(msg, false, "UTF-8");
            h.setFrom(from, "حداء — نموذج الدعم");
            h.setTo(forwardTo);
            if (m.get("email") != null) h.setReplyTo((String) m.get("email"));
            h.setSubject("[حداء دعم] " + m.get("topic") + " — " + m.get("name") + " #" + id.toString().substring(0, 8).toUpperCase());
            String when = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Riyadh")).format((Instant) m.get("at"));
            h.setText("الاسم: " + m.get("name")
                    + "\nالبريد: " + (m.get("email") == null ? "—" : m.get("email"))
                    + "\nالجوال: " + (m.get("phone") == null ? "—" : m.get("phone"))
                    + "\nالموضوع: " + m.get("topic")
                    + "\nالمصدر: " + m.get("source") + " · " + when + " (الرياض)"
                    + "\n\n" + m.get("message"), false);
            sender.send(msg);
            jdbc.sql("update support_messages set forwarded_at = now(), forward_error = null where id = ?").param(id).update();
        } catch (Exception e) {
            log.warn("Support message {} not forwarded: {}", id, e.getMessage());
            jdbc.sql("update support_messages set forward_error = ? where id = ?")
                    .params(String.valueOf(e.getMessage()).substring(0, Math.min(300, String.valueOf(e.getMessage()).length())), id).update();
        }
    }

    private boolean mailConfigured() {
        return mailHost != null && !mailHost.isBlank() && mail.getIfAvailable() != null;
    }

    private static String trim(String s, int max) {
        if (s == null) return null;
        s = s.strip();
        if (s.isEmpty()) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static String ipHash(HttpServletRequest r) {
        String fwd = r.getHeader("X-Forwarded-For");
        String ip = fwd != null && !fwd.isBlank() ? fwd.split(",")[0].trim() : r.getRemoteAddr();
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(("hidaa-support|" + ip).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d, 0, 12);
        } catch (Exception e) {
            return "unknown";
        }
    }
}
