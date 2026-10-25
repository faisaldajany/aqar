package sa.waslah.agent.dev;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import sa.waslah.agent.whatsapp.WebhookProcessor;

/**
 * Local-only helpers that feed fake WhatsApp webhooks through the real processor, so the portal
 * can be exercised without a Meta app. Enabled only when DEV_TOOLS=true; never in production.
 */
@RestController
@RequestMapping("/dev")
@ConditionalOnProperty(name = "waslah.dev-tools-enabled", havingValue = "true")
public class DevSimulatorController {

    public record CustomerMessage(String phoneNumberId, String from, String name, String text) {
    }

    public record MarketerEcho(String phoneNumberId, String to, String text) {
    }

    private final WebhookProcessor processor;
    private final ObjectMapper json;

    public DevSimulatorController(WebhookProcessor processor, ObjectMapper json) {
        this.processor = processor;
        this.json = json;
    }

    @PostMapping("/simulate-inbound")
    Map<String, String> inbound(@RequestBody CustomerMessage m) throws Exception {
        String id = "wamid.sim." + UUID.randomUUID();
        Map<String, Object> value = Map.of(
                "messaging_product", "whatsapp",
                "metadata", Map.of("phone_number_id", m.phoneNumberId()),
                "contacts", List.of(Map.of("wa_id", m.from(), "profile", Map.of("name", m.name() == null ? "" : m.name()))),
                "messages", List.of(Map.of(
                        "from", m.from(), "id", id, "timestamp", String.valueOf(Instant.now().getEpochSecond()),
                        "type", "text", "text", Map.of("body", m.text()))));
        processor.process(json.writeValueAsBytes(envelope("messages", value)));
        return Map.of("wamid", id);
    }

    @PostMapping("/simulate-echo")
    Map<String, String> echo(@RequestBody MarketerEcho m) throws Exception {
        String id = "wamid.sim." + UUID.randomUUID();
        Map<String, Object> value = Map.of(
                "messaging_product", "whatsapp",
                "metadata", Map.of("phone_number_id", m.phoneNumberId()),
                "message_echoes", List.of(Map.of(
                        "from", "office", "to", m.to(), "id", id,
                        "timestamp", String.valueOf(Instant.now().getEpochSecond()),
                        "type", "text", "text", Map.of("body", m.text()))));
        processor.process(json.writeValueAsBytes(envelope("smb_message_echoes", value)));
        return Map.of("wamid", id);
    }

    private static Map<String, Object> envelope(String field, Map<String, Object> value) {
        return Map.of("object", "whatsapp_business_account",
                "entry", List.of(Map.of("id", "sim-waba", "changes", List.of(Map.of("field", field, "value", value)))));
    }
}
