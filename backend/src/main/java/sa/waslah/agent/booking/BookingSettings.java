package sa.waslah.agent.booking;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** When viewings can be booked and how confirmations are worded, per office. */
public record BookingSettings(
        List<Integer> days,
        LocalTime startTime,
        LocalTime endTime,
        int slotMinutes,
        int bufferMinutes,
        int minLeadMinutes,
        int reminderMinutes,
        boolean selfVisitEnabled,
        boolean selfVisitRequiresApproval,
        LocalTime selfStartTime,
        LocalTime selfEndTime,
        boolean selfVacantOnly,
        boolean selfQualifiedOnly,
        int codeWindowMinutes,
        boolean followUpEnabled,
        String templateRep,
        String templateSelf) {

    @Repository
    public static class Store {
        private static final String COLUMNS = """
                days, start_time, end_time, slot_minutes, buffer_minutes, min_lead_minutes, reminder_minutes,
                self_visit_enabled, self_visit_requires_approval, self_start_time, self_end_time, self_vacant_only,
                self_qualified_only, code_window_minutes, follow_up_enabled, template_rep, template_self""";

        private final JdbcClient jdbc;

        public Store(JdbcClient jdbc) {
            this.jdbc = jdbc;
        }

        /** The office's settings, created with defaults on first use. Runs under the office's tenant. */
        public BookingSettings get(UUID officeId) {
            jdbc.sql("insert into booking_settings (office_id) values (?) on conflict (office_id) do nothing").param(officeId).update();
            return jdbc.sql("select " + COLUMNS + " from booking_settings where office_id = ?").param(officeId)
                    .query(Store::map).single();
        }

        public BookingSettings save(UUID officeId, BookingSettings s) {
            get(officeId);
            jdbc.sql("""
                    update booking_settings set days = cast(:days as int[]), start_time = :st, end_time = :et,
                      slot_minutes = :slot, buffer_minutes = :buf, min_lead_minutes = :lead, reminder_minutes = :rem,
                      self_visit_enabled = :sve, self_visit_requires_approval = :sva, self_start_time = :sst,
                      self_end_time = :set, self_vacant_only = :svo, self_qualified_only = :sqo,
                      code_window_minutes = :cw, follow_up_enabled = :fu, template_rep = :trep, template_self = :tself,
                      updated_at = now()
                    where office_id = :office""")
                    .param("days", "{" + String.join(",", s.days().stream().map(String::valueOf).toList()) + "}")
                    .param("st", java.sql.Time.valueOf(s.startTime())).param("et", java.sql.Time.valueOf(s.endTime()))
                    .param("slot", s.slotMinutes()).param("buf", s.bufferMinutes()).param("lead", s.minLeadMinutes())
                    .param("rem", s.reminderMinutes()).param("sve", s.selfVisitEnabled())
                    .param("sva", s.selfVisitRequiresApproval())
                    .param("sst", java.sql.Time.valueOf(s.selfStartTime())).param("set", java.sql.Time.valueOf(s.selfEndTime()))
                    .param("svo", s.selfVacantOnly()).param("sqo", s.selfQualifiedOnly())
                    .param("cw", s.codeWindowMinutes()).param("fu", s.followUpEnabled())
                    .param("trep", s.templateRep()).param("tself", s.templateSelf()).param("office", officeId)
                    .update();
            return get(officeId);
        }

        private static BookingSettings map(ResultSet rs, int n) throws SQLException {
            Array a = rs.getArray("days");
            List<Integer> days = new ArrayList<>();
            if (a != null) for (Integer d : (Integer[]) a.getArray()) days.add(d);
            return new BookingSettings(days, rs.getTime("start_time").toLocalTime(), rs.getTime("end_time").toLocalTime(),
                    rs.getInt("slot_minutes"), rs.getInt("buffer_minutes"), rs.getInt("min_lead_minutes"),
                    rs.getInt("reminder_minutes"), rs.getBoolean("self_visit_enabled"),
                    rs.getBoolean("self_visit_requires_approval"), rs.getTime("self_start_time").toLocalTime(),
                    rs.getTime("self_end_time").toLocalTime(), rs.getBoolean("self_vacant_only"),
                    rs.getBoolean("self_qualified_only"), rs.getInt("code_window_minutes"),
                    rs.getBoolean("follow_up_enabled"), rs.getString("template_rep"), rs.getString("template_self"));
        }
    }
}
