package sa.waslah.agent.whatsapp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;
import sa.waslah.agent.config.WaslahProperties;

/** Checks Meta's X-Hub-Signature-256 header: "sha256=" + hex(HMAC-SHA256(app secret, raw body)). */
@Component
public class WebhookSignatureVerifier {

    private final byte[] secret;

    @org.springframework.beans.factory.annotation.Autowired
    public WebhookSignatureVerifier(WaslahProperties props) {
        this(props.whatsapp().appSecret());
    }

    WebhookSignatureVerifier(String appSecret) {
        this.secret = appSecret == null ? new byte[0] : appSecret.getBytes(StandardCharsets.UTF_8);
    }

    public boolean verify(byte[] body, String header) {
        if (secret.length == 0 || header == null || !header.startsWith("sha256=") || body == null) {
            return false;
        }
        byte[] expected = sign(body);
        byte[] given;
        try {
            given = HexFormat.of().parseHex(header.substring(7));
        } catch (IllegalArgumentException e) {
            return false;
        }
        return MessageDigest.isEqual(expected, given);
    }

    public String header(byte[] body) {
        return "sha256=" + HexFormat.of().formatHex(sign(body));
    }

    private byte[] sign(byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(body);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC unavailable", e);
        }
    }
}
