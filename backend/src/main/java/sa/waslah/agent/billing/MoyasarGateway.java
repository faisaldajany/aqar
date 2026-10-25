package sa.waslah.agent.billing;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Moyasar hosted invoices (https://docs.moyasar.com): POST /v1/invoices returns {id, status, url};
 * GET /v1/invoices/{id} tells us whether it is paid. Basic auth with the secret key as the username.
 */
public class MoyasarGateway implements PaymentGateway {

    private final RestClient http;

    public MoyasarGateway(String secretKey) {
        this(secretKey, "https://api.moyasar.com/v1");
    }

    MoyasarGateway(String secretKey, String baseUrl) {
        if (secretKey == null || secretKey.isBlank()) {
            throw new IllegalStateException("waslah.billing.moyasar-secret-key is required when provider=moyasar");
        }
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout((int) Duration.ofSeconds(10).toMillis());
        rf.setReadTimeout((int) Duration.ofSeconds(20).toMillis());
        String auth = Base64.getEncoder().encodeToString((secretKey + ":").getBytes(StandardCharsets.UTF_8));
        this.http = RestClient.builder().baseUrl(baseUrl).requestFactory(rf)
                .defaultHeader("Authorization", "Basic " + auth).build();
    }

    @Override
    public String name() {
        return "moyasar";
    }

    @Override
    public Checkout create(long amountHalalas, String description, String invoiceId, String successUrl, String callbackUrl) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", amountHalalas);
        body.put("currency", "SAR");
        body.put("description", description);
        body.put("success_url", successUrl);
        body.put("back_url", successUrl);
        if (callbackUrl != null) body.put("callback_url", callbackUrl);
        body.put("expired_at", Instant.now().plus(Duration.ofDays(7)).toString());
        body.put("metadata", Map.of("waslah_invoice_id", invoiceId));
        JsonNode r = http.post().uri("/invoices").contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve().body(JsonNode.class);
        if (r == null || !r.hasNonNull("id") || !r.hasNonNull("url")) {
            throw new IllegalStateException("Moyasar did not return an invoice");
        }
        return new Checkout(r.get("id").asText(), r.get("url").asText());
    }

    @Override
    public boolean isPaid(String providerRef) {
        JsonNode r = http.get().uri("/invoices/{id}", providerRef).retrieve().body(JsonNode.class);
        return r != null && "paid".equalsIgnoreCase(r.path("status").asText());
    }
}
