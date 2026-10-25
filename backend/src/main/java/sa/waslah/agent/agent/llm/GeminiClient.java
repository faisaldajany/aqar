package sa.waslah.agent.agent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.ResourceAccessException;

/**
 * Gemini Developer API, generateContent with function calling.
 * POST https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent
 */
public class GeminiClient implements Llm.Client {

    /**
     * @param maxRetries retries on 429 (rate limit) and 500/503 (temporary). Keep it low in production,
     *                   since a customer is waiting; the eval uses more.
     */
    public record Settings(String apiKey, String model, String thinkingLevel, double temperature, int maxOutputTokens,
                           Duration timeout, int maxRetries) {
    }

    private final RestClient http;
    private final ObjectMapper json;
    private final Settings s;

    public GeminiClient(RestClient.Builder builder, ObjectMapper json, Settings settings) {
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout((int) Duration.ofSeconds(5).toMillis());
        rf.setReadTimeout((int) settings.timeout().toMillis());
        this.http = builder.baseUrl("https://generativelanguage.googleapis.com/v1beta").requestFactory(rf).build();
        this.json = json;
        this.s = settings;
    }

    @Override
    public String model() {
        return s.model();
    }

    @Override
    public Llm.Response generate(Llm.Request req) {
        ObjectNode body = buildBody(req);
        for (int attempt = 0; ; attempt++) {
            try {
                JsonNode res = http.post()
                        .uri("/models/{model}:generateContent", s.model())
                        .header("x-goog-api-key", s.apiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .body(JsonNode.class);
                return parse(res);
            } catch (RestClientResponseException e) {
                int code = e.getStatusCode().value();
                boolean retryable = code == 429 || code == 500 || code == 503;
                if (!retryable || attempt >= s.maxRetries()) {
                    throw new Llm.LlmException("Gemini " + code + ": " + truncate(e.getResponseBodyAsString()), e);
                }
                sleep(backoff(e.getResponseBodyAsString(), attempt));
            } catch (ResourceAccessException e) {
                throw new Llm.LlmException("Gemini unreachable or timed out", e);
            }
        }
    }

    @Override
    public JsonNode extractJson(String instruction, String input, ObjectNode schema) {
        ObjectNode body = json.createObjectNode();
        body.putObject("systemInstruction").putArray("parts").addObject().put("text", instruction);
        body.putArray("contents").addObject().put("role", "user").putArray("parts").addObject().put("text", input);
        ObjectNode gen = body.putObject("generationConfig");
        gen.put("temperature", 0.0);
        gen.put("maxOutputTokens", 2048);
        gen.put("responseMimeType", "application/json");
        gen.set("responseSchema", schema);
        if (s.thinkingLevel() != null && !s.thinkingLevel().isBlank()) {
            gen.putObject("thinkingConfig").put("thinkingLevel", s.thinkingLevel());
        }
        for (int attempt = 0; ; attempt++) {
            try {
                JsonNode res = http.post()
                        .uri("/models/{model}:generateContent", s.model())
                        .header("x-goog-api-key", s.apiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .body(JsonNode.class);
                String text = parse(res).text();
                return json.readTree(text);
            } catch (RestClientResponseException e) {
                int code = e.getStatusCode().value();
                if (!(code == 429 || code == 500 || code == 503) || attempt >= s.maxRetries()) {
                    throw new Llm.LlmException("Gemini " + code + ": " + truncate(e.getResponseBodyAsString()), e);
                }
                sleep(backoff(e.getResponseBodyAsString(), attempt));
            } catch (ResourceAccessException e) {
                throw new Llm.LlmException("Gemini unreachable or timed out", e);
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new Llm.LlmException("Gemini returned invalid JSON", e);
            }
        }
    }

    /** Uses the server's suggested retryDelay (e.g. "12s") when present, else 1s, 2s, 4s… capped at 30s. */
    static long backoff(String errorBody, int attempt) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"retryDelay\"\\s*:\\s*\"(\\d+)(?:\\.\\d+)?s\"")
                .matcher(errorBody == null ? "" : errorBody);
        long ms = m.find() ? Long.parseLong(m.group(1)) * 1000 : (1000L << Math.min(attempt, 5));
        return Math.min(ms, 30_000);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new Llm.LlmException("Interrupted while waiting to retry", ie);
        }
    }

    public ObjectNode buildBody(Llm.Request req) {
        ObjectNode body = json.createObjectNode();
        body.putObject("systemInstruction").putArray("parts").addObject().put("text", req.system());

        ArrayNode contents = body.putArray("contents");
        for (Llm.Turn t : req.turns()) {
            switch (t) {
                case Llm.UserText u -> {
                    ObjectNode c = contents.addObject().put("role", "user");
                    c.putArray("parts").addObject().put("text", u.text());
                }
                case Llm.ModelText m -> {
                    ObjectNode c = contents.addObject().put("role", "model");
                    c.putArray("parts").addObject().put("text", m.text());
                }
                case Llm.ModelRaw r -> contents.add(r.content().deepCopy());
                case Llm.ToolResults tr -> {
                    ObjectNode c = contents.addObject().put("role", "user");
                    ArrayNode parts = c.putArray("parts");
                    for (Llm.ToolResult r : tr.results()) {
                        ObjectNode fr = parts.addObject().putObject("functionResponse");
                        fr.put("name", r.name());
                        fr.set("response", r.response());
                    }
                }
            }
        }

        if (!req.tools().isEmpty()) {
            ArrayNode decls = body.putArray("tools").addObject().putArray("functionDeclarations");
            for (Llm.Tool tool : req.tools()) {
                ObjectNode d = decls.addObject();
                d.put("name", tool.name());
                d.put("description", tool.description());
                d.set("parameters", tool.parameters());
            }
        }

        ObjectNode gen = body.putObject("generationConfig");
        gen.put("temperature", s.temperature());
        gen.put("maxOutputTokens", s.maxOutputTokens());
        if (s.thinkingLevel() != null && !s.thinkingLevel().isBlank()) {
            gen.putObject("thinkingConfig").put("thinkingLevel", s.thinkingLevel());
        }
        return body;
    }

    public Llm.Response parse(JsonNode res) {
        if (res == null) {
            throw new Llm.LlmException("Gemini returned an empty body");
        }
        JsonNode cand = res.path("candidates").path(0);
        JsonNode content = cand.path("content");
        if (content.isMissingNode()) {
            String reason = cand.path("finishReason").asText(res.path("promptFeedback").path("blockReason").asText("unknown"));
            throw new Llm.LlmException("Gemini returned no content (" + reason + ")");
        }
        StringBuilder text = new StringBuilder();
        List<Llm.ToolCall> calls = new ArrayList<>();
        for (JsonNode p : content.path("parts")) {
            if (p.has("functionCall")) {
                calls.add(new Llm.ToolCall(p.path("functionCall").path("name").asText(),
                        p.path("functionCall").path("args")));
            } else if (p.has("text") && !p.path("thought").asBoolean(false)) {
                text.append(p.path("text").asText());
            }
        }
        JsonNode u = res.path("usageMetadata");
        Llm.Usage usage = new Llm.Usage(
                u.path("promptTokenCount").asInt(0),
                u.path("cachedContentTokenCount").asInt(0),
                u.path("candidatesTokenCount").asInt(0) + u.path("thoughtsTokenCount").asInt(0));
        ObjectNode raw = content.deepCopy();
        if (!raw.has("role")) {
            raw.put("role", "model");
        }
        return new Llm.Response(text.toString().trim(), calls, raw, usage);
    }

    private static String truncate(String s) {
        return s == null ? "" : s.length() > 400 ? s.substring(0, 400) : s;
    }
}
