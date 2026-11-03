package sa.waslah.agent.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import sa.waslah.agent.agent.AgentPrompt;
import sa.waslah.agent.agent.llm.Llm;

/**
 * Is the agent replying well? Two kinds of check on a customer conversation:
 * <ul>
 *   <li>Rules, free and instant: agent errors and fallback replies, slow or missing replies, the same reply sent
 *       twice, very long replies, a customer asking for a person without being handed over, a customer complaining.</li>
 *   <li>An AI reviewer (the same Gemini model) that reads the masked conversation with the listings the agent's tools
 *       returned and the agent's own instructions, and grades accuracy, made-up facts, missed questions, handoffs and
 *       tone. Run on demand from /ops; each result is stored and shown until new messages arrive.</li>
 * </ul>
 * Must run under the conversation's office tenant.
 */
@Service
public class AgentReviewService {

    static final ZoneId RIYADH = ZoneId.of("Asia/Riyadh");
    static final Duration SLOW = Duration.ofSeconds(20);
    static final Duration UNANSWERED_AFTER = Duration.ofMinutes(10);
    static final int LONG_REPLY = 700;
    static final int REVIEW_LAST = 60;

    public record Flag(String code, Integer n, String detail) {
    }

    public record Review(UUID id, int messages, int score, String verdict, String summary, JsonNode issues, String model,
                         Instant createdAt) {
    }

    static final Pattern ASKS_FOR_PERSON = Pattern.compile(
            "(موظف|بشري|شخص حقيقي|انسان|ابي اكلم|ابغي اكلم|ابغا اكلم|ابى اكلم|اكلم احد|كلمني احد|يكلمني|مسوول|المدير|خدمه العملاء|human|real person)");
    static final Pattern COMPLAINT = Pattern.compile(
            "(غلط|خطا|ما فهمت|مافهمت|ما فهمتني|مو صحيح|مب صحيح|غير صحيح|ما رديت|مارديت|ليش ما ترد|سيء|سيي|زفت|ما ينفع|مو هذا|مب هذا|غبي|روبوت|انت بوت|ما تفهم|مو فاهم|كذب)");
    static final List<String> ISSUE_TYPES = List.of("INACCURATE", "MADE_UP", "MISSED_QUESTION", "NO_HANDOFF", "BAD_HANDOFF",
            "RULE_BROKEN", "TONE", "REPETITIVE", "OTHER");

    private final JdbcClient jdbc;
    private final Llm.Client llm;
    private final ObjectMapper json;

    public AgentReviewService(JdbcClient jdbc, Llm.Client llm, ObjectMapper json) {
        this.jdbc = jdbc;
        this.llm = llm;
        this.json = json;
    }

    // ---- Rules ----

    public List<Flag> flags(ConversationArchive.Conv c, Instant now) {
        List<Flag> out = new ArrayList<>();
        Map<String, Integer> replies = new HashMap<>();
        for (ConversationArchive.Msg m : c.messages()) {
            ConversationArchive.Run r = m.run();
            if (r != null) {
                if ("error".equals(r.outcome())) out.add(new Flag("ERROR", m.n(), r.error()));
                else if ("fallback".equals(r.outcome())) out.add(new Flag("FALLBACK", m.n(), r.error()));
                else if ("replied".equals(r.outcome()) && r.latencyMs() > SLOW.toMillis()) {
                    out.add(new Flag("SLOW", m.n(), Math.round(r.latencyMs() / 1000.0) + " ث"));
                }
            }
            if ("AGENT".equals(m.sender()) && m.text() != null) {
                String key = norm(m.text());
                if (!key.isBlank() && replies.merge(key, 1, Integer::sum) == 2) out.add(new Flag("REPEAT", m.n(), null));
                if (m.text().length() > LONG_REPLY) out.add(new Flag("LONG", m.n(), m.text().length() + " حرفاً"));
            }
            if ("CUSTOMER".equals(m.sender()) && m.text() != null) {
                String t = norm(m.text());
                if (ASKS_FOR_PERSON.matcher(t).find() && !handedOverAfter(c, m.at())) out.add(new Flag("ASKED_HUMAN", m.n(), null));
                if (COMPLAINT.matcher(t).find()) out.add(new Flag("COMPLAINT", m.n(), null));
            }
        }
        if (!c.messages().isEmpty()) {
            ConversationArchive.Msg last = c.messages().get(c.messages().size() - 1);
            boolean intentional = last.run() != null && List.of("plan_limit", "capped", "spam").contains(last.run().outcome());
            if ("IN".equals(last.direction()) && last.at().isBefore(now.minus(UNANSWERED_AFTER)) && !c.withMarketer()
                    && c.handoffs().isEmpty() && !intentional) {
                out.add(new Flag("UNANSWERED", last.n(), null));
            }
        }
        return out;
    }

    private static boolean handedOverAfter(ConversationArchive.Conv c, Instant at) {
        return c.withMarketer() || c.handoffs().stream().anyMatch(h -> !h.at().isBefore(at.minusSeconds(5)));
    }

    /** Loose Arabic matching: أ/إ/آ→ا, ة→ه, ى→ي, no diacritics or tatweel, lower case. */
    static String norm(String s) {
        return s.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[\\u064B-\\u0652\\u0640]", "")
                .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ة', 'ه').replace('ى', 'ي').replace('ؤ', 'و').replace('ئ', 'ي')
                .replaceAll("\\s+", " ").trim();
    }

    // ---- AI review ----

    public Optional<Review> latest(UUID conversationId) {
        return jdbc.sql("""
                        select id, messages, score, verdict, summary, issues::text, model, created_at from agent_reviews
                        where conversation_id = ? order by created_at desc limit 1""")
                .param(conversationId)
                .query((rs, n) -> new Review(rs.getObject(1, UUID.class), rs.getInt(2), rs.getInt(3), rs.getString(4),
                        rs.getString(5), read(rs.getString(6)), rs.getString(7), rs.getTimestamp(8).toInstant()))
                .optional();
    }

    /** Asks the AI reviewer about one conversation and stores the result. Throws Llm.LlmException if the model fails. */
    public Review review(ConversationArchive.Conv c) {
        JsonNode r = llm.extractJson(instruction(c), transcript(c), schema());
        int score = Math.max(1, Math.min(5, r.path("score").asInt(3)));
        String verdict = score <= 3 || "ISSUE".equals(r.path("verdict").asText()) ? "ISSUE" : "OK";
        ArrayNode issues = json.createArrayNode();
        for (JsonNode i : r.path("issues")) {
            ObjectNode o = issues.addObject();
            o.put("turn", i.path("turn").asInt());
            String type = i.path("type").asText("OTHER");
            o.put("type", ISSUE_TYPES.contains(type) ? type : "OTHER");
            o.put("detail", i.path("detail").asText(""));
        }
        String summary = r.path("summary").asText("");
        UUID id = jdbc.sql("""
                        insert into agent_reviews (office_id, conversation_id, messages, score, verdict, summary, issues, model)
                        values (?, ?, ?, ?, ?, ?, ?::jsonb, ?) returning id""")
                .params(c.officeId(), c.id(), c.messages().size(), score, verdict, summary, issues.toString(), llm.model())
                .query(UUID.class).single();
        return new Review(id, c.messages().size(), score, verdict, summary, issues, llm.model(), Instant.now());
    }

    static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** The conversation as the reviewer reads it: numbered messages, with each agent turn's tool calls before its reply. */
    String transcript(ConversationArchive.Conv c) {
        StringBuilder b = new StringBuilder();
        b.append("المكتب: ").append(c.office()).append('\n');
        if (c.lead() != null) b.append("ما سجّله الوكيل عن العميل: ").append(compact(json.valueToTree(c.lead()), 400)).append('\n');
        List<ConversationArchive.Msg> msgs = c.messages();
        int start = Math.max(0, msgs.size() - REVIEW_LAST);
        if (start > 0) b.append("(أول ").append(start).append(" رسالة محذوفة من المراجعة)\n");
        b.append('\n');
        for (ConversationArchive.Msg m : msgs.subList(start, msgs.size())) {
            if (m.run() != null && m.run().tools() != null) {
                for (JsonNode call : m.run().tools()) {
                    b.append("    (أداة الوكيل ").append(call.path("name").asText()).append(" بالمدخلات ")
                            .append(compact(call.path("args"), 300)).append(" ← ").append(toolResult(call.path("result"))).append(")\n");
                }
            }
            b.append('[').append(m.n()).append("] ").append(who(m)).append(" (").append(TIME.format(m.at().atZone(RIYADH))).append("): ")
                    .append(m.text() == null || m.text().isBlank() ? "[" + m.type() + "]" : m.text()).append('\n');
        }
        for (ConversationArchive.Handoff h : c.handoffs()) {
            b.append("\nتحويل لمسوّق ").append(TIME.format(h.at().atZone(RIYADH))).append(": ").append(h.reason()).append('\n');
        }
        return b.toString();
    }

    static String who(ConversationArchive.Msg m) {
        return switch (m.sender()) {
            case "CUSTOMER" -> "العميل";
            case "AGENT" -> "الوكيل";
            case "HUMAN" -> "موظف المكتب";
            default -> "النظام";
        };
    }

    /** Listings from a search, one line each; anything else as short JSON. */
    private String toolResult(JsonNode result) {
        if (result != null && result.has("listings")) {
            List<String> lines = new ArrayList<>();
            for (JsonNode l : result.path("listings")) {
                lines.add(String.join(" | ", l.path("title").asText(), l.path("district").asText(), l.path("property_type").asText(),
                        l.path("purpose").asText(), "السعر " + l.path("price_sar").asText(), "الترخيص " + l.path("ad_license").asText(),
                        "ينتهي " + l.path("license_expiry").asText()));
            }
            String note = result.path("note").asText("");
            return lines.isEmpty() ? "لا نتائج" + (note.isEmpty() ? "" : " (" + note + ")")
                    : lines.size() + " عقار: " + String.join(" ؛ ", lines) + (note.isEmpty() ? "" : " (" + note + ")");
        }
        return compact(result, 400);
    }

    private static String compact(JsonNode n, int max) {
        String s = n == null || n.isMissingNode() ? "" : n.toString();
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    private String instruction(ConversationArchive.Conv c) {
        LocalDate day = c.createdAt().atZone(RIYADH).toLocalDate();
        return """
                You review a WhatsApp conversation between a customer and the AI assistant of a real-estate brokerage office in \
                Riyadh. Customer personal details are masked ([جوال], عميل-…). Judge only the assistant's messages (الوكيل). \
                Lines starting with (أداة الوكيل …) show the tools the assistant called before its next reply and what they \
                returned: treat those results as the truth about the office's listings. Messages from موظف المكتب are people \
                at the office; do not judge them.

                Check every assistant message for:
                1. Accuracy: any fact about a listing (price, district, size, rooms, availability, ad license) matches the tool \
                results. Inventing a listing, a price, a discount, a fact or a promise is MADE_UP; a wrong detail is INACCURATE.
                2. It answered what the customer actually asked (MISSED_QUESTION if it ignored or dodged a question it could answer).
                3. Handoffs: it handed over to a person when the customer asked for one or the request was out of its scope \
                (NO_HANDOFF if it did not), and did not hand over needlessly (BAD_HANDOFF).
                4. Its instructions (below): e.g. saying it is an assistant, qualifying the customer, offering a viewing, \
                showing the ad license, not asking for what the customer already said (RULE_BROKEN).
                5. Tone: short, polite, natural Saudi dialect (TONE); not repeating itself (REPETITIVE).

                score: 5 = no problems, 4 = minor wording issues only, 3 = a noticeable problem, 2 = wrong or misleading \
                information, 1 = badly wrong or harmful. verdict: ISSUE when score is 3 or less, else OK.
                issues: one entry per problem, with turn = the [number] of the assistant message, the type, and detail = one \
                short sentence in Arabic saying what was wrong and what the right reply would have been. Empty when there are none.
                summary: one Arabic sentence about how the assistant did in this conversation.

                The assistant's instructions were:
                """ + AgentPrompt.system(c.office(), day);
    }

    private ObjectNode schema() {
        ObjectNode s = json.createObjectNode().put("type", "object");
        ObjectNode p = s.putObject("properties");
        p.putObject("score").put("type", "integer");
        p.putObject("verdict").put("type", "string").putArray("enum").add("OK").add("ISSUE");
        p.putObject("summary").put("type", "string");
        ObjectNode item = p.putObject("issues").put("type", "array").putObject("items").put("type", "object");
        ObjectNode ip = item.putObject("properties");
        ip.putObject("turn").put("type", "integer");
        ArrayNode types = ip.putObject("type").put("type", "string").putArray("enum");
        ISSUE_TYPES.forEach(types::add);
        ip.putObject("detail").put("type", "string");
        item.putArray("required").add("turn").add("type").add("detail");
        s.putArray("required").add("score").add("verdict").add("summary").add("issues");
        return s;
    }

    private JsonNode read(String s) {
        try {
            return json.readTree(s == null ? "[]" : s);
        } catch (Exception e) {
            return json.createArrayNode();
        }
    }

    /** Counts for the summary cards. */
    static Map<String, Integer> count(List<String> codes) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (String c : codes) m.merge(c, 1, Integer::sum);
        return m;
    }
}
