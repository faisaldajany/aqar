package sa.waslah.agent.booking;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class SlotEngineTest {

    static BookingSettings settings(List<Integer> days) {
        return new BookingSettings(days, LocalTime.of(16, 0), LocalTime.of(18, 0), 45, 15, 60, 60, true, true,
                LocalTime.of(8, 0), LocalTime.of(11, 0), true, true, 30, true, "x", "y");
    }

    // Monday 2026-10-26 10:00 Riyadh
    static final Instant MONDAY_10 = LocalDate.of(2026, 10, 26).atTime(10, 0).atZone(SlotEngine.RIYADH).toInstant();

    @Test
    void slotsFollowHoursStepAndDays() {
        List<SlotEngine.Slot> s = SlotEngine.free(settings(List.of(1)), false, MONDAY_10, List.of(), null, 7, 10);
        // 16:00, 17:00 fit before 18:00 (45 min + 15 buffer step); only Mondays: today and next Monday.
        assertThat(s).extracting(SlotEngine.Slot::label).containsExactly("اليوم 4:00 م", "اليوم 5:00 م");
    }

    @Test
    void respectsLeadTimeAndBusyWithBuffer() {
        Instant at1630 = LocalDate.of(2026, 10, 26).atTime(15, 30).atZone(SlotEngine.RIYADH).toInstant();
        assertThat(SlotEngine.free(settings(List.of(1)), false, at1630, List.of(), LocalDate.of(2026, 10, 26), 1, 10))
                .extracting(SlotEngine.Slot::label).containsExactly("اليوم 5:00 م");

        Instant b1 = LocalDate.of(2026, 10, 26).atTime(16, 50).atZone(SlotEngine.RIYADH).toInstant();
        List<SlotEngine.Interval> busy = List.of(new SlotEngine.Interval(b1, b1.plusSeconds(1800)));
        assertThat(SlotEngine.free(settings(List.of(1)), false, MONDAY_10, busy, LocalDate.of(2026, 10, 26), 1, 10))
                .as("16:00 ends 16:45 + 15 buffer touches 16:50 busy; 17:00 overlaps")
                .isEmpty();
    }

    @Test
    void selfVisitUsesItsOwnHoursAndLabelsTomorrow() {
        List<SlotEngine.Slot> s = SlotEngine.free(settings(List.of(2)), true, MONDAY_10, List.of(), null, 7, 10);
        assertThat(s).extracting(SlotEngine.Slot::label).containsExactly("غداً الثلاثاء 8:00 ص", "غداً الثلاثاء 9:00 ص", "غداً الثلاثاء 10:00 ص");
    }

    @Test
    void fillsTemplatesAndKeepsUnknownPlaceholders() {
        assertThat(BookingService.fill("هلا {العميل}، موعدك {الوقت} {غير_معروف}",
                java.util.Map.of("العميل", "أبو فهد", "الوقت", "5:00 م")))
                .isEqualTo("هلا أبو فهد، موعدك 5:00 م {غير_معروف}");
    }
}
