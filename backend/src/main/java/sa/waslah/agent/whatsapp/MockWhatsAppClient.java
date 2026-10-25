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

    @Override
    public String sendLocation(Credentials from, String toWaId, double latitude, double longitude, String name, String address) {
        return record(from, toWaId, "location", latitude + "," + longitude + " " + name);
    }

    @Override
    public String sendImageLink(Credentials from, String toWaId, String url, String caption) {
        return record(from, toWaId, "image", url);
    }

    @Override
    public String uploadMedia(Credentials from, byte[] bytes, String contentType) {
        return "media.mock." + UUID.randomUUID();
    }

    @Override
    public String sendImageId(Credentials from, String toWaId, String mediaId, String caption) {
        return record(from, toWaId, "image", mediaId);
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
