package sa.waslah.agent.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import sa.waslah.agent.agent.llm.GeminiClient;
import sa.waslah.agent.agent.llm.Llm;
import sa.waslah.agent.agent.llm.ScriptedLlmClient;

class AgentUnitTests {

    static final ObjectMapper JSON = new ObjectMapper();

    /** Records calls; search returns one listing with the given license. */
    static class FakeTools implements AgentTools {
        final List<String> called = new ArrayList<>();
        final List<String> licenses = new ArrayList<>();
        boolean handedOff;

        @Override
        public JsonNode call(String name, JsonNode args) {
            called.add(name);
            if (SEARCH.equals(name)) {
                licenses.add("7200418836");
                ObjectNode o = JSON.createObjectNode().put("count", 1);
                o.putArray("listings").addObject().put("id", "L1").put("ad_license", "7200418836");
                return o;
            }
            if (HANDOFF.equals(name)) handedOff = true;
            return JSON.createObjectNode().put("ok", true);
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

    @Nested
    class Brain {
        @Test
        void loopsThroughToolsThenReplies() {
            ScriptedLlmClient llm = new ScriptedLlmClient()
                    .thenCall("search_listings", "{\"district\":\"النرجس\"}")
                    .thenText("عندنا فيلا ترخيص 7200418836");
            FakeTools tools = new FakeTools();
            AgentBrain.Outcome out = new AgentBrain(llm, JSON).respond("sys", List.of(new Llm.UserText("فلل؟")), tools, Set.of());
            assertThat(out.fallback()).isFalse();
            assertThat(out.reply()).contains("7200418836");
            assertThat(tools.called).containsExactly("search_listings");
            assertThat(llm.requests().get(1).turns()).hasSize(3);
        }

        @Test
        void inventedLicenseFallsBackAndHandsOffOnce() {
            ScriptedLlmClient llm = new ScriptedLlmClient().thenText("ترخيص ٧٢٠٠٠٠٠٠٠١");
            FakeTools tools = new FakeTools();
            AgentBrain.Outcome out = new AgentBrain(llm, JSON).respond("sys", List.of(new Llm.UserText("x")), tools, Set.of());
            assertThat(out.fallback()).isTrue();
            assertThat(out.failure()).startsWith("guard:unknown_license");
            assertThat(tools.called).containsExactly("handoff_to_marketer");
        }

        @Test
        void licenseSentEarlierInTheChatIsAllowed() {
            ScriptedLlmClient llm = new ScriptedLlmClient().thenText("نفس الفيلا ترخيص 7200111111");
            AgentBrain.Outcome out = new AgentBrain(llm, JSON).respond("sys", List.of(new Llm.UserText("x")),
                    new FakeTools(), Set.of("7200111111"));
            assertThat(out.fallback()).isFalse();
        }

        @Test
        void modelErrorFallsBack() {
            Llm.Client failing = new Llm.Client() {
                public Llm.Response generate(Llm.Request r) {
                    throw new Llm.LlmException("boom");
                }

                public String model() {
                    return "x";
                }
            };
            FakeTools tools = new FakeTools();
            AgentBrain.Outcome out = new AgentBrain(failing, JSON).respond("sys", List.of(new Llm.UserText("x")), tools, Set.of());
            assertThat(out.reply()).isEqualTo(AgentPrompt.FALLBACK_REPLY);
            assertThat(tools.handedOff).isTrue();
        }
    }

    @Nested
    class Guard {
        @Test
        void rejectsEmptyTooLongAndUnknownLicense() {
            assertThat(ReplyGuard.check("", Set.of()).ok()).isFalse();
            assertThat(ReplyGuard.check("a".repeat(2000), Set.of()).ok()).isFalse();
            assertThat(ReplyGuard.check("ترخيص 7200418836", Set.of()).ok()).isFalse();
            assertThat(ReplyGuard.check("ترخيص 7200418836", Set.of("7200418836")).ok()).isTrue();
            assertThat(ReplyGuard.check("جوالي 0551234567 والسعر 1950000", Set.of()).ok()).isTrue();
        }

        @Test
        void correctsAvailabilityClaimsWithoutSearch() {
            assertThat(ReplyGuard.correction("نعم متوفرة لدينا محلات تجارية للإيجار", Set.of(), false))
                    .isEqualTo(ReplyGuard.MUST_SEARCH);
            assertThat(ReplyGuard.correction("عندنا فيلا في النرجس", Set.of(AgentTools.SEARCH), false)).isNull();
            assertThat(ReplyGuard.correction("ما عندي معلومات عن المطاعم، أقدر أساعدك بالعقار", Set.of(), false)).isNull();
        }

        @Test
        void correctsHandoffPromisesButNotOffers() {
            assertThat(ReplyGuard.correction("سأقوم بتحويلك فوراً للمسوّق.", Set.of(), false)).isEqualTo(ReplyGuard.MUST_HANDOFF);
            assertThat(ReplyGuard.correction("تبي أحولك للمسوّق؟", Set.of(), false)).isNull();
            assertThat(ReplyGuard.correction("بحوّلك للأستاذ خالد ويتواصل معك.", Set.of(), true)).isNull();
        }

        @Test
        void brainRepairsThenReplies() {
            ScriptedLlmClient llm = new ScriptedLlmClient()
                    .thenText("سأقوم بتحويلك للمسوّق.")
                    .thenCall("handoff_to_marketer", "{\"reason\":\"NEGOTIATION\",\"summary\":\"x\"}")
                    .thenText("بحوّلك للمسوّق ويتواصل معك.");
            FakeTools tools = new FakeTools();
            AgentBrain.Outcome out = new AgentBrain(llm, JSON).respond("sys", List.of(new Llm.UserText("كم آخر سعر؟")), tools, Set.of());
            assertThat(out.fallback()).isFalse();
            assertThat(tools.called).containsExactly("handoff_to_marketer");
            assertThat(llm.requests().get(1).turns().toString()).contains("تنبيه داخلي");
        }
    }

    @Nested
    class Gemini {
        final GeminiClient client = new GeminiClient(RestClient.builder(), JSON,
                new GeminiClient.Settings("k", "gemini-3.5-flash-lite", "low", 0.3, 512, Duration.ofSeconds(5), 0));

        @Test
        void buildsRequestWithToolsAndReplaysRawModelTurn() throws Exception {
            JsonNode raw = JSON.readTree("{\"role\":\"model\",\"parts\":[{\"functionCall\":{\"name\":\"search_listings\",\"args\":{}},\"thoughtSignature\":\"abc\"}]}");
            ObjectNode body = client.buildBody(new Llm.Request("sys", List.of(
                    new Llm.UserText("هلا"), new Llm.ModelRaw(raw),
                    new Llm.ToolResults(List.of(new Llm.ToolResult("search_listings", JSON.createObjectNode().put("count", 0))))),
                    AgentTools.specs(JSON)));
            assertThat(body.path("systemInstruction").path("parts").get(0).path("text").asText()).isEqualTo("sys");
            assertThat(body.path("contents")).hasSize(3);
            assertThat(body.path("contents").get(1).path("parts").get(0).path("thoughtSignature").asText()).isEqualTo("abc");
            assertThat(body.path("contents").get(2).path("parts").get(0).path("functionResponse").path("name").asText())
                    .isEqualTo("search_listings");
            assertThat(body.path("tools").get(0).path("functionDeclarations")).hasSize(4);
            assertThat(body.path("generationConfig").path("thinkingConfig").path("thinkingLevel").asText()).isEqualTo("low");
        }

        @Test
        void parsesCallsTextAndUsageAndSkipsThoughts() throws Exception {
            Llm.Response r = client.parse(JSON.readTree("""
                    {"candidates":[{"content":{"role":"model","parts":[
                      {"text":"thinking...","thought":true},
                      {"functionCall":{"name":"save_lead","args":{"purpose":"SALE"}}},
                      {"text":"تمام"}]}}],
                     "usageMetadata":{"promptTokenCount":100,"candidatesTokenCount":20,"thoughtsTokenCount":5,"cachedContentTokenCount":60}}"""));
            assertThat(r.text()).isEqualTo("تمام");
            assertThat(r.toolCalls()).hasSize(1);
            assertThat(r.toolCalls().get(0).args().path("purpose").asText()).isEqualTo("SALE");
            assertThat(r.usage().outputTokens()).isEqualTo(25);
            assertThat(r.usage().cachedTokens()).isEqualTo(60);
        }
    }

    @Nested
    class History {
        @Test
        void mergesSameRoleAndStartsWithUser() {
            List<AgentService.Msg> h = List.of(
                    msg("OUT", "AGENT", "text", "رسالة قديمة"),
                    msg("IN", "CUSTOMER", "text", "السلام"),
                    msg("IN", "CUSTOMER", "text", "عندكم فلل؟"),
                    msg("OUT", "HUMAN", "text", "هلا"),
                    msg("IN", "CUSTOMER", "audio", null));
            List<Llm.Turn> t = AgentService.toTurns(h);
            assertThat(t).hasSize(3);
            assertThat(((Llm.UserText) t.get(0)).text()).isEqualTo("السلام\nعندكم فلل؟");
            assertThat(((Llm.ModelText) t.get(1)).text()).contains("[رد من المسوّق]");
            assertThat(((Llm.UserText) t.get(2)).text()).isEqualTo("[رسالة صوتية]");
        }

        private AgentService.Msg msg(String dir, String sender, String type, String body) {
            return new AgentService.Msg(java.util.UUID.randomUUID(), dir, sender, type, body);
        }
    }
}
