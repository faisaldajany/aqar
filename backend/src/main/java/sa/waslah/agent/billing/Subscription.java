package sa.waslah.agent.billing;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Where the office stands: trial, paid, overdue or stopped. Must run under the office's tenant. */
@Component
public class Subscription {

    public record State(String status, PlanLimits.Plan plan, String cycle, Instant trialEndsAt, Instant paidUntil,
                        String falStatus, Instant suspendedAt) {

        /** Whether the agent may answer customers right now. A suspended office never is. */
        public boolean serving(Instant now) {
            if (suspendedAt != null) return false;
            return switch (status) {
                case "TRIAL" -> trialEndsAt.isAfter(now);
                case "ACTIVE", "PAST_DUE" -> true; // PAST_DUE is the grace period; the job moves it to EXPIRED
                default -> false;
            };
        }

        public long trialDaysLeft(Instant now) {
            if (!"TRIAL".equals(status) || !trialEndsAt.isAfter(now)) return 0;
            return Math.max(1, (Duration.between(now, trialEndsAt).toHours() + 23) / 24);
        }
    }

    private final JdbcClient jdbc;

    public Subscription(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public State state() {
        return jdbc.sql("""
                        select subscription_status, plan, billing_cycle, trial_ends_at, paid_until, fal_status, suspended_at
                        from offices""")
                .query((rs, n) -> new State(rs.getString(1), PlanLimits.Plan.valueOf(rs.getString(2)), rs.getString(3),
                        rs.getTimestamp(4).toInstant(), ts(rs.getTimestamp(5)), rs.getString(6), ts(rs.getTimestamp(7))))
                .single();
    }

    /** A paid subscription invoice: switch plan and extend the paid period from whichever is later, now or the current end. */
    public void activate(PlanLimits.Plan plan, String cycle, Instant now) {
        activateFor(plan, cycle, "ANNUAL".equals(cycle) ? 12 : 1, now);
    }

    /** Extends the paid period by {@code months} on {@code plan}, from the later of now, the current end and the trial end. */
    public void activateFor(PlanLimits.Plan plan, String cycle, int months, Instant now) {
        State s = state();
        Instant from = now;
        if (s.paidUntil() != null && s.paidUntil().isAfter(from)) from = s.paidUntil();
        if ("TRIAL".equals(s.status()) && s.trialEndsAt().isAfter(from)) from = s.trialEndsAt();
        jdbc.sql("""
                update offices set plan = ?, billing_cycle = ?, subscription_status = 'ACTIVE',
                       paid_until = cast(? as timestamptz) + make_interval(months => ?)""")
                .params(plan.name(), cycle, Timestamp.from(from), months).update();
    }

    public void setStatus(String status) {
        jdbc.sql("update offices set subscription_status = ?").param(status).update();
    }

    private static Instant ts(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
