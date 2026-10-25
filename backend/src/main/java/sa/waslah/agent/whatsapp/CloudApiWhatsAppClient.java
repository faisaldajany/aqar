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

    @Override
    public String sendLocation(Credentials from, String toWaId, double latitude, double longitude, String name, String address) {
        Map<String, Object> loc = new LinkedHashMap<>();
        loc.put("latitude", latitude);
        loc.put("longitude", longitude);
        if (name != null) loc.put("name", name);
        if (address != null) loc.put("address", address);
        return post(from, Map.of("messaging_product", "whatsapp", "to", toWaId, "type", "location", "location", loc));
    }

    @Override
    public String sendImageLink(Credentials from, String toWaId, String url, String caption) {
        Map<String, Object> img = new LinkedHashMap<>();
        img.put("link", url);
        if (caption != null) img.put("caption", caption);
        return post(from, Map.of("messaging_product", "whatsapp", "to", toWaId, "type", "image", "image", img));
    }

    @Override
    public String uploadMedia(Credentials from, byte[] bytes, String contentType) {
        org.springframework.util.LinkedMultiValueMap<String, Object> form = new org.springframework.util.LinkedMultiValueMap<>();
        form.add("messaging_product", "whatsapp");
        form.add("type", contentType);
        org.springframework.http.HttpHeaders partHeaders = new org.springframework.http.HttpHeaders();
        partHeaders.setContentType(MediaType.parseMediaType(contentType));
        form.add("file", new org.springframework.http.HttpEntity<>(new org.springframework.core.io.ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return "photo." + (contentType.endsWith("png") ? "png" : contentType.endsWith("webp") ? "webp" : "jpg");
            }
        }, partHeaders));
        try {
            JsonNode res = http.post().uri("/{id}/media", from.phoneNumberId())
                    .headers(h -> h.setBearerAuth(from.accessToken()))
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(form)
                    .retrieve().body(JsonNode.class);
            String id = res == null ? "" : res.path("id").asText("");
            if (id.isBlank()) throw ApiException.badGateway("wa_media_failed", "تعذّر رفع الصورة إلى واتساب.");
            return id;
        } catch (RestClientResponseException e) {
            throw ApiException.badGateway("wa_media_failed", "رفض واتساب الصورة: " + e.getStatusCode().value());
        }
    }

    @Override
    public String sendImageId(Credentials from, String toWaId, String mediaId, String caption) {
        Map<String, Object> img = new LinkedHashMap<>();
        img.put("id", mediaId);
        if (caption != null) img.put("caption", caption);
        return post(from, Map.of("messaging_product", "whatsapp", "to", toWaId, "type", "image", "image", img));
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
