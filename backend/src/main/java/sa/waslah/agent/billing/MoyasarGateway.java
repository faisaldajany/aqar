package sa.waslah.agent.billing;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Moyasar hosted invoices (https://docs.moyasar.com): POST /v1/invoices returns {id, status, url};
 * GET /v1/invoices/{id} tells us whether it is paid (or expired); GET /v1/payments/{id} gives one payment attempt
 * (failed, refunded). Basic auth with the secret key as the username.
 */
public class MoyasarGateway implements PaymentGateway {

    private final RestClient http;
    private final String name;

    public MoyasarGateway(String secretKey) {
        this(secretKey, "https://api.moyasar.com/v1", "moyasar");
    }

    /** The same gateway with a test key, recorded on invoices under its own name so they are checked with that key. */
    public static MoyasarGateway testMode(String testSecretKey) {
        return new MoyasarGateway(testSecretKey, "https://api.moyasar.com/v1", PaymentGateways.TEST);
    }

    MoyasarGateway(String secretKey, String baseUrl) {
        this(secretKey, baseUrl, "moyasar");
    }

    MoyasarGateway(String secretKey, String baseUrl, String name) {
        this.name = name;
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
        return name;
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

    /** GET /v1/payments/{id}: status, amount, refunded, invoice_id and source.message (the bank's or Moyasar's reason). */
    @Override
    public Optional<Payment> payment(String paymentId) {
        if (paymentId == null || paymentId.isBlank()) return Optional.empty();
        try {
            JsonNode r = http.get().uri("/payments/{id}", paymentId).retrieve().body(JsonNode.class);
            if (r == null || !r.hasNonNull("id")) return Optional.empty();
            return Optional.of(payment(r));
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        }
    }

    static Payment payment(JsonNode r) {
        JsonNode src = r.path("source");
        String type = src.path("type").asText("");
        String method = src.hasNonNull("company") ? src.path("company").asText()
                : switch (type) { case "applepay" -> "applepay"; case "samsungpay" -> "samsungpay"; case "stcpay" -> "stcpay"; default -> null; };
        String digits = src.path("number").asText("").replaceAll("\\D", "");
        return new Payment(r.path("id").asText(), r.path("status").asText(""), r.path("amount").asLong(), r.path("refunded").asLong(),
                r.path("invoice_id").asText(null), src.path("message").asText(null), method,
                digits.length() >= 4 ? digits.substring(digits.length() - 4) : null, src.path("reference_number").asText(null));
    }

    /** GET /v1/invoices/{id}: the payment in its payments list that went through. */
    @Override
    public Optional<Payment> paidPayment(String providerRef) {
        try {
            JsonNode r = http.get().uri("/invoices/{id}", providerRef).retrieve().body(JsonNode.class);
            if (r == null) return Optional.empty();
            for (JsonNode p : r.path("payments")) {
                String st = p.path("status").asText("");
                if (st.equals("paid") || st.equals("captured") || st.equals("refunded")) return Optional.of(payment(p));
            }
            return Optional.empty();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    @Override
    public String invoiceStatus(String providerRef) {
        try {
            JsonNode r = http.get().uri("/invoices/{id}", providerRef).retrieve().body(JsonNode.class);
            return r == null ? null : r.path("status").asText(null);
        } catch (HttpClientErrorException.NotFound e) {
            return null;
        }
    }
}
