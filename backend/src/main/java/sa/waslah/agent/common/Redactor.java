package sa.waslah.agent.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;
import sa.waslah.agent.config.WaslahProperties;

/**
 * Masks customers' personal details for the Hidaa team's quality review and exports: a customer becomes a stable code
 * ("عميل-7F3A2C", the same in every export, unrecoverable without the server secret), and phone numbers, emails,
 * national ID / iqama numbers and IBANs typed in messages are blanked. Ad license numbers and prices stay, since
 * they are what the agent's accuracy is checked against. Names written inside a message cannot be found reliably
 * and stay as written.
 */
@Component
public class Redactor {

    private static final String D = "[0-9٠-٩]";
    static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+(?:\\.[\\w-]+)+");
    static final Pattern IBAN = Pattern.compile("(?i)\\bSA\\s?\\d{2}(?:\\s?\\d{2,4}){5,6}");
    /** Saudi mobiles: 05XXXXXXXX, 5XXXXXXXX, +966 5X…, 00966…, spaces or dashes between digits, Latin or Arabic digits. */
    static final Pattern PHONE = Pattern.compile("(?<!" + D + ")(?:(?:\\+|00|٠٠)?(?:966|٩٦٦)[\\s-]?|0|٠)?[5٥](?:[\\s-]?" + D + "){8}(?!" + D + ")");
    /** National ID or iqama: 10 digits starting with 1 or 2. */
    static final Pattern NATIONAL_ID = Pattern.compile("(?<!" + D + ")[12١٢]" + D + "{9}(?!" + D + ")");
    static final Set<String> PERSONAL_KEYS = Set.of("customer_name", "customerName", "phone", "customer_phone", "wa_id", "waId", "email");

    private final byte[] key;

    public Redactor(WaslahProperties props) {
        this.key = ("customer-code|" + props.jwtSecret()).getBytes(StandardCharsets.UTF_8);
    }

    /** "عميل-7F3A2C": the same customer of the same office always gets the same code. */
    public String customerCode(UUID officeId, String waId) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] d = mac.doFinal((officeId + "|" + waId).getBytes(StandardCharsets.UTF_8));
            return "عميل-" + HexFormat.of().withUpperCase().formatHex(d, 0, 3);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String text(String s) {
        if (s == null || s.isEmpty()) return s;
        String t = EMAIL.matcher(s).replaceAll("[بريد]");
        t = IBAN.matcher(t).replaceAll("[آيبان]");
        t = PHONE.matcher(t).replaceAll("[جوال]");
        return NATIONAL_ID.matcher(t).replaceAll("[هوية]");
    }

    /** A copy of a tool call's arguments or result with personal fields removed and text masked. */
    public static JsonNode json(JsonNode node) {
        if (node == null) return null;
        JsonNode copy = node.deepCopy();
        scrub(copy);
        return copy;
    }

    private static void scrub(JsonNode n) {
        if (n instanceof ObjectNode o) {
            for (Iterator<Map.Entry<String, JsonNode>> it = o.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                if (PERSONAL_KEYS.contains(e.getKey())) {
                    it.remove();
                } else if (e.getValue().isTextual()) {
                    e.setValue(TextNode.valueOf(text(e.getValue().asText())));
                } else {
                    scrub(e.getValue());
                }
            }
        } else if (n instanceof ArrayNode a) {
            for (int i = 0; i < a.size(); i++) {
                if (a.get(i).isTextual()) a.set(i, TextNode.valueOf(text(a.get(i).asText())));
                else scrub(a.get(i));
            }
        }
    }
}
