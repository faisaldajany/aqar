package sa.waslah.agent.booking;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import sa.waslah.agent.common.ApiException;
import sa.waslah.agent.listing.ListingStore;
import sa.waslah.agent.tenant.TenantContext;

@RestController
public class BookingController {

    public record Approve(@Pattern(regexp = "LOCK|GUARD|KEY") String accessMethod, String accessCode) {
    }

    public record Reject(String reason) {
    }

    public record StatusChange(@NotBlank @Pattern(regexp = "DONE|NO_SHOW|CANCELLED") String status) {
    }

    public record Settings(
            @NotEmpty List<@Min(0) @Max(6) Integer> days,
            @NotNull LocalTime startTime, @NotNull LocalTime endTime,
            @Min(15) @Max(240) int slotMinutes, @Min(0) @Max(120) int bufferMinutes,
            @Min(0) @Max(2880) int minLeadMinutes, @Min(15) @Max(2880) int reminderMinutes,
            boolean selfVisitEnabled, boolean selfVisitRequiresApproval,
            @NotNull LocalTime selfStartTime, @NotNull LocalTime selfEndTime,
            boolean selfVacantOnly, boolean selfQualifiedOnly,
            @Min(0) @Max(240) int codeWindowMinutes, boolean followUpEnabled,
            @NotBlank String templateRep, @NotBlank String templateSelf) {
    }

    public record Access(List<@Pattern(regexp = "LOCK|GUARD|KEY") String> methods, String lockNote, String guard,
                         String keyPoint) {
    }

    public record PreviewRequest(@NotBlank String template, @Pattern(regexp = "WITH_MARKETER|SELF_VISIT") String mode) {
    }

    private final BookingService service;
    private final BookingStore bookings;
    private final BookingSettings.Store settings;
    private final ListingStore listings;

    public BookingController(BookingService service, BookingStore bookings, BookingSettings.Store settings, ListingStore listings) {
        this.service = service;
        this.bookings = bookings;
        this.settings = settings;
        this.listings = listings;
    }

    /** Bookings from yesterday through the next 14 days (or the given range). */
    @GetMapping("/api/bookings")
    List<BookingStore.Booking> list(@RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to) {
        LocalDate start = from != null ? from : LocalDate.now(SlotEngine.RIYADH).minusDays(1);
        LocalDate end = to != null ? to : start.plusDays(15);
        return bookings.between(start.atStartOfDay(SlotEngine.RIYADH).toInstant(), end.atStartOfDay(SlotEngine.RIYADH).toInstant());
    }

    @GetMapping("/api/bookings/pending")
    List<BookingStore.Booking> pending() {
        return bookings.pending();
    }

    @PostMapping("/api/bookings/{id}/approve")
    BookingStore.Booking approve(@PathVariable UUID id, @Valid @RequestBody(required = false) Approve a) {
        return service.approve(TenantContext.require(), id, a == null ? null : a.accessMethod(), a == null ? null : a.accessCode());
    }

    @PostMapping("/api/bookings/{id}/reject")
    Map<String, Object> reject(@PathVariable UUID id, @RequestBody(required = false) Reject r) {
        service.reject(TenantContext.require(), id, r == null ? null : r.reason());
        return Map.of("status", "REJECTED");
    }

    @PostMapping("/api/bookings/{id}/status")
    Map<String, Object> status(@PathVariable UUID id, @Valid @RequestBody StatusChange s) {
        service.setStatus(id, s.status());
        return Map.of("status", s.status());
    }

    @GetMapping("/api/listings/{id}/slots")
    List<SlotEngine.Slot> slots(@PathVariable UUID id, @RequestParam(defaultValue = "WITH_MARKETER") String mode,
                                @RequestParam(required = false) LocalDate date) {
        try {
            return service.slots(TenantContext.require(), id, mode, date, Instant.now());
        } catch (BookingService.BookingRefused e) {
            throw ApiException.badRequest(e.code, e.getMessage());
        }
    }

    @PutMapping("/api/listings/{id}/access")
    Map<String, Object> access(@PathVariable UUID id, @Valid @RequestBody Access a) {
        listings.find(id).orElseThrow(() -> ApiException.notFound("no_listing", "العقار غير موجود."));
        listings.setAccess(id, a.methods() == null ? List.of() : a.methods(), a.lockNote(), a.guard(), a.keyPoint());
        return Map.of("saved", true);
    }

    @GetMapping("/api/booking-settings")
    BookingSettings getSettings() {
        return settings.get(TenantContext.require());
    }

    @PutMapping("/api/booking-settings")
    BookingSettings saveSettings(@Valid @RequestBody Settings s) {
        if (!s.endTime().isAfter(s.startTime()) || !s.selfEndTime().isAfter(s.selfStartTime())) {
            throw ApiException.badRequest("bad_hours", "وقت النهاية يجب أن يكون بعد وقت البداية في اليوم نفسه.");
        }
        if (Duration.between(s.startTime(), s.endTime()).toMinutes() < s.slotMinutes()) {
            throw ApiException.badRequest("bad_hours", "ساعات المعاينة أقصر من مدة موعد واحد.");
        }
        return settings.save(TenantContext.require(), new BookingSettings(s.days().stream().distinct().sorted().toList(),
                s.startTime(), s.endTime(), s.slotMinutes(), s.bufferMinutes(), s.minLeadMinutes(), s.reminderMinutes(),
                s.selfVisitEnabled(), s.selfVisitRequiresApproval(), s.selfStartTime(), s.selfEndTime(), s.selfVacantOnly(),
                s.selfQualifiedOnly(), s.codeWindowMinutes(), s.followUpEnabled(), s.templateRep(), s.templateSelf()));
    }

    @PostMapping("/api/booking-settings/preview")
    Map<String, String> preview(@Valid @RequestBody PreviewRequest r) {
        return Map.of("text", service.preview(TenantContext.require(), r.template(), r.mode()));
    }
}
