package sa.waslah.agent.whatsapp;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import sa.waslah.agent.common.ApiException;
import sa.waslah.agent.config.WaslahProperties;

/**
 * Graph API calls used during Embedded Signup. Endpoints are listed in docs/meta-integration.md
 * with the items that must be checked against Meta's current documentation before going live.
 */
public class MetaGraphClient {

    public record PhoneInfo(String displayPhone, String verifiedName) {
    }

    private final RestClient http;
    private final WaslahProperties.WhatsApp cfg;

    public MetaGraphClient(RestClient.Builder builder, WaslahProperties.WhatsApp cfg) {
        this.cfg = cfg;
        this.http = builder.baseUrl("https://graph.facebook.com/" + cfg.graphVersion()).build();
    }

    /** Exchanges the code returned by FB.login for a business integration system user token. */
    public String exchangeCode(String code) {
        JsonNode res = call(() -> http.get()
                .uri(b -> b.path("/oauth/access_token")
                        .queryParam("client_id", cfg.appId())
                        .queryParam("client_secret", cfg.appSecret())
                        .queryParam("code", code)
                        .build())
                .retrieve().body(JsonNode.class));
        String token = res == null ? "" : res.path("access_token").asText("");
        if (token.isBlank()) {
            throw ApiException.badGateway("meta_token_failed", "تعذّر إكمال الربط مع Meta. حاول مرة أخرى.");
        }
        return token;
    }

    /** Subscribes our app to the office's WABA so its webhooks reach us. */
    public void subscribeApp(String wabaId, String token) {
        call(() -> http.post().uri("/{waba}/subscribed_apps", wabaId)
                .headers(h -> h.setBearerAuth(token))
                .retrieve().body(JsonNode.class));
    }

    public PhoneInfo phoneInfo(String phoneNumberId, String token) {
        JsonNode res = call(() -> http.get()
                .uri(b -> b.path("/{id}").queryParam("fields", "display_phone_number,verified_name").build(phoneNumberId))
                .headers(h -> h.setBearerAuth(token))
                .retrieve().body(JsonNode.class));
        return new PhoneInfo(
                res == null ? null : res.path("display_phone_number").asText(null),
                res == null ? null : res.path("verified_name").asText(null));
    }

    /**
     * Coexistence (number stays on the WhatsApp Business app): asks Meta to sync the app's contacts
     * and then its recent chat history. Must be called soon after onboarding.
     */
    public void startCoexistenceSync(String phoneNumberId, String token) {
        for (String syncType : new String[] {"smb_app_state_sync", "history"}) {
            call(() -> http.post().uri("/{id}/smb_app_data", phoneNumberId)
                    .headers(h -> h.setBearerAuth(token))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("messaging_product", "whatsapp", "sync_type", syncType))
                    .retrieve().body(JsonNode.class));
        }
    }

    /** Only for numbers moved fully to the Cloud API (not coexistence). */
    public void register(String phoneNumberId, String token, String pin) {
        call(() -> http.post().uri("/{id}/register", phoneNumberId)
                .headers(h -> h.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("messaging_product", "whatsapp", "pin", pin))
                .retrieve().body(JsonNode.class));
    }

    private JsonNode call(java.util.function.Supplier<JsonNode> request) {
        try {
            return request.get();
        } catch (RestClientResponseException e) {
            throw ApiException.badGateway("meta_error", "رفضت Meta الطلب (" + e.getStatusCode().value() + ").");
        }
    }
}
