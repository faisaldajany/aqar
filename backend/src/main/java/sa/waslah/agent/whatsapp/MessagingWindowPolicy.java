package sa.waslah.agent.whatsapp;

import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * WhatsApp's customer-service window: free-form replies are allowed for 24 hours after the
 * customer's last message. Outside it only approved templates may be sent (paid by the office).
 * A five-minute margin avoids racing the edge of the window.
 */
@Component
public class MessagingWindowPolicy {

    static final Duration WINDOW = Duration.ofHours(24);
    static final Duration MARGIN = Duration.ofMinutes(5);

    public boolean isOpen(Instant lastInboundAt, Instant now) {
        return lastInboundAt != null && now.isBefore(lastInboundAt.plus(WINDOW).minus(MARGIN));
    }
}
