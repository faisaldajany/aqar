package sa.waslah.agent.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import sa.waslah.agent.IntegrationTest;

/** «أبلغ عن الخطأ»: stored for the support inbox with the request id and who reported it; repeats give one report. */
@SuppressWarnings({"rawtypes", "unchecked"})
class ErrorReportTest extends IntegrationTest {

    @Autowired TestRestTemplate rest;
    @Autowired JdbcClient jdbc;

    ResponseEntity<Map> report(String ip, String cookie, Map<String, Object> body) {
        HttpHeaders h = new HttpHeaders();
        h.set("X-Forwarded-For", ip);
        h.set("User-Agent", "TestBrowser/1.0");
        if (cookie != null) h.add(HttpHeaders.COOKIE, cookie);
        return rest.postForEntity("/api/public/support/error-report", new HttpEntity<>(body, h), Map.class);
    }

    static Map<String, Object> seen(String requestId) {
        Map<String, Object> m = new HashMap<>();
        m.put("code", "internal");
        m.put("status", 500);
        m.put("message", "حدث خطأ من جهتنا ولم يكتمل طلبك.");
        m.put("requestId", requestId);
        m.put("method", "POST");
        m.put("path", "/api/whatsapp/setup/steps/number");
        m.put("page", "https://app.hidaaco.com/whatsapp");
        m.put("note", "كنت أكتب رقم المكتب");
        return m;
    }

    Map<String, Object> row(String requestId) {
        return jdbc.sql("select office_id, name, email, topic, message, source, forward_error from support_messages where position(? in message) > 0")
                .param(requestId).query((rs, n) -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("office", rs.getObject(1, UUID.class));
                    m.put("name", rs.getString(2));
                    m.put("email", rs.getString(3));
                    m.put("topic", rs.getString(4));
                    m.put("message", rs.getString(5));
                    m.put("source", rs.getString(6));
                    m.put("forwardError", rs.getString(7));
                    return m;
                }).single();
    }

    @Test
    void aVisitorsReportIsStoredWithWhatTheySaw() {
        String rid = UUID.randomUUID().toString();
        Map<String, Object> body = seen(rid);
        body.put("email", "visitor@example.com");
        ResponseEntity<Map> r = report("10.7.0.1", null, body);
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat((String) r.getBody().get("reference")).hasSize(8);

        Map<String, Object> m = row(rid);
        assertThat(m.get("source")).isEqualTo("error-report");
        assertThat(m.get("topic")).isEqualTo(SupportService.REPORT_TOPIC);
        assertThat(m.get("name")).isEqualTo("زائر");
        assertThat(m.get("email")).isEqualTo("visitor@example.com");
        assertThat(m.get("office")).isNull();
        assertThat((String) m.get("message")).contains("POST /api/whatsapp/setup/steps/number", "500 · internal",
                "كنت أكتب رقم المكتب", "TestBrowser/1.0", "https://app.hidaaco.com/whatsapp");
        // Same pipeline as the contact form: emailed when mail is set up, kept and retried until then.
        assertThat(m.get("forwardError")).isEqualTo("mail not configured");
    }

    @Test
    void aSignedInReportNamesTheOfficeAndUser() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        ResponseEntity<Map> signup = rest.postForEntity("/api/auth/signup", Map.of(
                "officeName", "مكتب " + tag, "falLicense", "12345678", "fullName", "أبو فهد",
                "email", tag + "@report.sa", "password", "password123"), Map.class);
        String cookie = signup.getHeaders().get(HttpHeaders.SET_COOKIE).stream().filter(c -> c.startsWith("waslah_session="))
                .map(c -> c.split(";")[0]).findFirst().orElseThrow();
        UUID office = UUID.fromString(String.valueOf(signup.getBody().get("officeId")));

        String rid = UUID.randomUUID().toString();
        assertThat(report("10.7.0.2", cookie, seen(rid)).getStatusCode().value()).isEqualTo(200);

        Map<String, Object> m = row(rid);
        assertThat(m.get("office")).isEqualTo(office);
        assertThat(m.get("name")).isEqualTo("أبو فهد");
        assertThat(m.get("email")).isEqualTo(tag + "@report.sa");
        assertThat((String) m.get("message")).contains("مكتب " + tag);
    }

    @Test
    void reportingTheSameErrorTwiceGivesOneReport() {
        String rid = UUID.randomUUID().toString();
        String first = (String) report("10.7.0.3", null, seen(rid)).getBody().get("reference");
        String again = (String) report("10.7.0.3", null, seen(rid)).getBody().get("reference");
        assertThat(again).isEqualTo(first);
        assertThat(jdbc.sql("select count(*) from support_messages where position(? in message) > 0").param(rid)
                .query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void botsAndBadEmailsAreTurnedAway() {
        String rid = UUID.randomUUID().toString();
        Map<String, Object> bot = seen(rid);
        bot.put("website", "http://spam");
        assertThat(report("10.7.0.4", null, bot).getStatusCode().value()).isEqualTo(200);
        assertThat(jdbc.sql("select count(*) from support_messages where position(? in message) > 0").param(rid)
                .query(Integer.class).single()).isZero();

        Map<String, Object> bad = seen(UUID.randomUUID().toString());
        bad.put("email", "not-an-email");
        ResponseEntity<Map> r = report("10.7.0.4", null, bad);
        assertThat(r.getStatusCode().value()).isEqualTo(400);
        assertThat(r.getBody().get("error")).isEqualTo("bad_email");
    }

    @Test
    void rateLimitedPerSenderWithoutTouchingTheContactForm() {
        String ip = "10.8." + (int) (Math.random() * 200) + ".9";
        for (int i = 0; i < SupportService.MAX_REPORTS_PER_HOUR; i++) {
            assertThat(report(ip, null, seen(UUID.randomUUID().toString())).getStatusCode().value()).isEqualTo(200);
        }
        assertThat(report(ip, null, seen(UUID.randomUUID().toString())).getStatusCode().value()).isEqualTo(429);

        HttpHeaders h = new HttpHeaders();
        h.set("X-Forwarded-For", ip);
        ResponseEntity<Map> contact = rest.postForEntity("/api/public/support", new HttpEntity<>(Map.of(
                "name", "أبو فهد", "email", "abu@example.com", "topic", "أخرى", "message", "سؤال بعد بلاغات كثيرة"), h), Map.class);
        assertThat(contact.getStatusCode().value()).isEqualTo(200);
    }
}
