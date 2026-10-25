package sa.waslah.agent.billing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Plans from the pricing doc (باقات ومزايا وكيل واتساب العقاري — وصلة).
 * Replies over the allowance are not blocked; they are billed per reply.
 */
@Component
public class PlanLimits {

    public enum Plan {
        BASIC("الأساسي", new BigDecimal("499"), new BigDecimal("4990"), 10_000, 2, new BigDecimal("0.12")),
        GROWTH("النمو", new BigDecimal("1000"), new BigDecimal("10000"), 20_000, 10, new BigDecimal("0.10"));

        public final String arabicName;
        public final BigDecimal monthlySar;
        public final BigDecimal annualSar;
        public final int includedReplies;
        public final int marketers;
        public final BigDecimal overagePerReplySar;

        Plan(String arabicName, BigDecimal monthlySar, BigDecimal annualSar, int includedReplies, int marketers,
             BigDecimal overagePerReplySar) {
            this.arabicName = arabicName;
            this.monthlySar = monthlySar;
            this.annualSar = annualSar;
            this.includedReplies = includedReplies;
            this.marketers = marketers;
            this.overagePerReplySar = overagePerReplySar;
        }

        public int marketersLimit() {
            return marketers;
        }
    }

    public record Current(Plan plan, int marketers, int includedReplies) {
    }

    public record Usage(Plan plan, LocalDate periodStart, int replies, int includedReplies, int overageReplies,
                        BigDecimal overageSar) {
        public int percent() {
            return includedReplies == 0 ? 0 : (int) Math.min(999, Math.round(100.0 * replies / includedReplies));
        }
    }

    /** Replies that count against the plan: every message the agent sent to a customer. */
    static final String COUNTED = "('replied', 'fallback', 'voice_note')";
    static final ZoneId RIYADH = ZoneId.of("Asia/Riyadh");

    private final JdbcClient jdbc;

    public PlanLimits(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Must run under the office's tenant. */
    public Current current() {
        Plan p = plan();
        return new Current(p, p.marketers, p.includedReplies);
    }

    public Plan plan() {
        String p = jdbc.sql("select plan from offices").query(String.class).optional().orElse("BASIC");
        return Plan.valueOf(p);
    }

    public Usage usageThisMonth() {
        LocalDate start = LocalDate.now(RIYADH).withDayOfMonth(1);
        return usageSince(start);
    }

    public Usage usageSince(LocalDate start) {
        return usageBetween(start, LocalDate.now(RIYADH).plusDays(1));
    }

    /** Counted replies from {@code start} (inclusive) to {@code end} (exclusive), Riyadh days. */
    public Usage usageBetween(LocalDate start, LocalDate end) {
        Plan p = plan();
        ZonedDateTime from = start.atStartOfDay(RIYADH);
        int replies = jdbc.sql("select count(*) from agent_runs where outcome in " + COUNTED + " and created_at >= ? and created_at < ?")
                .params(java.sql.Timestamp.from(from.toInstant()), java.sql.Timestamp.from(end.atStartOfDay(RIYADH).toInstant()))
                .query(Integer.class).single();
        int over = Math.max(0, replies - p.includedReplies);
        return new Usage(p, start, replies, p.includedReplies, over, p.overagePerReplySar.multiply(BigDecimal.valueOf(over)));
    }

    /** Agent replies to one conversation since midnight Riyadh time (abuse cap). */
    public int repliesTodayInConversation(UUID conversationId) {
        ZonedDateTime from = LocalDate.now(RIYADH).atStartOfDay(RIYADH);
        return jdbc.sql("select count(*) from agent_runs where conversation_id = ? and outcome in " + COUNTED + " and created_at >= ?")
                .params(conversationId, java.sql.Timestamp.from(from.toInstant()))
                .query(Integer.class).single();
    }
}
