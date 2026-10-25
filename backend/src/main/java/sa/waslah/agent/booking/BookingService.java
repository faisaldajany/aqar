package sa.waslah.agent.booking;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import sa.waslah.agent.common.ApiException;
import sa.waslah.agent.config.WaslahProperties;
import sa.waslah.agent.conversation.ConversationStore;
import sa.waslah.agent.conversation.ConversationStore.Sender;
import sa.waslah.agent.listing.ListingStore;
import sa.waslah.agent.listing.MarketerStore;
import sa.waslah.agent.office.OfficeAlerts;
import sa.waslah.agent.whatsapp.MessagingWindowPolicy;
import sa.waslah.agent.whatsapp.OutboundMessageService;
import sa.waslah.agent.whatsapp.WaAccounts;
import sa.waslah.agent.whatsapp.WhatsAppClient;

/**
 * Viewings: free slots, booking from the agent, office approval of self-visits, and the messages
 * the customer receives, worded by the office's own templates. Every method runs under the
 * office's tenant.
 */
@Service
public class BookingService {

    private static final Logger log = LoggerFactory.getLogger(BookingService.class);
    public static final String WITH_MARKETER = "WITH_MARKETER";
    public static final String SELF_VISIT = "SELF_VISIT";
    static final Map<String, String> ACCESS_LABELS = Map.of("LOCK", "رمز قفل ذكي", "GUARD", "الحارس أو المالك في الموقع", "KEY", "استلام المفتاح");

    /** Why a booking cannot be made, in words the agent can relay. */
    public static class BookingRefused extends RuntimeException {
        public final String code;

        public BookingRefused(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    public record Booked(UUID id, String status, String label, String marketerFirstName) {
    }

    private final BookingStore bookings;
    private final BookingSettings.Store settings;
    private final ListingStore listings;
    private final MarketerStore marketers;
    private final ConversationStore conversations;
    private final OutboundMessageService outbound;
    private final WaAccounts accounts;
    private final WhatsAppClient whatsapp;
    private final MessagingWindowPolicy window;
    private final OfficeAlerts alerts;
    private final JdbcClient jdbc;
    private final WaslahProperties props;
    private final SecureRandom random = new SecureRandom();

    public BookingService(BookingStore bookings, BookingSettings.Store settings, ListingStore listings,
                          MarketerStore marketers, ConversationStore conversations, OutboundMessageService outbound,
                          WaAccounts accounts, WhatsAppClient whatsapp, MessagingWindowPolicy window, OfficeAlerts alerts,
                          JdbcClient jdbc, WaslahProperties props) {
        this.bookings = bookings;
        this.settings = settings;
        this.listings = listings;
        this.marketers = marketers;
        this.conversations = conversations;
        this.outbound = outbound;
        this.accounts = accounts;
        this.whatsapp = whatsapp;
        this.window = window;
        this.alerts = alerts;
        this.jdbc = jdbc;
        this.props = props;
    }

    // ---- slots -----------------------------------------------------------------------------

    public List<SlotEngine.Slot> slots(UUID officeId, UUID listingId, String mode, LocalDate date, Instant now) {
        ListingStore.Listing l = listing(listingId);
        BookingSettings s = settings.get(officeId);
        boolean self = SELF_VISIT.equals(mode);
        if (self) checkSelfVisitAllowed(s, l, null);
        UUID marketer = self ? null : marketerFor(l).id();
        Instant to = now.plus(Duration.ofDays(15));
        return SlotEngine.free(s, self, now, bookings.busy(marketer, l.id(), now, to), date, 7, 6);
    }

    // ---- booking from the agent ------------------------------------------------------------

    public Booked book(UUID officeId, UUID conversationId, UUID listingId, Instant start, String mode, String customerName,
                       Instant now) {
        ListingStore.Listing l = listing(listingId);
        BookingSettings s = settings.get(officeId);
        boolean self = SELF_VISIT.equals(mode);
        if (self) checkSelfVisitAllowed(s, l, conversationId);
        MarketerStore.Marketer m = marketerFor(l);

        List<SlotEngine.Slot> free = SlotEngine.free(s, self, now,
                bookings.busy(self ? null : m.id(), l.id(), now, now.plus(Duration.ofDays(15))),
                start.atZone(SlotEngine.RIYADH).toLocalDate(), 1, 100);
        SlotEngine.Slot slot = free.stream().filter(x -> x.start().equals(start)).findFirst()
                .orElseThrow(() -> new BookingRefused("slot_unavailable",
                        "هذا الموعد غير متاح. استدعِ get_viewing_slots واعرض على العميل المواعيد المتاحة."));
        if (!bookings.forConversation(conversationId).stream().filter(b -> b.listingId().equals(listingId)).toList().isEmpty()) {
            throw new BookingRefused("already_booked", "لدى العميل موعد قائم لهذا العقار.");
        }

        String status = self && s.selfVisitRequiresApproval() ? "PENDING_APPROVAL" : "CONFIRMED";
        UUID id = bookings.create(officeId, conversationId, listingId, self ? null : m.id(), mode, slot.start(), slot.end(),
                status, customerName, null);
        BookingStore.Booking b = bookings.find(id).orElseThrow();

        if (self && "PENDING_APPROVAL".equals(status)) {
            alerts.raise(officeId, "SELF_VISIT_REQUEST", id, "طلب زيارة ذاتية لـ" + l.title() + " في " + l.district() + " — "
                    + slot.label() + ". راجع الطلب ووافق أو اعتذر.", "self-" + id);
        } else if (self) {
            String method = l.selfVisitAccess().get(0);
            confirm(officeId, b, l, s, method, "LOCK".equals(method) ? newCode() : null);
        } else {
            confirm(officeId, b, l, s, null, null);
            alerts.raise(officeId, "BOOKING", id, "معاينة جديدة مع " + m.name() + ": " + l.title() + " — " + slot.label() + ".", "booking-" + id);
        }
        return new Booked(id, status, slot.label(), m.name().split("\\s+")[0]);
    }

    public void cancelByCustomer(UUID conversationId, UUID bookingId) {
        BookingStore.Booking b = bookings.find(bookingId)
                .filter(x -> x.conversationId().equals(conversationId))
                .orElseThrow(() -> new BookingRefused("no_booking", "لا يوجد موعد بهذا المعرّف لهذا العميل."));
        bookings.setStatus(b.id(), "CANCELLED");
    }

    // ---- office actions --------------------------------------------------------------------

    public BookingStore.Booking approve(UUID officeId, UUID bookingId, String accessMethod, String accessCode) {
        BookingStore.Booking b = require(bookingId);
        if (!"PENDING_APPROVAL".equals(b.status())) {
            throw ApiException.conflict("not_pending", "هذا الطلب ليس بانتظار الموافقة.");
        }
        ListingStore.Listing l = listing(b.listingId());
        String method = accessMethod != null ? accessMethod : l.selfVisitAccess().isEmpty() ? null : l.selfVisitAccess().get(0);
        if (method == null || !ACCESS_LABELS.containsKey(method)) {
            throw ApiException.badRequest("no_access", "حدّد طريقة الدخول (قفل ذكي، حارس، أو استلام مفتاح).");
        }
        String code = "LOCK".equals(method) ? (accessCode == null || accessCode.isBlank() ? newCode() : accessCode.trim()) : null;
        confirm(officeId, b, l, settings.get(officeId), method, code);
        return require(bookingId);
    }

    public void reject(UUID officeId, UUID bookingId, String reason) {
        BookingStore.Booking b = require(bookingId);
        bookings.decide(b.id(), "REJECTED", null, null, null);
        String text = "نعتذر، الزيارة الذاتية لـ" + b.listingTitle() + " غير متاحة في هذا الموعد"
                + (reason == null || reason.isBlank() ? "" : " (" + reason.trim() + ")")
                + ". تبي أحجز لك معاينة مع المسوّق بدلاً منها؟";
        sendToCustomer(b.conversationId(), text, null, List.of());
    }

    public void setStatus(UUID bookingId, String status) {
        require(bookingId);
        bookings.setStatus(bookingId, status);
    }

    /** Fills the office's template; unknown placeholders stay visible so mistakes are noticed in the preview. */
    public String preview(UUID officeId, String template, String mode) {
        Map<String, String> v = new HashMap<>();
        v.put("العميل", "أبو عبدالله");
        v.put("العقار", "فيلا درج داخلي");
        v.put("الحي", "النرجس");
        v.put("اليوم", "غداً الأحد");
        v.put("الوقت", "5:00 م");
        v.put("من", "5:00 م");
        v.put("إلى", "5:45 م");
        v.put("الموقع", "https://maps.google.com/?q=24.8521,46.6487");
        v.put("المسوق", "خالد العتيبي");
        v.put("جوال_المسوق", "0551840022");
        v.put("الرمز", "482913");
        v.put("طريقة_الدخول", SELF_VISIT.equals(mode) ? "رمز الدخول: 482913 (يعمل من 4:30 م إلى 6:15 م فقط)" : "");
        return fill(template, v);
    }

    // ---- internals -------------------------------------------------------------------------

    private void confirm(UUID officeId, BookingStore.Booking b, ListingStore.Listing l, BookingSettings s, String method, String code) {
        boolean self = SELF_VISIT.equals(b.mode());
        LocalDate today = LocalDate.now(SlotEngine.RIYADH);
        ZonedDateTime start = b.startsAt().atZone(SlotEngine.RIYADH);
        ZonedDateTime end = b.endsAt().atZone(SlotEngine.RIYADH);
        Map<String, String> v = new HashMap<>();
        v.put("العميل", b.customerName() == null || b.customerName().isBlank() ? "حياك الله" : b.customerName());
        v.put("العقار", l.title());
        v.put("الحي", l.district());
        v.put("اليوم", SlotEngine.dayLabel(b.startsAt(), today));
        v.put("الوقت", SlotEngine.time(start.toLocalTime()));
        v.put("من", SlotEngine.time(start.toLocalTime()));
        v.put("إلى", SlotEngine.time(end.toLocalTime()));
        v.put("الموقع", l.hasLocation() ? "https://maps.google.com/?q=" + l.latitude() + "," + l.longitude() : "يرسله المكتب");
        Optional<MarketerStore.Marketer> m = b.marketerId() == null ? Optional.empty() : marketers.find(b.marketerId());
        v.put("المسوق", m.map(MarketerStore.Marketer::name).orElse("المسوّق"));
        v.put("جوال_المسوق", m.map(x -> "0" + x.waPhone().substring(3)).orElse(""));
        v.put("الرمز", code == null ? "" : code);
        v.put("طريقة_الدخول", self ? accessText(l, s, method, code, start, end) : "");
        String text = fill(self ? s.templateSelf() : s.templateRep(), v).replaceAll("\n{3,}", "\n\n").trim();

        bookings.decide(b.id(), "CONFIRMED", method, code, text);
        boolean sent = sendToCustomer(b.conversationId(), text, props.agent().reminderTemplate(),
                List.of(v.get("العميل"), l.title(), v.get("اليوم") + " " + v.get("الوقت")));
        if (!sent) {
            alerts.raise(officeId, "CONFIRMATION_NOT_SENT", b.id(),
                    "تعذّر إرسال تأكيد الموعد للعميل على واتساب (انتهت نافذة 24 ساعة ولا يوجد قالب معتمد). أبلغه يدوياً.",
                    "confirm-" + b.id());
        }
    }

    private static String accessText(ListingStore.Listing l, BookingSettings s, String method, String code,
                                     ZonedDateTime start, ZonedDateTime end) {
        if (method == null) return "";
        return switch (method) {
            case "LOCK" -> "رمز الدخول: " + code + " (يعمل من " + SlotEngine.time(start.minusMinutes(s.codeWindowMinutes()).toLocalTime())
                    + " إلى " + SlotEngine.time(end.plusMinutes(s.codeWindowMinutes()).toLocalTime()) + " فقط)"
                    + (l.accessLockNote() == null ? "" : ". " + l.accessLockNote());
            case "GUARD" -> "عند الوصول تواصل مع: " + (l.accessGuard() == null ? "الحارس" : l.accessGuard());
            case "KEY" -> "استلم المفتاح " + (l.accessKeyPoint() == null ? "من المكتب" : l.accessKeyPoint())
                    + " قبل موعدك بـ15 دقيقة، وأعده بعد الزيارة.";
            default -> "";
        };
    }

    /** Free text inside the 24-hour window; otherwise the approved template if configured. Returns false if nothing was sent. */
    boolean sendToCustomer(UUID conversationId, String text, String templateOutsideWindow, List<String> templateParams) {
        ConversationStore.ConversationRef conv = conversations.find(conversationId).orElseThrow();
        if (window.isOpen(conv.lastInboundAt(), Instant.now())) {
            outbound.sendText(conversationId, text, Sender.AGENT);
            return true;
        }
        if (templateOutsideWindow == null || templateOutsideWindow.isBlank() || conv.waAccountId() == null) {
            return false;
        }
        try {
            WaAccounts.Account a = accounts.byId(conv.waAccountId()).orElseThrow();
            String wamid = whatsapp.sendTemplate(accounts.credentials(a), conv.waId(), templateOutsideWindow,
                    props.agent().handoffTemplateLanguage(), templateParams);
            conversations.insertMessage(a.officeId(), conversationId, wamid, "OUT", Sender.SYSTEM, "template", text, null,
                    "sent", Instant.now());
            return true;
        } catch (RuntimeException e) {
            log.warn("Template send failed: {}", e.getMessage());
            return false;
        }
    }

    private void checkSelfVisitAllowed(BookingSettings s, ListingStore.Listing l, UUID conversationId) {
        if (!s.selfVisitEnabled()) {
            throw new BookingRefused("self_disabled", "المكتب لا يتيح الزيارة الذاتية؛ اعرض معاينة مع المسوّق.");
        }
        if (s.selfVacantOnly() && !l.vacant()) {
            throw new BookingRefused("occupied", "العقار مسكون، فالمعاينة مع المسوّق فقط.");
        }
        if (l.selfVisitAccess().isEmpty()) {
            throw new BookingRefused("no_access", "لم يحدد المكتب طريقة دخول لهذا العقار؛ اعرض معاينة مع المسوّق.");
        }
        if (conversationId != null && s.selfQualifiedOnly()) {
            Boolean qualified = jdbc.sql("select budget_max is not null and payment is not null from leads where conversation_id = ?")
                    .param(conversationId).query(Boolean.class).optional().orElse(false);
            if (!qualified) {
                throw new BookingRefused("not_qualified", "قبل الزيارة الذاتية اسأل العميل عن ميزانيته وطريقة الدفع وسجّلها بـ save_lead.");
            }
        }
    }

    private MarketerStore.Marketer marketerFor(ListingStore.Listing l) {
        Optional<MarketerStore.Marketer> m = l.marketerId() != null ? marketers.find(l.marketerId()).filter(MarketerStore.Marketer::active)
                : Optional.empty();
        return m.or(() -> marketers.forDistrict(l.district()))
                .orElseThrow(() -> new BookingRefused("no_marketer", "لا يوجد مسوّق مسجّل لدى المكتب؛ حوّل العميل للمكتب."));
    }

    private ListingStore.Listing listing(UUID id) {
        ListingStore.Listing l = listings.find(id)
                .orElseThrow(() -> new BookingRefused("no_listing", "العقار غير موجود؛ استخدم معرّفاً من search_listings."));
        if (!"ACTIVE".equals(l.status()) || !l.licenseValid(LocalDate.now(SlotEngine.RIYADH))) {
            throw new BookingRefused("listing_unavailable", "العقار غير متاح للحجز حالياً.");
        }
        return l;
    }

    private BookingStore.Booking require(UUID id) {
        return bookings.find(id).orElseThrow(() -> ApiException.notFound("no_booking", "الموعد غير موجود."));
    }

    private String newCode() {
        return String.valueOf(100000 + random.nextInt(900000));
    }

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^{}]+)}");

    static String fill(String template, Map<String, String> v) {
        Matcher m = PLACEHOLDER.matcher(template == null ? "" : template);
        StringBuilder b = new StringBuilder();
        while (m.find()) {
            String key = m.group(1).trim();
            m.appendReplacement(b, Matcher.quoteReplacement(v.getOrDefault(key, "{" + key + "}")));
        }
        m.appendTail(b);
        return b.toString();
    }
}
