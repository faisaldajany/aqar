package sa.waslah.agent.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class EmailTest {

    Email email = new Email("سطر المعاينة", Email.greet("فيصل العتيبي"),
            List.of("فقرة <b>غير موثوقة</b> & رمز"),
            new Email.Button("تعيين كلمة مرور", "https://app.hidaaco.com/reset?token=a_b-c&x=1"),
            "إذا لم تطلب ذلك فتجاهل الرسالة.");

    @Test
    void htmlIsRightToLeftBrandedAndEscaped() {
        String html = email.html();
        assertThat(html).contains("<html lang=\"ar\" dir=\"rtl\">").contains("حداء").contains("مرحباً فيصل،")
                .contains("href=\"https://app.hidaaco.com/reset?token=a_b-c&amp;x=1\"")
                .doesNotContain(">https://app.hidaaco.com/reset")
                .contains("فقرة &lt;b&gt;غير موثوقة&lt;/b&gt; &amp; رمز")
                .contains("support@hidaaco.com")
                .doesNotContain("<b>غير");
    }

    @Test
    void plainTextKeepsTheLinkUsable() {
        assertThat(email.text()).contains("https://app.hidaaco.com/reset?token=a_b-c&x=1").startsWith("مرحباً فيصل،");
        assertThat(Email.greet(null)).isEqualTo("مرحباً،");
        assertThat(Mailer.mask("faisal@gmail.com")).isEqualTo("fa***@gmail.com");
    }

    @Test
    void invoiceLinesAreATableAndPlainLines() {
        Email e = new Email("إيصال", Email.greet("فيصل"), List.of("وصلنا دفعتك."), null, null, null,
                List.of(new Email.Line("الضريبة", "29.85 ريال"), new Email.Line("الإجمالي", "228.85 <ريال>", true)));
        assertThat(e.html()).contains("<table role=\"presentation\"").contains("الضريبة").contains("228.85 &lt;ريال&gt;")
                .contains("font-size:17px;font-weight:bold");
        assertThat(e.text()).contains("الضريبة: 29.85 ريال\nالإجمالي: 228.85 <ريال>");
        assertThat(email.lines()).isEmpty();
    }
}
