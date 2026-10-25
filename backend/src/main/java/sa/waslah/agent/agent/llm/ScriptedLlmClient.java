package sa.waslah.agent.agent.llm;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Offline stand-in for the model. Plays queued responses in order (tests), and when the queue is
 * empty answers with a fixed Arabic line (local dev without an API key).
 */
public class ScriptedLlmClient implements Llm.Client {

    private final Deque<Llm.Response> queue = new ArrayDeque<>();
    private final List<Llm.Request> seen = new ArrayList<>();

    public synchronized ScriptedLlmClient thenText(String text) {
        queue.add(new Llm.Response(text, List.of(), JsonNodeFactory.instance.objectNode().put("role", "model"), Llm.Usage.NONE));
        return this;
    }

    public synchronized ScriptedLlmClient thenCall(String tool, String argsJson) {
        try {
            var args = new com.fasterxml.jackson.databind.ObjectMapper().readTree(argsJson);
            var raw = JsonNodeFactory.instance.objectNode().put("role", "model");
            raw.putArray("parts").addObject().putObject("functionCall").put("name", tool).set("args", args);
            queue.add(new Llm.Response("", List.of(new Llm.ToolCall(tool, args)), raw, Llm.Usage.NONE));
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
        return this;
    }

    private final Deque<com.fasterxml.jackson.databind.JsonNode> extractions = new ArrayDeque<>();

    public synchronized ScriptedLlmClient thenExtract(String json) {
        try {
            extractions.add(new com.fasterxml.jackson.databind.ObjectMapper().readTree(json));
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
        return this;
    }

    @Override
    public synchronized com.fasterxml.jackson.databind.JsonNode extractJson(String instruction, String input,
                                                                         com.fasterxml.jackson.databind.node.ObjectNode schema) {
        seen.add(new Llm.Request(instruction, List.of(new Llm.UserText(input)), List.of()));
        var next = extractions.poll();
        if (next == null) {
            throw new Llm.LlmException("No scripted extraction queued");
        }
        return next;
    }

    public synchronized void reset() {
        queue.clear();
        seen.clear();
        extractions.clear();
    }

    public synchronized List<Llm.Request> requests() {
        return List.copyOf(seen);
    }

    @Override
    public synchronized Llm.Response generate(Llm.Request request) {
        seen.add(request);
        Llm.Response next = queue.poll();
        if (next != null) {
            return next;
        }
        return new Llm.Response("هلا بك، معك المساعد الآلي للمكتب (وضع التطوير). كيف أقدر أخدمك؟", List.of(),
                JsonNodeFactory.instance.objectNode().put("role", "model"), Llm.Usage.NONE);
    }

    @Override
    public String model() {
        return "scripted";
    }
}
