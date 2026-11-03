package sa.waslah.agent.common;

import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/** Email from no-reply@hidaaco.com (password reset links). Does nothing when MAIL_HOST is not set. */
@Component
public class Mailer {

    private static final Logger log = LoggerFactory.getLogger(Mailer.class);

    private final ObjectProvider<JavaMailSender> mail;
    private final String host;
    private final String from;

    public Mailer(ObjectProvider<JavaMailSender> mail, @Value("${spring.mail.host:}") String host,
                  @Value("${waslah.support.from:no-reply@hidaaco.com}") String from) {
        this.mail = mail;
        this.host = host;
        this.from = from;
    }

    public boolean configured() {
        return host != null && !host.isBlank() && mail.getIfAvailable() != null;
    }

    /** A branded HTML email with its plain-text copy. Sends and returns true, or logs and returns false; never throws. */
    public boolean send(String to, String subject, Email email) {
        return deliver(to, subject, email.text(), email.html());
    }

    /** Plain text only. Sends and returns true, or logs and returns false; never throws. */
    public boolean send(String to, String subject, String text) {
        return deliver(to, subject, text, null);
    }

    private boolean deliver(String to, String subject, String text, String html) {
        if (!configured()) {
            log.warn("Email to {} not sent: mail is not configured (MAIL_HOST)", to);
            return false;
        }
        try {
            JavaMailSender sender = mail.getObject();
            MimeMessage msg = sender.createMimeMessage();
            MimeMessageHelper h = new MimeMessageHelper(msg, html != null, "UTF-8");
            h.setFrom(from, "حداء");
            h.setTo(to);
            h.setSubject(subject);
            if (html != null) h.setText(text, html);
            else h.setText(text, false);
            sender.send(msg);
            log.info("Email \"{}\" sent to {}", subject, mask(to));
            return true;
        } catch (Exception e) {
            log.warn("Email to {} not sent: {}", to, e.getMessage());
            return false;
        }
    }

    /** "fa***@gmail.com": enough to tell which user in the logs. */
    static String mask(String email) {
        if (email == null) return "";
        int at = email.indexOf('@');
        if (at <= 0) return "***";
        return email.substring(0, Math.min(2, at)) + "***" + email.substring(at);
    }
}
