package sa.waslah.agent.conversation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import sa.waslah.agent.common.ApiException;
import sa.waslah.agent.conversation.ConversationStore.Sender;
import sa.waslah.agent.whatsapp.OutboundMessageService;

@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    public record Reply(@NotBlank @Size(max = 4096) String text) {
    }

    private final ConversationStore store;
    private final OutboundMessageService outbound;

    public ConversationController(ConversationStore store, OutboundMessageService outbound) {
        this.store = store;
        this.outbound = outbound;
    }

    @GetMapping
    List<Map<String, Object>> list(@RequestParam(defaultValue = "100") int limit) {
        return store.list(Math.min(Math.max(limit, 1), 200));
    }

    @GetMapping("/{id}/messages")
    List<Map<String, Object>> messages(@PathVariable UUID id, @RequestParam(defaultValue = "200") int limit) {
        requireExists(id);
        return store.messages(id, Math.min(Math.max(limit, 1), 500));
    }

    /** The office replies itself; the agent stays silent here until resumed. */
    @PostMapping("/{id}/takeover")
    Map<String, Object> takeover(@PathVariable UUID id) {
        requireExists(id);
        store.pauseAgent(id, ConversationStore.TAKEN_OVER);
        return Map.of("agentActive", false);
    }

    @PostMapping("/{id}/resume")
    Map<String, Object> resume(@PathVariable UUID id) {
        requireExists(id);
        store.resumeAgent(id);
        return Map.of("agentActive", true);
    }

    @PostMapping("/{id}/messages")
    Map<String, Object> reply(@PathVariable UUID id, @Valid @RequestBody Reply reply) {
        UUID messageId = outbound.sendText(id, reply.text(), Sender.HUMAN);
        return Map.of("id", messageId);
    }

    private void requireExists(UUID id) {
        if (store.find(id).isEmpty()) {
            throw ApiException.notFound("no_conversation", "المحادثة غير موجودة.");
        }
    }
}
