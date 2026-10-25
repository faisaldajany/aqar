package sa.waslah.agent.whatsapp;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import sa.waslah.agent.common.ApiException;

/** WhatsApp Cloud API: POST https://graph.facebook.com/{version}/{phone_number_id}/messages */
public class CloudApiWhatsAppClient implements WhatsAppClient {

    private final RestClient http;

    public CloudApiWhatsAppClient(RestClient.Builder builder, String graphVersion) {
        this.http = builder.baseUrl("https://graph.facebook.com/" + graphVersion).build();
    }

    @Override
    public String sendText(Credentials from, String toWaId, String body) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("messaging_product", "whatsapp");
        payload.put("recipient_type", "individual");
        payload.put("to", toWaId);
        payload.put("type", "text");
        payload.put("text", Map.of("preview_url", false, "body", body));
        return post(from, payload);
    }

    @Override
    public String sendTemplate(Credentials from, String toWaId, String templateName, String languageCode, List<String> bodyParams) {
        List<Map<String, Object>> params = new ArrayList<>();
        for (String p : bodyParams) {
            params.add(Map.of("type", "text", "text", p));
        }
        Map<String, Object> template = new LinkedHashMap<>();
        template.put("name", templateName);
        template.put("language", Map.of("code", languageCode));
        if (!params.isEmpty()) {
            template.put("components", List.of(Map.of("type", "body", "parameters", params)));
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("messaging_product", "whatsapp");
        payload.put("to", toWaId);
        payload.put("type", "template");
        payload.put("template", template);
        return post(from, payload);
    }

    @Override
    public void markRead(Credentials from, String waMessageId) {
        try {
            http.post().uri("/{id}/messages", from.phoneNumberId())
                    .headers(h -> h.setBearerAuth(from.accessToken()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("messaging_product", "whatsapp", "status", "read", "message_id", waMessageId))
                    .retrieve().toBodilessEntity();
        } catch (RestClientResponseException ignored) {
            // A failed read receipt must never block a reply.
        }
    }

    private String post(Credentials from, Map<String, Object> payload) {
        try {
            JsonNode res = http.post().uri("/{id}/messages", from.phoneNumberId())
                    .headers(h -> h.setBearerAuth(from.accessToken()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(JsonNode.class);
            if (res == null || !res.path("messages").isArray() || res.path("messages").isEmpty()) {
                throw ApiException.badGateway("wa_send_failed", "لم يؤكد واتساب إرسال الرسالة.");
            }
            return res.path("messages").get(0).path("id").asText();
        } catch (RestClientResponseException e) {
            throw ApiException.badGateway("wa_send_failed", "رفض واتساب الرسالة: " + e.getStatusCode().value());
        }
    }
}
