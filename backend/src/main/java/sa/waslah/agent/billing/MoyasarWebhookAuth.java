package sa.waslah.agent.billing;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Whether a Moyasar notification comes from Moyasar. Two kinds arrive at /webhooks/moyasar:
 * <ul>
 *   <li>the dashboard webhook (Settings → Webhooks), whose body carries the Secret Token typed there, and</li>
 *   <li>the invoice's own callback_url, which carries no token in its body, so the URL we give Moyasar for it ends in
 *       {@code ?token=} with a value derived from the same secret (the secret itself never appears in a URL or a log).</li>
 * </ul>
 * Either is enough. This only keeps strangers out: the paid status itself is always asked from Moyasar's API.
 */
final class MoyasarWebhookAuth {

    private MoyasarWebhookAuth() {
    }

    static boolean configured(String secret) {
        return secret != null && !secret.isBlank();
    }

    /** The value for {@code ?token=} on invoice callback URLs. */
    static String callbackToken(String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.trim().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] h = mac.doFinal("moyasar-invoice-callback".getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(h);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static boolean allowed(String secret, JsonNode body, String queryToken) {
        if (!configured(secret)) return true;
        JsonNode t = body == null ? null : body.path("secret_token");
        if (t != null && t.isTextual() && same(t.asText(), secret.trim())) return true;
        return queryToken != null && same(queryToken, callbackToken(secret));
    }

    private static boolean same(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
