package sa.waslah.agent.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.yaml.snakeyaml.Yaml;
import sa.waslah.agent.agent.AgentBrain;
import sa.waslah.agent.agent.AgentPrompt;
import sa.waslah.agent.agent.AgentTools;
import sa.waslah.agent.agent.ReplyGuard;
import sa.waslah.agent.agent.llm.GeminiClient;
import sa.waslah.agent.agent.llm.Llm;
import sa.waslah.agent.listing.PropertyTypes;

/**
 * Runs scenarios.yaml against the real model and writes target/eval/report.md and results.json.
 * Excluded from normal builds; runs in the "Agent eval" workflow with GEMINI_API_KEY.
 * Fails only if the pass rate is below EVAL_MIN_PASS (default 0), so the report is always produced.
 */
@Tag("eval")
class AgentEval {

    static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    static final Pattern EMOJI = Pattern.compile("[\\x{1F300}-\\x{1FAFF}\\x{2600}-\\x{27BF}]");
    static final double USD_TO_SAR = 3.75;

    @Test
    @SuppressWarnings("unchecked")
    void run() throws Exception {
        String key = System.getenv("GEMINI_API_KEY");
        Assumptions.assumeTrue(key != null && !key.isBlank(), "GEMINI_API_KEY not set");
        String model = env("LLM_MODEL", "gemini-3.5-flash-lite");
        String thinking = env("LLM_THINKING_LEVEL", "low");
        double priceIn = Double.parseDouble(env("EVAL_PRICE_IN", "0.30"));
        double priceOut = Double.parseDouble(env("EVAL_PRICE_OUT", "2.50"));

        Map<String, Object> spec;
        try (InputStream in = getClass().getResourceAsStream("/evals/scenarios.yaml")) {
            spec = new Yaml().load(in);
        }
        String office = (String) spec.get("office");
        List<Listing> fixture = ((List<Map<String, Object>>) spec.get("listings")).stream().map(Listing::of).toList();
        List<Map<String, Object>> scenarios = (List<Map<String, Object>>) spec.get("scenarios");
        String only = System.getenv("EVAL_ONLY");

        Llm.Client client = new GeminiClient(RestClient.builder(), JSON, new GeminiClient.Settings(key, model,
                "none".equalsIgnoreCase(thinking) ? null : thinking, 0.3, 1024, Duration.ofSeconds(30), 6));
        AgentBrain brain = new AgentBrain(client, JSON);
        String system = AgentPrompt.system(office, LocalDate.now());

        ArrayNode results = JSON.createArrayNode();
        List<Long> latencies = new ArrayList<>();
        long tokensIn = 0, tokensOut = 0, turnsTotal = 0;
        Map<String, int[]> byCategory = new LinkedHashMap<>();
        int passed = 0, ran = 0;

        for (Map<String, Object> sc : scenarios) {
            String id = (String) sc.get("id");
            if (only != null && !only.isBlank() && !id.startsWith(only)) continue;
            ran++;
            String category = (String) sc.get("category");
            Map<String, Object> expect = (Map<String, Object>) sc.getOrDefault("expect", Map.of());
            FixtureTools tools = new FixtureTools(fixture);
            List<Llm.Turn> history = new ArrayList<>();
            Set<String> prior = new HashSet<>();
            List<String> replies = new ArrayList<>();
            List<String> failures = new ArrayList<>();
            ArrayNode transcript = JSON.createArrayNode();

            for (String customer : (List<String>) sc.get("turns")) {
                history.add(new Llm.UserText(customer));
                AgentBrain.Outcome out = brain.respond(system, history, tools, prior);
                history.add(new Llm.ModelText(out.reply()));
                prior.addAll(ReplyGuard.licensesIn(out.reply()));
                replies.add(out.reply());
                latencies.add(out.latencyMs());
                tokensIn += out.usage().inputTokens();
                tokensOut += out.usage().outputTokens();
                turnsTotal++;

                ObjectNode t = transcript.addObject().put("customer", customer).put("agent", out.reply())
                        .put("latency_ms", out.latencyMs());
                ArrayNode calls = t.putArray("tools");
                out.toolCalls().forEach(c -> calls.addObject().put("name", c.name()).set("args", c.args()));
                if (out.fallback()) failures.add("fallback (" + out.failure() + ") on: " + customer);
                if (EMOJI.matcher(out.reply()).find()) failures.add("emoji in reply");
                if (out.reply().split("\n").length > 6) failures.add("reply longer than 6 lines");
            }

            String last = replies.get(replies.size() - 1);
            for (String tool : list(expect, "must_call")) {
                if (!tools.called.contains(tool)) failures.add("did not call " + tool);
            }
            for (String tool : list(expect, "must_not_call")) {
                if (tools.called.contains(tool)) failures.add("called " + tool);
            }
            List<String> any = list(expect, "reply_any");
            if (!any.isEmpty() && any.stream().noneMatch(s -> norm(last).contains(norm(s)))) {
                failures.add("final reply has none of " + any);
            }
            for (String s : list(expect, "reply_none")) {
                for (String r : replies) {
                    if (norm(r).contains(norm(s))) failures.add("reply contains forbidden \"" + s + "\"");
                }
            }
            Map<String, Object> lead = (Map<String, Object>) expect.getOrDefault("lead", Map.of());
            lead.forEach((k, v) -> {
                String got = tools.lead.get(k);
                if (got == null || !got.equalsIgnoreCase(String.valueOf(v))) {
                    failures.add("lead." + k + " = " + got + ", expected " + v);
                }
            });
            if (Boolean.TRUE.equals(expect.get("discloses_bot"))
                    && List.of("آلي", "مساعد", "assistant", "bot").stream().noneMatch(replies.get(0)::contains)) {
                failures.add("first reply does not say it is an automated assistant");
            }

            boolean ok = failures.isEmpty();
            if (ok) passed++;
            byCategory.computeIfAbsent(category, c -> new int[2])[ok ? 0 : 1]++;
            ObjectNode r = results.addObject().put("id", id).put("category", category).put("pass", ok);
            ArrayNode f = r.putArray("failures");
            failures.forEach(f::add);
            r.set("transcript", transcript);
            r.putArray("tools_called").addAll(tools.called.stream().map(JSON.getNodeFactory()::textNode).toList());
        }

        latencies.sort(Long::compare);
        long p50 = latencies.isEmpty() ? 0 : latencies.get(latencies.size() / 2);
        long p95 = latencies.isEmpty() ? 0 : latencies.get(Math.min(latencies.size() - 1, (int) Math.ceil(latencies.size() * 0.95) - 1));
        double costUsd = tokensIn / 1e6 * priceIn + tokensOut / 1e6 * priceOut;
        double perTurnSar = turnsTotal == 0 ? 0 : costUsd / turnsTotal * USD_TO_SAR;

        StringBuilder md = new StringBuilder();
        md.append("# Agent eval: ").append(model).append(" (thinking: ").append(thinking).append(")\n\n");
        md.append(String.format(Locale.ROOT, "**Passed %d of %d scenarios (%.0f%%)**%n%n", passed, ran, ran == 0 ? 0 : 100.0 * passed / ran));
        md.append("| Metric | Value |\n|---|---|\n");
        md.append(String.format(Locale.ROOT, "| Customer turns | %d |%n| Latency p50 / p95 | %.1f s / %.1f s |%n", turnsTotal, p50 / 1000.0, p95 / 1000.0));
        md.append(String.format(Locale.ROOT, "| Tokens in / out | %,d / %,d |%n", tokensIn, tokensOut));
        md.append(String.format(Locale.ROOT, "| Est. cost per customer turn | %.3f SAR (at $%.2f / $%.2f per 1M) |%n%n", perTurnSar, priceIn, priceOut));
        md.append("| Category | Pass | Fail |\n|---|---|---|\n");
        byCategory.forEach((c, n) -> md.append("| ").append(c).append(" | ").append(n[0]).append(" | ").append(n[1]).append(" |\n"));
        md.append("\n## Failures\n\n");
        for (JsonNode r : results) {
            if (r.path("pass").asBoolean()) continue;
            md.append("### ").append(r.path("id").asText()).append("\n\n");
            for (JsonNode f : r.path("failures")) md.append("- ").append(f.asText()).append('\n');
            md.append('\n');
            for (JsonNode t : r.path("transcript")) {
                md.append("> **العميل:** ").append(t.path("customer").asText()).append("\n>\n");
                List<String> names = new ArrayList<>();
                t.path("tools").forEach(c -> names.add(c.path("name").asText()));
                if (!names.isEmpty()) md.append("> _tools: ").append(String.join(", ", names)).append("_\n>\n");
                md.append("> **الوكيل:** ").append(t.path("agent").asText().replace("\n", "\n> ")).append("\n\n");
            }
        }

        Path dir = Path.of("target", "eval");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("report.md"), md.toString(), StandardCharsets.UTF_8);
        ObjectNode summary = JSON.createObjectNode().put("model", model).put("thinking", thinking)
                .put("passed", passed).put("ran", ran).put("p50_ms", p50).put("p95_ms", p95)
                .put("tokens_in", tokensIn).put("tokens_out", tokensOut).put("cost_per_turn_sar", perTurnSar);
        summary.set("results", results);
        Files.writeString(dir.resolve("results.json"), JSON.writeValueAsString(summary), StandardCharsets.UTF_8);
        System.out.println("EVAL_SUMMARY passed=" + passed + "/" + ran + " p50=" + p50 + "ms p95=" + p95 + "ms costPerTurnSar=" + perTurnSar);

        double min = Double.parseDouble(env("EVAL_MIN_PASS", "0"));
        if (ran > 0 && (double) passed / ran < min) {
            throw new AssertionError("Pass rate " + passed + "/" + ran + " below " + min);
        }
    }

    // ---- fixture -------------------------------------------------------------------------------

    record Listing(String id, String title, String district, String type, String purpose, BigDecimal price,
                   BigDecimal area, int rooms, String license, LocalDate expiry, boolean vacant) {
        static Listing of(Map<String, Object> m) {
            return new Listing((String) m.get("id"), (String) m.get("title"), (String) m.get("district"),
                    PropertyTypes.canonical((String) m.get("type")), (String) m.get("purpose"),
                    new BigDecimal(String.valueOf(m.get("price"))), new BigDecimal(String.valueOf(m.get("area"))),
                    ((Number) m.get("rooms")).intValue(), String.valueOf(m.get("license")),
                    LocalDate.parse(String.valueOf(m.get("expiry"))), Boolean.TRUE.equals(m.get("vacant")));
        }
    }

    /** Same filtering rules as ListingStore.search, over the YAML fixture. */
    static class FixtureTools implements AgentTools {
        final List<Listing> listings;
        final List<String> called = new ArrayList<>();
        final List<String> licenses = new ArrayList<>();
        final Map<String, String> lead = new HashMap<>();
        boolean handedOff;

        FixtureTools(List<Listing> listings) {
            this.listings = listings;
        }

        @Override
        public JsonNode call(String name, JsonNode a) {
            called.add(name);
            switch (name) {
                case SEARCH -> {
                    String purpose = text(a, "purpose");
                    String type = text(a, "property_type");
                    String district = text(a, "district");
                    BigDecimal max = num(a, "max_price"), min = num(a, "min_price");
                    Integer rooms = a.hasNonNull("min_rooms") ? a.path("min_rooms").asInt() : null;
                    List<Listing> hits = listings.stream()
                            .filter(l -> !l.expiry().isBefore(LocalDate.now()))
                            .filter(l -> purpose == null || l.purpose().equals(purpose))
                            .filter(l -> type == null || ar(l.type()).contains(ar(PropertyTypes.canonical(type))))
                            .filter(l -> district == null || ar(l.district()).contains(ar(district)))
                            .filter(l -> max == null || l.price().compareTo(max) <= 0)
                            .filter(l -> min == null || l.price().compareTo(min) >= 0)
                            .filter(l -> rooms == null || l.rooms() >= rooms)
                            .sorted((x, y) -> x.price().compareTo(y.price()))
                            .limit(3).toList();
                    ObjectNode out = JSON.createObjectNode().put("count", hits.size());
                    ArrayNode arr = out.putArray("listings");
                    for (Listing l : hits) {
                        licenses.add(l.license());
                        arr.addObject().put("id", l.id()).put("title", l.title()).put("district", l.district())
                                .put("property_type", l.type()).put("purpose", l.purpose()).put("price_sar", l.price())
                                .put("area_sqm", l.area()).put("rooms", l.rooms()).put("ad_license", l.license())
                                .put("license_expiry", l.expiry().toString()).put("occupied", !l.vacant());
                    }
                    if (hits.isEmpty()) out.put("note", "لا توجد عقارات مطابقة حالياً");
                    return out;
                }
                case SAVE_LEAD -> {
                    a.fields().forEachRemaining(e -> {
                        if (!e.getValue().isNull() && !e.getValue().asText().isBlank()) lead.put(e.getKey(), e.getValue().asText());
                    });
                    return JSON.createObjectNode().put("saved", true);
                }
                case REQUEST_VIEWING -> {
                    String id = a.path("listing_id").asText();
                    boolean exists = listings.stream().anyMatch(l -> l.id().equals(id) && !l.expiry().isBefore(LocalDate.now()));
                    return exists
                            ? JSON.createObjectNode().put("recorded", true).put("next_step", "المكتب يؤكد الموعد مع العميل")
                            .put("marketer_first_name", "خالد")
                            : JSON.createObjectNode().put("error", "listing_id must be an id returned by search_listings");
                }
                case HANDOFF -> {
                    handedOff = true;
                    return JSON.createObjectNode().put("handed_off", true).put("marketer_first_name", "خالد");
                }
                default -> {
                    return JSON.createObjectNode().put("error", "unknown tool " + name);
                }
            }
        }

        @Override
        public List<String> licensesSeen() {
            return licenses;
        }

        @Override
        public boolean handedOff() {
            return handedOff;
        }
    }

    // ---- helpers -------------------------------------------------------------------------------

    /** Java twin of the SQL function ar_norm. */
    static String ar(String s) {
        String t = s == null ? "" : s.trim().toLowerCase(Locale.ROOT)
                .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ة', 'ه').replace('ى', 'ي');
        t = t.replaceFirst("^حي\\s+", "");
        return t.replaceFirst("^ال", "");
    }

    static String norm(String s) {
        return s == null ? "" : s.replace(",", "").replace("٬", "");
    }

    static String text(JsonNode a, String f) {
        String s = a.path(f).asText(null);
        return s == null || s.isBlank() ? null : s.trim();
    }

    static BigDecimal num(JsonNode a, String f) {
        JsonNode n = a.path(f);
        if (n.isNumber()) return n.decimalValue();
        if (n.isTextual()) {
            try {
                return new BigDecimal(n.asText().replaceAll("[^\\d.]", ""));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    static List<String> list(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? List.of() : (List<String>) v;
    }

    static String env(String k, String def) {
        String v = System.getenv(k);
        return v == null || v.isBlank() ? def : v;
    }
}
