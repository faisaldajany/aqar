package sa.waslah.agent.agent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

/** Provider-neutral types for a chat model with function calling. */
public final class Llm {

    private Llm() {
    }

    /** A function the model may call. {@code parameters} is a JSON Schema object. */
    public record Tool(String name, String description, ObjectNode parameters) {
    }

    public record ToolCall(String name, JsonNode args) {
    }

    /**
     * One turn. A model turn keeps the provider's raw content so it can be sent back verbatim
     * (Gemini 3 requires its thought signatures to be returned with function calls).
     */
    public sealed interface Turn permits UserText, ModelText, ModelRaw, ToolResults {
    }

    public record UserText(String text) implements Turn {
    }

    public record ModelText(String text) implements Turn {
    }

    public record ModelRaw(JsonNode content) implements Turn {
    }

    public record ToolResult(String name, JsonNode response) {
    }

    public record ToolResults(List<ToolResult> results) implements Turn {
    }

    public record Request(String system, List<Turn> turns, List<Tool> tools) {
    }

    public record Usage(int inputTokens, int cachedTokens, int outputTokens) {
        public static final Usage NONE = new Usage(0, 0, 0);

        public Usage plus(Usage o) {
            return new Usage(inputTokens + o.inputTokens, cachedTokens + o.cachedTokens, outputTokens + o.outputTokens);
        }
    }

    /** Either text, tool calls, or both. {@code raw} is the model's content to replay. */
    public record Response(String text, List<ToolCall> toolCalls, JsonNode raw, Usage usage) {
        public boolean hasToolCalls() {
            return toolCalls != null && !toolCalls.isEmpty();
        }
    }

    public interface Client {
        Response generate(Request request);

        String model();

        /** Structured extraction: returns a JSON object matching {@code schema}. */
        default JsonNode extractJson(String instruction, String input, ObjectNode schema) {
            throw new LlmException("Structured extraction is not supported by " + model());
        }
    }

    public static class LlmException extends RuntimeException {
        public LlmException(String message) {
            super(message);
        }

        public LlmException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
