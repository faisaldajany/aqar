package sa.waslah.agent.billing;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Plans from the home page: priced per conversation. A conversation is one customer the agent answered,
 * counted once per 24 hours however many messages it takes. Over the allowance, BASIC and GROWTH bill
 * 0.50 SAR per conversation; STARTER hands new conversations to the office instead.
 */
@Component
public class PlanLimits {

    public enum Plan {
        STARTER("البداية", new BigDecimal("199"), new BigDecimal("1990"), 100, 1, 1, null),
        BASIC("الأساسي", new BigDecimal("499"), new BigDecimal("4990"), 500, 1, 1, new BigDecimal("0.50")),
        GROWTH("النمو", new BigDecimal("999"), new BigDecimal("9990"), 2_000, 5, 1, new BigDecimal("0.50"));

        public final String arabicName;
        public final BigDecimal monthlySar;
        public final BigDecimal annualSar;
        public final int includedConversations;
        public final int marketers;
        public final int whatsappNumbers;
        /** Null: no overage; new conversations over the allowance go to the office. */
        public final BigDecimal overagePerConversationSar;

        Plan(String arabicName, BigDecimal monthlySar, BigDecimal annualSar, int includedConversations, int marketers,
             int whatsappNumbers, BigDecimal overagePerConversationSar) {
            this.arabicName = arabicName;
            this.monthlySar = monthlySar;
            this.annualSar = annualSar;
            this.includedConversations = includedConversations;
            this.marketers = marketers;
            this.whatsappNumbers = whatsappNumbers;
            this.overagePerConversationSar = overagePerConversationSar;
        }

        public boolean handsOverAtLimit() {
            return overagePerConversationSar == null;
        }
    }

    public record Current(Plan plan, int marketers, int includedConversations) {
    }

    public record Usage(Plan plan, LocalDate periodStart, int conversations, int included, int overage, BigDecimal overageSar) {
        public int percent() {
            return included == 0 ? 0 : (int) Math.min(999, Math.round(100.0 * conversations / included));
        }
    }

    /** Agent outcomes that mean a customer got an answer. */
    static final String COUNTED = "('replied', 'fallback', 'voice_note')";
    static final ZoneId RIYADH = ZoneId.of("Asia/Riyadh");

    private final JdbcClient jdbc;

    public PlanLimits(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Must run under the office's tenant. */
    public Current current() {
        Plan p = plan();
        return new Current(p, p.marketers, p.includedConversations);
    }

    public Plan plan() {
        String p = jdbc.sql("select plan from offices").query(String.class).optional().orElse("BASIC");
        return Plan.valueOf(p);
    }

    /** Opens a billable conversation unless this chat already has one from the last 24 hours. */
    public void countConversation(UUID officeId, UUID conversationId) {
        jdbc.sql("""
                insert into agent_sessions (office_id, conversation_id)
                select ?, ? where not exists (
                    select 1 from agent_sessions where conversation_id = ? and started_at > now() - interval '24 hours')""")
                .params(officeId, conversationId, conversationId).update();
    }

    public boolean hasOpenConversation(UUID conversationId) {
        return jdbc.sql("select exists(select 1 from agent_sessions where conversation_id = ? and started_at > now() - interval '24 hours')")
                .param(conversationId).query(Boolean.class).single();
    }

    /** STARTER at its allowance: a chat without an open conversation is handed to the office. */
    public boolean mustHandOver(UUID conversationId) {
        Plan p = plan();
        if (!p.handsOverAtLimit()) return false;
        return usageThisMonth().conversations() >= p.includedConversations && !hasOpenConversation(conversationId);
    }

    public Usage usageThisMonth() {
        LocalDate start = LocalDate.now(RIYADH).withDayOfMonth(1);
        return usageBetween(start, LocalDate.now(RIYADH).plusDays(1));
    }

    public Usage usageSince(LocalDate start) {
        return usageBetween(start, LocalDate.now(RIYADH).plusDays(1));
    }

    /** Conversations started from {@code start} (inclusive) to {@code end} (exclusive), Riyadh days. */
    public Usage usageBetween(LocalDate start, LocalDate end) {
        Plan p = plan();
        ZonedDateTime from = start.atStartOfDay(RIYADH);
        int n = jdbc.sql("select count(*) from agent_sessions where started_at >= ? and started_at < ?")
                .params(Timestamp.from(from.toInstant()), Timestamp.from(end.atStartOfDay(RIYADH).toInstant()))
                .query(Integer.class).single();
        int over = p.handsOverAtLimit() ? 0 : Math.max(0, n - p.includedConversations);
        BigDecimal sar = p.handsOverAtLimit() ? BigDecimal.ZERO : p.overagePerConversationSar.multiply(BigDecimal.valueOf(over));
        return new Usage(p, start, n, p.includedConversations, over, sar);
    }

    /** Agent replies to one conversation since midnight Riyadh time (abuse cap). */
    public int repliesTodayInConversation(UUID conversationId) {
        ZonedDateTime from = LocalDate.now(RIYADH).atStartOfDay(RIYADH);
        return jdbc.sql("select count(*) from agent_runs where conversation_id = ? and outcome in " + COUNTED + " and created_at >= ?")
                .params(conversationId, Timestamp.from(from.toInstant()))
                .query(Integer.class).single();
    }
}
