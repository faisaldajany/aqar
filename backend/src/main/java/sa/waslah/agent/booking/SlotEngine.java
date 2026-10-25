package sa.waslah.agent.booking;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure slot calculation in Riyadh time. A slot is free when it is inside the office's hours for
 * that mode, starts after the minimum lead time, and does not overlap a busy interval
 * (with the buffer added on both sides).
 */
public final class SlotEngine {

    public static final ZoneId RIYADH = ZoneId.of("Asia/Riyadh");
    static final String[] DAY_NAMES = {"الأحد", "الاثنين", "الثلاثاء", "الأربعاء", "الخميس", "الجمعة", "السبت"};

    public record Interval(Instant start, Instant end) {
        boolean overlaps(Instant s, Instant e) {
            return s.isBefore(end) && e.isAfter(start);
        }
    }

    public record Slot(Instant start, Instant end, String label) {
    }

    private SlotEngine() {
    }

    public static List<Slot> free(BookingSettings s, boolean selfVisit, Instant now, List<Interval> busy,
                                  LocalDate onlyDate, int daysAhead, int max) {
        List<Slot> out = new ArrayList<>();
        LocalTime open = selfVisit ? s.selfStartTime() : s.startTime();
        LocalTime close = selfVisit ? s.selfEndTime() : s.endTime();
        int step = s.slotMinutes() + s.bufferMinutes();
        Instant earliest = now.plusSeconds(s.minLeadMinutes() * 60L);
        LocalDate today = now.atZone(RIYADH).toLocalDate();
        LocalDate first = onlyDate != null ? onlyDate : today;
        int span = onlyDate != null ? 1 : daysAhead;
        for (int d = 0; d < span && out.size() < max; d++) {
            LocalDate date = first.plusDays(d);
            if (!s.days().contains(dayIndex(date.getDayOfWeek()))) continue;
            for (ZonedDateTime t = date.atTime(open).atZone(RIYADH);
                 !t.toLocalTime().plusMinutes(s.slotMinutes()).isAfter(close) && t.toLocalDate().equals(date) && out.size() < max;
                 t = t.plusMinutes(step)) {
                Instant start = t.toInstant();
                Instant end = start.plusSeconds(s.slotMinutes() * 60L);
                if (start.isBefore(earliest)) continue;
                Instant padStart = start.minusSeconds(s.bufferMinutes() * 60L);
                Instant padEnd = end.plusSeconds(s.bufferMinutes() * 60L);
                if (busy.stream().anyMatch(b -> b.overlaps(padStart, padEnd))) continue;
                out.add(new Slot(start, end, label(start, today)));
            }
        }
        return out;
    }

    /** 0 = Sunday … 6 = Saturday, as stored in booking_settings.days. */
    static int dayIndex(DayOfWeek d) {
        return d.getValue() % 7;
    }

    /** "اليوم 5:00 م", "غداً الأحد 7:30 م", "الثلاثاء 28/10 4:00 م". */
    public static String label(Instant start, LocalDate today) {
        ZonedDateTime z = start.atZone(RIYADH);
        LocalDate date = z.toLocalDate();
        String day = DAY_NAMES[dayIndex(date.getDayOfWeek())];
        String prefix = date.equals(today) ? "اليوم" : date.equals(today.plusDays(1)) ? "غداً " + day
                : day + " " + date.getDayOfMonth() + "/" + date.getMonthValue();
        return prefix + " " + time(z.toLocalTime());
    }

    public static String time(LocalTime t) {
        int h = t.getHour() % 12 == 0 ? 12 : t.getHour() % 12;
        return h + ":" + String.format("%02d", t.getMinute()) + (t.getHour() < 12 ? " ص" : " م");
    }

    public static String dayLabel(Instant start, LocalDate today) {
        ZonedDateTime z = start.atZone(RIYADH);
        LocalDate date = z.toLocalDate();
        String day = DAY_NAMES[dayIndex(date.getDayOfWeek())];
        if (date.equals(today)) return "اليوم " + day;
        if (date.equals(today.plusDays(1))) return "غداً " + day;
        return day + " " + date.getDayOfMonth() + "/" + date.getMonthValue();
    }
}
