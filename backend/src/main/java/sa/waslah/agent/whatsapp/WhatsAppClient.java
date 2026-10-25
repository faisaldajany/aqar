package sa.waslah.agent.whatsapp;

import java.util.List;

/** Sends messages through one office's WhatsApp number. Returns Meta's message id (wamid). */
public interface WhatsAppClient {

    record Credentials(String phoneNumberId, String accessToken) {
    }

    String sendText(Credentials from, String toWaId, String body);

    String sendTemplate(Credentials from, String toWaId, String templateName, String languageCode, List<String> bodyParams);

    void markRead(Credentials from, String waMessageId);
}
