package sa.waslah.agent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sa.waslah.agent.agent.llm.Llm;

/**
 * One agent turn: let the model call tools until it writes a reply, then check the reply.
 * Has no database or WhatsApp dependencies, so the eval harness runs exactly this code.
 * Any failure (model error, rule-breaking reply, too many steps) ends in a handoff to a human.
 */
public class AgentBrain {

    private static final Logger log = LoggerFactory.getLogger(AgentBrain.class);
    static final int MAX_STEPS = 6;

    public record ToolCallLog(String name, JsonNode args, JsonNode result) {
    }

    public record Outcome(String reply, boolean fallback, String failure, Llm.Usage usage,
                          List<ToolCallLog> toolCalls, long latencyMs) {
    }

    private final Llm.Client client;
    private final ObjectMapper json;
    private final List<Llm.Tool> specs;

    public AgentBrain(Llm.Client client, ObjectMapper json) {
        this.client = client;
        this.json = json;
        this.specs = AgentTools.specs(json);
    }

    public String model() {
        return client.model();
    }

    public Outcome respond(String system, List<Llm.Turn> history, AgentTools tools, Collection<String> priorLicenses) {
        long start = System.nanoTime();
        List<Llm.Turn> turns = new ArrayList<>(history);
        List<ToolCallLog> calls = new ArrayList<>();
        Llm.Usage usage = Llm.Usage.NONE;
        String failure;

        try {
            for (int step = 0; step < MAX_STEPS; step++) {
                Llm.Response r = client.generate(new Llm.Request(system, turns, specs));
                usage = usage.plus(r.usage());
                if (r.hasToolCalls()) {
                    turns.add(new Llm.ModelRaw(r.raw()));
                    List<Llm.ToolResult> results = new ArrayList<>();
                    for (Llm.ToolCall c : r.toolCalls()) {
                        JsonNode result = tools.call(c.name(), c.args());
                        calls.add(new ToolCallLog(c.name(), c.args(), result));
                        results.add(new Llm.ToolResult(c.name(), result));
                    }
                    turns.add(new Llm.ToolResults(results));
                    continue;
                }
                Set<String> allowed = new HashSet<>(priorLicenses);
                allowed.addAll(tools.licensesSeen());
                ReplyGuard.Verdict v = ReplyGuard.check(r.text(), allowed);
                if (v.ok()) {
                    return new Outcome(r.text(), false, null, usage, calls, ms(start));
                }
                failure = "guard:" + v.reason();
                log.warn("Agent reply blocked ({}): {}", v.reason(), r.text());
                return fallback(tools, calls, usage, start, failure);
            }
            failure = "too_many_steps";
        } catch (Llm.LlmException e) {
            failure = "llm:" + e.getMessage();
            log.warn("Model call failed: {}", e.getMessage());
        }
        return fallback(tools, calls, usage, start, failure);
    }

    private Outcome fallback(AgentTools tools, List<ToolCallLog> calls, Llm.Usage usage, long start, String failure) {
        if (!tools.handedOff()) {
            ObjectNode args = json.createObjectNode()
                    .put("reason", "OTHER")
                    .put("summary", "تحويل تلقائي: لم يتمكن المساعد من إكمال الرد (" + failure + "). راجع المحادثة.");
            calls.add(new ToolCallLog(AgentTools.HANDOFF, args, tools.call(AgentTools.HANDOFF, args)));
        }
        return new Outcome(AgentPrompt.FALLBACK_REPLY, true, failure, usage, calls, ms(start));
    }

    private static long ms(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
