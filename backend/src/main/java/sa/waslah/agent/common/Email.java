package sa.waslah.agent.common;

import java.util.List;

/**
 * A Hidaa email: an Arabic, right-to-left message with the brand header, an optional button and a footer, sent as
 * HTML with a plain-text copy for clients that do not show HTML. Built with tables and inline styles, which is what
 * email clients (Gmail, Outlook, Apple Mail) render reliably; no images or web fonts, which they often block.
 *
 * @param preheader  the grey line inboxes show after the subject
 * @param greeting   "مرحباً فيصل،", or null
 * @param paragraphs the message
 * @param code       a one-time code shown large under the message (e.g. "482913"), or null
 * @param button     the call to action, or null
 * @param note       a smaller line under the button (why they got this, what if it was not them), or null
 */
public record Email(String preheader, String greeting, List<String> paragraphs, String code, Button button, String note) {

    public Email(String preheader, String greeting, List<String> paragraphs, Button button, String note) {
        this(preheader, greeting, paragraphs, null, button, note);
    }

    public record Button(String label, String url) {
    }

    static final String NIGHT = "#17213F";
    static final String SADU = "#A82B2E";
    static final String MUTED = "#5D6478";
    static final String LINE = "#E1E4EC";
    static final String BG = "#F5F6F9";
    static final String FONT = "Tahoma, 'Segoe UI', Arial, sans-serif";

    /** "مرحباً فيصل،" from a full name, or "مرحباً،" when there is none. */
    public static String greet(String fullName) {
        String first = fullName == null ? "" : fullName.trim().split("\\s+")[0];
        return first.isEmpty() ? "مرحباً،" : "مرحباً " + first + "،";
    }

    public String text() {
        StringBuilder b = new StringBuilder();
        if (greeting != null) b.append(greeting).append("\n\n");
        for (String p : paragraphs) b.append(p).append("\n\n");
        if (code != null) b.append("الرمز: ").append(code).append("\n\n");
        if (button != null) b.append(button.label()).append(":\n").append(button.url()).append("\n\n");
        if (note != null) b.append(note).append("\n\n");
        b.append("فريق حداء\nsupport@hidaaco.com\n");
        return b.toString();
    }

    public String html() {
        StringBuilder body = new StringBuilder();
        if (greeting != null) body.append(p(esc(greeting), "font-weight:bold;"));
        for (String para : paragraphs) body.append(p(esc(para), ""));
        if (code != null) {
            body.append("""
                    <table role="presentation" cellpadding="0" cellspacing="0" border="0" style="margin:6px 0 10px 0;">
                      <tr><td dir="ltr" align="center" bgcolor="%s" style="padding:14px 24px;border:1px solid %s;border-radius:10px;font-family:Menlo,Consolas,'Courier New',monospace;font-size:30px;font-weight:bold;letter-spacing:8px;color:%s;">%s</td></tr>
                    </table>
                    """.formatted(BG, LINE, NIGHT, esc(code)));
        }
        if (button != null) {
            String url = esc(button.url());
            body.append("""
                    <table role="presentation" cellpadding="0" cellspacing="0" border="0" style="margin:24px 0 8px 0;">
                      <tr><td align="center" bgcolor="%s" style="border-radius:10px;">
                        <a href="%s" target="_blank" style="display:inline-block;padding:13px 28px;font-family:%s;font-size:16px;font-weight:bold;color:#FFFFFF;text-decoration:none;border-radius:10px;">%s</a>
                      </td></tr>
                    </table>
                    <p style="margin:16px 0 0 0;font-family:%s;font-size:12px;line-height:1.7;color:%s;">إذا لم يعمل الزر، اضغط هذا الرابط:</p>
                    <p dir="ltr" style="margin:2px 0 0 0;font-family:Menlo,Consolas,monospace;font-size:12px;line-height:1.6;word-break:break-all;text-align:left;"><a href="%s" target="_blank" style="color:%s;text-decoration:underline;">%s</a></p>
                    """.formatted(NIGHT, url, FONT, esc(button.label()), FONT, MUTED, url, SADU, url));
        }
        if (note != null) {
            body.append("<p style=\"margin:20px 0 0 0;padding-top:16px;border-top:1px solid %s;font-family:%s;font-size:13px;line-height:1.8;color:%s;\">%s</p>"
                    .formatted(LINE, FONT, MUTED, esc(note)));
        }
        return """
                <!doctype html>
                <html lang="ar" dir="rtl">
                <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <meta name="color-scheme" content="light">
                <title>حداء</title>
                </head>
                <body style="margin:0;padding:0;background:%1$s;" dir="rtl">
                <div style="display:none;max-height:0;overflow:hidden;opacity:0;color:transparent;">%2$s</div>
                <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" border="0" bgcolor="%1$s" dir="rtl">
                  <tr><td align="center" style="padding:28px 12px;">
                    <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" border="0" style="max-width:520px;" dir="rtl">
                      <tr><td align="right" dir="ltr" style="padding:0 6px 14px 6px;font-family:%3$s;font-size:26px;font-weight:bold;color:%4$s;text-align:right;">
                        <span style="color:%4$s;font-size:15px;letter-spacing:1px;">&#9679;&#9679;</span><span style="color:%5$s;font-size:17px;vertical-align:super;">&#9679;</span>&nbsp;<span dir="rtl">حداء</span>
                      </td></tr>
                      <tr><td bgcolor="#FFFFFF" align="right" dir="rtl" style="padding:28px 26px;border:1px solid %6$s;border-radius:14px;text-align:right;">
                %7$s
                      </td></tr>
                      <tr><td align="center" style="padding:18px 6px 0 6px;font-family:%3$s;font-size:12px;line-height:1.8;color:%8$s;" dir="rtl">
                        فريق حداء · <a href="mailto:support@hidaaco.com" style="color:%8$s;">support@hidaaco.com</a><br>
                        وكيل لمكاتب العقار · <a href="https://hidaaco.com" style="color:%8$s;">hidaaco.com</a>
                      </td></tr>
                    </table>
                  </td></tr>
                </table>
                </body>
                </html>
                """.formatted(BG, esc(preheader == null ? "" : preheader), FONT, NIGHT, SADU, LINE, body, MUTED);
    }

    private static String p(String html, String extra) {
        return "<p style=\"margin:0 0 12px 0;font-family:%s;font-size:15px;line-height:1.9;color:%s;%s\">%s</p>\n"
                .formatted(FONT, NIGHT, extra, html);
    }

    static String esc(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            switch (c) {
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '&' -> b.append("&amp;");
                case '"' -> b.append("&quot;");
                case '\'' -> b.append("&#39;");
                default -> b.append(c);
            }
        }
        return b.toString();
    }
}
