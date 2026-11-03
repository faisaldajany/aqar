package sa.waslah.agent.common;

import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/** Plain-text email from no-reply@hidaaco.com (password reset links). Does nothing when MAIL_HOST is not set. */
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

    /** Sends and returns true, or logs and returns false; never throws. */
    public boolean send(String to, String subject, String text) {
        if (!configured()) {
            log.warn("Email to {} not sent: mail is not configured (MAIL_HOST)", to);
            return false;
        }
        try {
            JavaMailSender sender = mail.getObject();
            MimeMessage msg = sender.createMimeMessage();
            MimeMessageHelper h = new MimeMessageHelper(msg, false, "UTF-8");
            h.setFrom(from, "حداء");
            h.setTo(to);
            h.setSubject(subject);
            h.setText(text, false);
            sender.send(msg);
            return true;
        } catch (Exception e) {
            log.warn("Email to {} not sent: {}", to, e.getMessage());
            return false;
        }
    }
}
