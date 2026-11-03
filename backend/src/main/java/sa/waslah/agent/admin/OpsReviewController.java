package sa.waslah.agent.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import sa.waslah.agent.agent.llm.Llm;
import sa.waslah.agent.common.ApiException;
import sa.waslah.agent.common.ClientIp;
import sa.waslah.agent.tenant.TenantContext;

/**
 * Reply quality for the Hidaa team (portal /ops → مراجعة الردود): customer conversations in a date range with rule
 * flags and AI review results, the AI review itself (run in small batches so each request stays short), one
 * conversation's masked transcript, and an export of the conversations as CSV or JSON Lines. Customers are masked
 * everywhere ({@link sa.waslah.agent.common.Redactor}); every view, review and export is written to the audit log.
 */
@RestController
public class OpsReviewController {

    private static final Logger log = LoggerFactory.getLogger(OpsReviewController.class);
    static final ZoneId RIYADH = ZoneId.of("Asia/Riyadh");
    static final int MAX_DAYS = 92;
    static final int MAX_CONVERSATIONS = 3000;
    static final int MAX_BATCH = 5;
    static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    public record RunRequest(UUID officeId, String from, String to, Integer batch) {
    }

    record Office(UUID id, String name) {
    }

    record Range(LocalDate from, LocalDate to) {
        Instant start() {
            return from.atStartOfDay(RIYADH).toInstant();
        }

        Instant end() {
            return to.plusDays(1).atStartOfDay(RIYADH).toInstant();
        }
    }

    /** A conversation with its rule flags and latest AI review (null if never reviewed). */
    record Checked(ConversationArchive.Conv conv, List<AgentReviewService.Flag> flags, AgentReviewService.Review review) {

        boolean stale() {
            return review != null && review.messages() < conv.messages().size();
        }

        /** Sort key: AI-found issues first, then rule flags, then reviewed, then the rest. */
        int rank() {
            if (review != null && "ISSUE".equals(review.verdict())) return 0;
            if (!flags.isEmpty()) return 1;
            return review != null ? 2 : 3;
        }
    }

    private final JdbcClient jdbc;
    private final OpsAuth ops;
    private final ConversationArchive archive;
    private final AgentReviewService reviews;
    private final ObjectMapper json;

    public OpsReviewController(JdbcClient jdbc, OpsAuth ops, ConversationArchive archive, AgentReviewService reviews, ObjectMapper json) {
        this.jdbc = jdbc;
        this.ops = ops;
        this.archive = archive;
        this.reviews = reviews;
        this.json = json;
    }

    @GetMapping("/api/ops/reviews")
    Map<String, Object> list(HttpServletRequest http, @RequestParam(required = false) UUID officeId,
                             @RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        ops.require(http);
        Range range = range(from, to);
        List<Checked> all = checked(officeId, range, 1000);
        Map<String, Integer> byFlag = new LinkedHashMap<>(), byIssue = new LinkedHashMap<>();
        int flagged = 0, reviewed = 0, issues = 0, scoreSum = 0;
        for (Checked c : all) {
            if (!c.flags().isEmpty()) flagged++;
            for (AgentReviewService.Flag f : c.flags()) byFlag.merge(f.code(), 1, Integer::sum);
            if (c.review() != null) {
                reviewed++;
                scoreSum += c.review().score();
                if ("ISSUE".equals(c.review().verdict())) issues++;
                for (JsonNode i : c.review().issues()) byIssue.merge(i.path("type").asText("OTHER"), 1, Integer::sum);
            }
        }
        List<Map<String, Object>> rows = all.stream()
                .sorted(Comparator.comparingInt(Checked::rank)
                        .thenComparing(c -> c.conv().lastMessageAt() == null ? Instant.EPOCH : c.conv().lastMessageAt(), Comparator.reverseOrder()))
                .limit(500)
                .map(this::row)
                .toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", range.from().toString());
        out.put("to", range.to().toString());
        out.put("conversations", all.size());
        out.put("withAgentReplies", all.stream().filter(c -> c.conv().agentReplies() > 0).count());
        out.put("flagged", flagged);
        out.put("reviewed", reviewed);
        out.put("issues", issues);
        out.put("avgScore", reviewed == 0 ? null : Math.round(scoreSum * 10.0 / reviewed) / 10.0);
        out.put("toReview", all.stream().filter(c -> c.conv().agentReplies() > 0 && (c.review() == null || c.stale())).count());
        out.put("byFlag", byFlag);
        out.put("byIssue", byIssue);
        out.put("rows", rows);
        out.put("offices", offices(null).stream().map(o -> Map.of("id", o.id(), "name", o.name())).toList());
        return out;
    }

    /**
     * Reviews up to {@code batch} (at most 5) conversations that have agent replies and no current review, flagged
     * ones first. The console calls this repeatedly until it has the sample it asked for.
     */
    @PostMapping("/api/ops/reviews/run")
    Map<String, Object> run(HttpServletRequest http, @RequestBody RunRequest r) {
        ops.require(http);
        Range range = range(r == null ? null : r.from(), r == null ? null : r.to());
        int batch = Math.max(1, Math.min(MAX_BATCH, r == null || r.batch() == null ? MAX_BATCH : r.batch()));
        List<Checked> todo = checked(r == null ? null : r.officeId(), range, 1000).stream()
                .filter(c -> c.conv().agentReplies() > 0 && (c.review() == null || c.stale()))
                .sorted(Comparator.comparingInt((Checked c) -> c.flags().isEmpty() ? 1 : 0)
                        .thenComparing(c -> c.conv().lastMessageAt() == null ? Instant.EPOCH : c.conv().lastMessageAt(), Comparator.reverseOrder()))
                .toList();
        List<Map<String, Object>> done = new ArrayList<>();
        int failed = 0;
        String error = null;
        for (Checked c : todo) {
            if (done.size() >= batch || failed >= 2) break;
            try {
                AgentReviewService.Review rv = TenantContext.call(c.conv().officeId(), () -> reviews.review(c.conv()));
                done.add(Map.of("conversationId", c.conv().id(), "score", rv.score(), "verdict", rv.verdict()));
            } catch (Llm.LlmException e) {
                failed++;
                error = e.getMessage();
                log.warn("AI review failed for conversation {}: {}", c.conv().id(), e.getMessage());
            }
        }
        if (!done.isEmpty()) {
            audit(http, "AI_REVIEW", r == null ? null : r.officeId(), null, done.size() + " conversations, " + range.from() + " – " + range.to());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reviewed", done);
        out.put("failed", failed);
        out.put("error", error);
        out.put("remaining", Math.max(0, todo.size() - done.size() - failed));
        return out;
    }

    /** One conversation, masked, with its flags and latest AI review. */
    @GetMapping("/api/ops/offices/{officeId}/conversations/{id}")
    Map<String, Object> conversation(HttpServletRequest http, @PathVariable UUID officeId, @PathVariable UUID id) {
        ops.require(http);
        Office office = offices(officeId).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("no_office", "المكتب غير موجود."));
        Checked c = TenantContext.call(officeId, () -> archive.load(officeId, office.name(), id)
                .map(conv -> new Checked(conv, reviews.flags(conv, Instant.now()), reviews.latest(id).orElse(null))))
                .orElseThrow(() -> ApiException.notFound("no_conversation", "المحادثة غير موجودة."));
        audit(http, "CONVERSATION_VIEWED", officeId, c.conv().customer(), null);
        Map<String, Object> out = row(c);
        out.put("lead", c.conv().lead());
        out.put("handoffs", c.conv().handoffs().stream().map(h -> Map.of("at", h.at().toString(), "reason", h.reason(),
                "summary", h.summary() == null ? "" : h.summary())).toList());
        out.put("bookings", c.conv().bookings());
        out.put("withMarketer", c.conv().withMarketer());
        out.put("flagList", c.flags());
        out.put("messages", c.conv().messages().stream().map(this::message).toList());
        return out;
    }

    /** Conversations in the range as CSV (one row per message, opens in Excel) or JSON Lines (one conversation per line). */
    @GetMapping("/api/ops/export")
    ResponseEntity<byte[]> export(HttpServletRequest http, @RequestParam(required = false) UUID officeId,
                                  @RequestParam(required = false) String from, @RequestParam(required = false) String to,
                                  @RequestParam(defaultValue = "csv") String format) {
        ops.require(http);
        boolean csv = !"jsonl".equalsIgnoreCase(format);
        Range range = range(from, to);
        List<Checked> all = checked(officeId, range, MAX_CONVERSATIONS);
        byte[] body = csv ? csv(all) : jsonl(all);
        audit(http, "EXPORT", officeId, csv ? "csv" : "jsonl", all.size() + " conversations, " + range.from() + " – " + range.to());
        String name = "hidaa-conversations-" + range.from() + "-" + range.to() + (csv ? ".csv" : ".jsonl");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(csv ? new MediaType("text", "csv", StandardCharsets.UTF_8) : new MediaType("application", "x-ndjson", StandardCharsets.UTF_8))
                .body(body);
    }

    // ---- Building blocks ----

    private List<Checked> checked(UUID officeId, Range range, int limit) {
        List<Checked> out = new ArrayList<>();
        Instant now = Instant.now();
        for (Office o : offices(officeId)) {
            if (out.size() >= limit) break;
            int left = limit - out.size();
            out.addAll(TenantContext.call(o.id(), () -> {
                List<Checked> list = new ArrayList<>();
                for (UUID id : archive.ids(range.start(), range.end(), left)) {
                    archive.load(o.id(), o.name(), id).ifPresent(conv ->
                            list.add(new Checked(conv, reviews.flags(conv, now), reviews.latest(id).orElse(null))));
                }
                return list;
            }));
        }
        return out;
    }

    private Map<String, Object> row(Checked c) {
        ConversationArchive.Conv v = c.conv();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("officeId", v.officeId());
        m.put("office", v.office());
        m.put("conversationId", v.id());
        m.put("customer", v.customer());
        m.put("createdAt", v.createdAt().toString());
        m.put("lastMessageAt", v.lastMessageAt() == null ? null : v.lastMessageAt().toString());
        m.put("messages", v.messages().size());
        m.put("agentReplies", v.agentReplies());
        m.put("handedOver", !v.handoffs().isEmpty() || v.withMarketer());
        m.put("flags", c.flags().stream().map(AgentReviewService.Flag::code).distinct().toList());
        m.put("review", c.review() == null ? null : review(c.review(), c.stale()));
        return m;
    }

    private Map<String, Object> review(AgentReviewService.Review r, boolean stale) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("score", r.score());
        m.put("verdict", r.verdict());
        m.put("summary", r.summary());
        m.put("issues", r.issues());
        m.put("model", r.model());
        m.put("createdAt", r.createdAt().toString());
        m.put("stale", stale);
        return m;
    }

    private Map<String, Object> message(ConversationArchive.Msg x) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("n", x.n());
        m.put("at", x.at().toString());
        m.put("sender", x.sender());
        m.put("direction", x.direction());
        m.put("type", x.type());
        m.put("text", x.text());
        m.put("run", x.run() == null ? null : run(x.run()));
        return m;
    }

    private Map<String, Object> run(ConversationArchive.Run r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("outcome", r.outcome());
        m.put("latencyMs", r.latencyMs());
        m.put("model", r.model());
        m.put("inputTokens", r.inputTokens());
        m.put("outputTokens", r.outputTokens());
        m.put("error", r.error());
        m.put("tools", r.tools());
        return m;
    }

    static final List<String> CSV_COLUMNS = List.of("office", "conversation", "customer", "n", "time_riyadh", "from", "type", "text",
            "agent_outcome", "agent_latency_ms", "agent_tools", "agent_error", "conversation_flags", "review_score", "review_verdict",
            "review_summary");

    private byte[] csv(List<Checked> all) {
        StringBuilder b = new StringBuilder("﻿");
        b.append(String.join(",", CSV_COLUMNS)).append("\r\n");
        for (Checked c : all) {
            ConversationArchive.Conv v = c.conv();
            boolean first = true;
            for (ConversationArchive.Msg m : v.messages()) {
                ConversationArchive.Run r = m.run();
                List<String> tools = new ArrayList<>();
                if (r != null && r.tools() != null) for (JsonNode t : r.tools()) tools.add(t.path("name").asText());
                List<String> cells = List.of(v.office(), v.id().toString().substring(0, 8), v.customer(), String.valueOf(m.n()),
                        TIME.format(m.at().atZone(RIYADH)), from(m.sender()), m.type(), m.text() == null ? "" : m.text(),
                        r == null ? "" : r.outcome(), r == null ? "" : String.valueOf(r.latencyMs()), String.join("; ", tools),
                        r == null || r.error() == null ? "" : r.error(),
                        first ? String.join(" ", c.flags().stream().map(AgentReviewService.Flag::code).distinct().toList()) : "",
                        first && c.review() != null ? String.valueOf(c.review().score()) : "",
                        first && c.review() != null ? c.review().verdict() : "",
                        first && c.review() != null && c.review().summary() != null ? c.review().summary() : "");
                b.append(String.join(",", cells.stream().map(OpsReviewController::cell).toList())).append("\r\n");
                first = false;
            }
        }
        return b.toString().getBytes(StandardCharsets.UTF_8);
    }

    private byte[] jsonl(List<Checked> all) {
        StringBuilder b = new StringBuilder();
        for (Checked c : all) {
            ConversationArchive.Conv v = c.conv();
            ObjectNode o = json.createObjectNode();
            o.put("office", v.office());
            o.put("conversation", v.id().toString());
            o.put("customer", v.customer());
            o.put("createdAt", v.createdAt().toString());
            o.put("lastMessageAt", v.lastMessageAt() == null ? null : v.lastMessageAt().toString());
            o.put("handedToMarketer", v.withMarketer());
            o.set("lead", json.valueToTree(v.lead()));
            o.set("handoffs", json.valueToTree(v.handoffs().stream().map(h -> Map.of("at", h.at().toString(), "reason", h.reason(),
                    "summary", h.summary() == null ? "" : h.summary())).toList()));
            o.put("bookings", v.bookings());
            o.set("flags", json.valueToTree(c.flags()));
            o.set("review", c.review() == null ? null : json.<JsonNode>valueToTree(review(c.review(), c.stale())));
            ArrayNode msgs = o.putArray("messages");
            for (ConversationArchive.Msg m : v.messages()) {
                ObjectNode x = msgs.addObject();
                x.put("n", m.n());
                x.put("at", m.at().toString());
                x.put("from", from(m.sender()));
                x.put("type", m.type());
                x.put("text", m.text());
                if (m.run() != null) x.set("agent", json.valueToTree(run(m.run())));
            }
            b.append(o).append('\n');
        }
        return b.toString().getBytes(StandardCharsets.UTF_8);
    }

    static String from(String sender) {
        return switch (sender) {
            case "CUSTOMER" -> "customer";
            case "AGENT" -> "agent";
            case "HUMAN" -> "office_staff";
            default -> "system";
        };
    }

    /** A CSV cell: quoted when needed, and never read as a formula by Excel. */
    static String cell(String s) {
        String v = s == null ? "" : s;
        if (!v.isEmpty() && "=+-@\t\r".indexOf(v.charAt(0)) >= 0) v = "'" + v;
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) v = "\"" + v.replace("\"", "\"\"") + "\"";
        return v;
    }

    private Range range(String from, String to) {
        LocalDate today = LocalDate.now(RIYADH);
        LocalDate t, f;
        try {
            t = to == null || to.isBlank() ? today : LocalDate.parse(to);
            f = from == null || from.isBlank() ? t.minusDays(6) : LocalDate.parse(from);
        } catch (RuntimeException e) {
            throw ApiException.badRequest("bad_date", "التاريخ بصيغة 2026-11-03.");
        }
        if (f.isAfter(t)) throw ApiException.badRequest("bad_range", "تاريخ البداية بعد النهاية.");
        if (f.plusDays(MAX_DAYS).isBefore(t)) throw ApiException.badRequest("range_too_long", "الفترة 92 يوماً على الأكثر.");
        return new Range(f, t);
    }

    private List<Office> offices(UUID only) {
        List<UUID> ids = only != null ? List.of(only) : jdbc.sql("select distinct office_id from users").query(UUID.class).list();
        List<Office> out = new ArrayList<>();
        for (UUID id : ids) {
            Optional<String> name = TenantContext.call(id, () -> jdbc.sql("select name from offices").query(String.class).optional());
            name.ifPresent(n -> out.add(new Office(id, n)));
        }
        out.sort(Comparator.comparing(Office::name));
        return out;
    }

    private void audit(HttpServletRequest http, String action, UUID office, String target, String detail) {
        jdbc.sql("insert into ops_audit (action, office_id, target, detail, ip_hash) values (?, ?, ?, ?, ?)")
                .params(action, office, target, detail, ClientIp.hash(http, "ops")).update();
    }
}
