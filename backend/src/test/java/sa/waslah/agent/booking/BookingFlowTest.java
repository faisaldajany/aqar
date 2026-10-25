package sa.waslah.agent.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import sa.waslah.agent.IntegrationTest;
import sa.waslah.agent.auth.AuthService;
import sa.waslah.agent.listing.ListingInput;
import sa.waslah.agent.listing.ListingStore;
import sa.waslah.agent.listing.MarketerStore;
import sa.waslah.agent.tenant.TenantContext;
import sa.waslah.agent.whatsapp.EmbeddedSignupService;
import sa.waslah.agent.whatsapp.MockWhatsAppClient;
import sa.waslah.agent.whatsapp.WebhookProcessor;
import sa.waslah.agent.whatsapp.WhatsAppClient;

/** Booking with a marketer, self-visit approval, reminders and follow-up, on a real database. */
class BookingFlowTest extends IntegrationTest {

    @Autowired AuthService auth;
    @Autowired EmbeddedSignupService signup;
    @Autowired WebhookProcessor processor;
    @Autowired ListingStore listings;
    @Autowired MarketerStore marketers;
    @Autowired BookingService service;
    @Autowired BookingStore bookings;
    @Autowired BookingJobs jobs;
    @Autowired WhatsAppClient whatsapp;
    @Autowired JdbcClient jdbc;

    UUID office, conversation, villa, apartment;
    String phoneNumberId, customer = "966551112233";
    // A Sunday morning in the future, so default hours (Sun–Thu, Sat; 16:00–22:00) apply.
    final Instant now = nextSunday().atTime(9, 0).atZone(SlotEngine.RIYADH).toInstant();

    static LocalDate nextSunday() {
        LocalDate d = LocalDate.now().plusDays(7);
        while (d.getDayOfWeek() != java.time.DayOfWeek.SUNDAY) d = d.plusDays(1);
        return d;
    }

    @BeforeEach
    void setUp() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        office = auth.signup(new AuthService.Signup("مكتب " + tag, "12345678", "مالك", tag + "@book.sa", "password123")).officeId();
        phoneNumberId = "PN-" + tag;
        TenantContext.run(office, () -> {
            signup.complete(new EmbeddedSignupService.Request(null, null, phoneNumberId, true));
            marketers.insert(office, "خالد العتيبي", "966551840022", List.of("النرجس"));
            villa = listings.upsert(office, new ListingInput("فيلا درج داخلي", "النرجس", "فيلا", "SALE", new BigDecimal("1950000"),
                    null, 5, "7200418836", LocalDate.now().plusMonths(6), true, null, 24.85, 46.64, null, null, null, "MANUAL", null)).id();
            apartment = listings.upsert(office, new ListingInput("شقة", "النرجس", "شقة", "RENT", new BigDecimal("60000"),
                    null, 3, "7200402271", LocalDate.now().plusMonths(6), true, null, null, null, null, null, null, "MANUAL", null)).id();
            listings.setAccess(apartment, List.of("LOCK"), "قفل على الباب الرئيسي", null, null);
        });
        String body = """
                {"object":"whatsapp_business_account","entry":[{"id":"W","changes":[{"field":"messages","value":{
                "messaging_product":"whatsapp","metadata":{"phone_number_id":"%s"},
                "contacts":[{"profile":{"name":"أبو فهد"},"wa_id":"%s"}],
                "messages":[{"from":"%s","id":"wamid.%s","timestamp":"%d","type":"text","text":{"body":"ابي أعاين"}}]}}]}]}"""
                .formatted(phoneNumberId, customer, customer, tag, Instant.now().getEpochSecond());
        processor.process(body.getBytes(StandardCharsets.UTF_8));
        conversation = TenantContext.call(office, () -> jdbc.sql("select id from conversations").query(UUID.class).single());
        ((MockWhatsAppClient) whatsapp).reset();
    }

    @Test
    void withMarketerIsConfirmedAndTheSlotIsTaken() {
        List<SlotEngine.Slot> slots = TenantContext.call(office, () -> service.slots(office, villa, "WITH_MARKETER", null, now));
        assertThat(slots).isNotEmpty();
        Instant first = slots.get(0).start();
        BookingService.Booked b = TenantContext.call(office, () ->
                service.book(office, conversation, villa, first, "WITH_MARKETER", "أبو فهد", now));
        assertThat(b.status()).isEqualTo("CONFIRMED");
        assertThat(lastText()).contains("خالد العتيبي").contains("أبو فهد").contains("maps.google.com");

        List<SlotEngine.Slot> after = TenantContext.call(office, () -> service.slots(office, villa, "WITH_MARKETER", null, now));
        assertThat(after).extracting(SlotEngine.Slot::start).doesNotContain(first);
        assertThatThrownBy(() -> TenantContext.run(office, () ->
                service.book(office, conversation, apartment, first, "WITH_MARKETER", null, now)))
                .isInstanceOf(BookingService.BookingRefused.class)
                .hasMessageContaining("غير متاح");
    }

    @Test
    void selfVisitNeedsQualificationThenApprovalAndSendsTheCode() {
        Instant slot = TenantContext.call(office, () -> service.slots(office, apartment, "SELF_VISIT", null, now)).get(0).start();
        assertThatThrownBy(() -> TenantContext.run(office, () ->
                service.book(office, conversation, apartment, slot, "SELF_VISIT", null, now)))
                .isInstanceOf(BookingService.BookingRefused.class).hasMessageContaining("ميزانيته");

        TenantContext.run(office, () -> jdbc.sql("""
                insert into leads (conversation_id, office_id, budget_max, payment) values (?, ?, 70000, 'CASH')""")
                .params(conversation, office).update());
        BookingService.Booked b = TenantContext.call(office, () ->
                service.book(office, conversation, apartment, slot, "SELF_VISIT", null, now));
        assertThat(b.status()).isEqualTo("PENDING_APPROVAL");
        assertThat(lastText()).isNull();
        assertThat(TenantContext.call(office, () -> jdbc.sql("select count(*) from office_alerts where kind = 'SELF_VISIT_REQUEST'")
                .query(Integer.class).single())).isEqualTo(1);

        BookingStore.Booking approved = TenantContext.call(office, () -> service.approve(office, b.id(), null, null));
        assertThat(approved.status()).isEqualTo("CONFIRMED");
        assertThat(approved.accessCode()).matches("\\d{6}");
        assertThat(lastText()).contains(approved.accessCode()).contains("قفل على الباب الرئيسي");
    }

    @Test
    void remindsBeforeAndFollowsUpAfter() {
        Instant slot = TenantContext.call(office, () -> service.slots(office, villa, "WITH_MARKETER", null, now)).get(0).start();
        BookingService.Booked b = TenantContext.call(office, () ->
                service.book(office, conversation, villa, slot, "WITH_MARKETER", "أبو فهد", now));
        ((MockWhatsAppClient) whatsapp).reset();

        TenantContext.run(office, () -> jobs.runFor(office, slot.minusSeconds(30 * 60)));
        assertThat(lastText()).startsWith("تذكير");
        TenantContext.run(office, () -> jobs.runFor(office, slot.minusSeconds(20 * 60)));
        assertThat(((MockWhatsAppClient) whatsapp).sent()).as("reminder sent once").hasSize(1);

        TenantContext.run(office, () -> jobs.runFor(office, slot.plusSeconds(45 * 60 + 61 * 60)));
        assertThat(lastText()).contains("كيف كانت زيارتك");
        assertThat(TenantContext.call(office, () -> bookings.find(b.id()).orElseThrow().status())).isEqualTo("DONE");
    }

    private String lastText() {
        List<MockWhatsAppClient.Sent> s = ((MockWhatsAppClient) whatsapp).sent().stream()
                .filter(x -> x.phoneNumberId().equals(phoneNumberId) && x.kind().equals("text")).toList();
        return s.isEmpty() ? null : s.get(s.size() - 1).content();
    }
}
