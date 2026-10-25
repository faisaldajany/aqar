package sa.waslah.agent.whatsapp;

import java.util.List;

/** Sends messages through one office's WhatsApp number. Returns Meta's message id (wamid). */
public interface WhatsAppClient {

    record Credentials(String phoneNumberId, String accessToken) {
    }

    String sendText(Credentials from, String toWaId, String body);

    String sendTemplate(Credentials from, String toWaId, String templateName, String languageCode, List<String> bodyParams);

    void markRead(Credentials from, String waMessageId);

    /** A map pin. */
    String sendLocation(Credentials from, String toWaId, double latitude, double longitude, String name, String address);

    /** An image by public HTTPS link. */
    String sendImageLink(Credentials from, String toWaId, String url, String caption);

    /** Uploads image bytes to Meta and returns the media id for {@link #sendImageId}. */
    String uploadMedia(Credentials from, byte[] bytes, String contentType);

    String sendImageId(Credentials from, String toWaId, String mediaId, String caption);
}
