package sa.waslah.agent.whatsapp;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import sa.waslah.agent.IntegrationTest;
import sa.waslah.agent.auth.AuthService;
import sa.waslah.agent.report.DailyReport;
import sa.waslah.agent.tenant.TenantContext;

/** Each office gets its own copies of the four templates when it connects WhatsApp, and they are used once approved. */
class WaTemplatesTest extends IntegrationTest {

    @Autowired AuthService auth;
    @Autowired EmbeddedSignupService signup;
    @Autowired WaTemplates templates;
    @Autowired DailyReport reports;
    @Autowired WhatsAppClient whatsapp;
    @Autowired JdbcClient jdbc;

    UUID office;
    UUID account;
    String phoneNumberId;

    @BeforeEach
    void setUp() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        office = auth.signup(new AuthService.Signup("مكتب " + tag, "1100012345", "مالك", tag + "@tpl.sa", "password123")).officeId();
        phoneNumberId = "PN-T-" + tag;
        account = TenantContext.call(office, () -> signup.complete(new EmbeddedSignupService.Request(null, null, phoneNumberId, true))).accountId();
    }

    @Test
    void connectingCreatesTheTemplatesAndOnlyApprovedOnesAreSent() {
        List<Map<String, Object>> rows = TenantContext.call(office, () -> templates.statuses());
        assertThat(rows).extracting(r -> r.get("kind"))
                .containsExactlyInAnyOrder("HANDOFF", "REMINDER", "FOLLOWUP", "REPORT", "NEW_LISTING", "CHECKIN");
        assertThat(rows).allMatch(r -> "PENDING".equals(r.get("status")));
        assertThat(TenantContext.call(office, () -> templates.resolve(account, WaTemplates.Kind.REPORT))).isEmpty();

        TenantContext.run(office, () -> {
            jdbc.sql("update wa_templates set status = 'APPROVED' where kind = 'REPORT'").update();
            jdbc.sql("update offices set owner_phone = '966551112222'").update();
        });
        assertThat(TenantContext.call(office, () -> templates.resolve(account, WaTemplates.Kind.REPORT)))
                .contains(new WaTemplates.Resolved("hidaa_daily_report_v1", "ar"));
        assertThat(TenantContext.call(office, () -> templates.resolve(account, WaTemplates.Kind.HANDOFF))).isEmpty();

        ((MockWhatsAppClient) whatsapp).reset();
        TenantContext.call(office, () -> reports.send(office, LocalDate.now(java.time.ZoneId.of("Asia/Riyadh"))));
        List<MockWhatsAppClient.Sent> sent = ((MockWhatsAppClient) whatsapp).sent().stream()
                .filter(s -> s.phoneNumberId().equals(phoneNumberId)).toList();
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).kind()).isEqualTo("template");
        assertThat(sent.get(0).to()).isEqualTo("966551112222");
        assertThat(sent.get(0).content()).startsWith("hidaa_daily_report_v1:ar:");
        String error = TenantContext.call(office, () -> jdbc.sql("select coalesce(error, '') from daily_reports").query(String.class).single());
        assertThat(error).as("sent without error").isEmpty();

        // Re-running provisioning keeps what exists.
        TenantContext.run(office, () -> templates.syncOffice());
        assertThat(TenantContext.call(office, () -> templates.resolve(account, WaTemplates.Kind.REPORT))).isPresent();
    }

    @Test
    void aNewWordingIsCreatedAndThePreviousApprovedVersionIsSentMeanwhile() {
        // The office has the previous version of the report template, approved.
        TenantContext.run(office, () -> jdbc.sql("update wa_templates set name = 'hidaa_daily_report_v0', status = 'APPROVED' where kind = 'REPORT'").update());
        assertThat(TenantContext.call(office, () -> templates.resolve(account, WaTemplates.Kind.REPORT)))
                .contains(new WaTemplates.Resolved("hidaa_daily_report_v0", "ar"));

        TenantContext.run(office, () -> templates.syncOffice());
        List<String> names = TenantContext.call(office, () -> jdbc.sql("select name from wa_templates where kind = 'REPORT' order by name")
                .query(String.class).list());
        assertThat(names).containsExactly("hidaa_daily_report_v0", "hidaa_daily_report_v1");
        assertThat(TenantContext.call(office, () -> templates.resolve(account, WaTemplates.Kind.REPORT)))
                .as("the new wording is in review").contains(new WaTemplates.Resolved("hidaa_daily_report_v0", "ar"));

        TenantContext.run(office, () -> jdbc.sql("update wa_templates set status = 'APPROVED' where name = 'hidaa_daily_report_v1'").update());
        assertThat(TenantContext.call(office, () -> templates.resolve(account, WaTemplates.Kind.REPORT)))
                .contains(new WaTemplates.Resolved("hidaa_daily_report_v1", "ar"));
        List<Map<String, Object>> report = TenantContext.call(office, () -> templates.statuses()).stream()
                .filter(r -> "REPORT".equals(r.get("kind"))).toList();
        assertThat(report).hasSize(1);
        assertThat(report.get(0).get("name")).isEqualTo("hidaa_daily_report_v1");
    }

    private static final Pattern PARAM = Pattern.compile("\\{\\{(\\d+)}}");

    /** Meta rejects templates whose variables are out of order, at the start or end, or side by side. */
    @Test
    void definitionsFollowMetasTemplateRules() {
        for (WaTemplates.Kind k : WaTemplates.Kind.values()) {
            assertThat(k.templateName).matches("[a-z0-9_]{1,512}");
            Matcher m = PARAM.matcher(k.body);
            int expected = 1, last = -1;
            while (m.find()) {
                assertThat(Integer.parseInt(m.group(1))).as(k + " numbering").isEqualTo(expected++);
                if (last >= 0) assertThat(k.body.substring(last, m.start()).trim()).as(k + " adjacent variables").isNotEmpty();
                last = m.end();
            }
            assertThat(expected - 1).as(k + " example count").isEqualTo(k.example.size());
            assertThat(k.body.trim()).as(k + " starts with a variable").doesNotStartWith("{{");
            assertThat(k.body.trim()).as(k + " ends with a variable").doesNotEndWith("}}");
            assertThat(k.body).doesNotContain("\n").hasSizeLessThan(1024);
        }
    }
}
