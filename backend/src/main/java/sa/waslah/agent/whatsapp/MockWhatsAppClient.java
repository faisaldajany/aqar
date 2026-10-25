package sa.waslah.agent.whatsapp;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Records outbound messages instead of calling Meta. Used in local dev and tests. */
public class MockWhatsAppClient implements WhatsAppClient {

    private static final Logger log = LoggerFactory.getLogger(MockWhatsAppClient.class);

    public record Sent(String phoneNumberId, String to, String kind, String content, String wamid) {
    }

    private final List<Sent> sent = new CopyOnWriteArrayList<>();

    @Override
    public String sendText(Credentials from, String toWaId, String body) {
        return record(from, toWaId, "text", body);
    }

    @Override
    public String sendTemplate(Credentials from, String toWaId, String templateName, String languageCode, List<String> bodyParams) {
        return record(from, toWaId, "template", templateName + ":" + languageCode + ":" + bodyParams);
    }

    @Override
    public void markRead(Credentials from, String waMessageId) {
        // nothing to do
    }

    public List<Sent> sent() {
        return List.copyOf(sent);
    }

    public void reset() {
        sent.clear();
    }

    private String record(Credentials from, String to, String kind, String content) {
        String wamid = "wamid.mock." + UUID.randomUUID();
        sent.add(new Sent(from.phoneNumberId(), to, kind, content, wamid));
        log.info("[mock whatsapp] {} -> {} ({}): {}", from.phoneNumberId(), to, kind, content);
        return wamid;
    }
}
