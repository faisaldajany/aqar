package sa.waslah.agent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import sa.waslah.agent.conversation.ConversationStore;
import sa.waslah.agent.listing.ListingStore;
import sa.waslah.agent.listing.MarketerStore;

/**
 * Tools backed by the office's data, for one conversation. Runs under the office's tenant
 * context, so every query is limited to that office by RLS.
 */
class DbAgentTools implements AgentTools {

    private static final Logger log = LoggerFactory.getLogger(DbAgentTools.class);

    /** Alerts a marketer about a handoff. Implemented by AgentService (WhatsApp template); may do nothing. */
    interface MarketerAlert {
        void send(MarketerStore.Marketer marketer, String customerLabel, String summary);
    }

    private final UUID officeId;
    private final UUID conversationId;
    private final String customerLabel;
    private final ObjectMapper json;
    private final JdbcClient jdbc;
    private final ListingStore listings;
    private final MarketerStore marketers;
    private final ConversationStore conversations;
    private final MarketerAlert alert;
    private final int pauseHours;

    private final List<String> licenses = new ArrayList<>();
    private boolean handedOff;

    DbAgentTools(UUID officeId, UUID conversationId, String customerLabel, ObjectMapper json, JdbcClient jdbc,
                 ListingStore listings, MarketerStore marketers, ConversationStore conversations,
                 MarketerAlert alert, int pauseHours) {
        this.officeId = officeId;
        this.conversationId = conversationId;
        this.customerLabel = customerLabel;
        this.json = json;
        this.jdbc = jdbc;
        this.listings = listings;
        this.marketers = marketers;
        this.conversations = conversations;
        this.alert = alert;
        this.pauseHours = pauseHours;
    }

    @Override
    public List<String> licensesSeen() {
        return List.copyOf(licenses);
    }

    @Override
    public boolean handedOff() {
        return handedOff;
    }

    @Override
    public JsonNode call(String name, JsonNode args) {
        try {
            return switch (name) {
                case SEARCH -> search(args);
                case SAVE_LEAD -> saveLead(args);
                case REQUEST_VIEWING -> requestViewing(args);
                case HANDOFF -> handoff(args.path("reason").asText("OTHER"), args.path("summary").asText(""), null);
                default -> error("unknown tool " + name);
            };
        } catch (RuntimeException e) {
            log.warn("Tool {} failed: {}", name, e.getMessage());
            return error("tool failed");
        }
    }

    private JsonNode search(JsonNode a) {
        List<ListingStore.Listing> found = listings.search(new ListingStore.Query(
                text(a, "purpose"), text(a, "property_type"), text(a, "district"),
                number(a, "max_price"), number(a, "min_price"),
                a.hasNonNull("min_rooms") ? a.path("min_rooms").asInt() : null, 3));
        ObjectNode out = json.createObjectNode().put("count", found.size());
        ArrayNode arr = out.putArray("listings");
        for (ListingStore.Listing l : found) {
            licenses.add(l.adLicense());
            ObjectNode o = arr.addObject();
            o.put("id", l.id().toString());
            o.put("title", l.title());
            o.put("district", l.district());
            o.put("property_type", l.propertyType());
            o.put("purpose", l.purpose());
            o.put("price_sar", l.price());
            if (l.areaSqm() != null) o.put("area_sqm", l.areaSqm());
            if (l.rooms() != null) o.put("rooms", l.rooms());
            o.put("ad_license", l.adLicense());
            o.put("license_expiry", l.licenseExpiry().toString());
            o.put("occupied", !l.vacant());
        }
        if (found.isEmpty()) {
            out.put("note", "لا توجد عقارات مطابقة حالياً");
        }
        return out;
    }

    private JsonNode saveLead(JsonNode a) {
        jdbc.sql("""
                insert into leads (conversation_id, office_id, customer_name, purpose, property_type, districts,
                                   budget_min, budget_max, payment, timeline, notes)
                values (:c, :o, :name, :purpose, :ptype, :districts, :bmin, :bmax, :payment, :timeline, :notes)
                on conflict (conversation_id) do update set
                    customer_name = coalesce(excluded.customer_name, leads.customer_name),
                    purpose       = coalesce(excluded.purpose, leads.purpose),
                    property_type = coalesce(excluded.property_type, leads.property_type),
                    districts     = coalesce(excluded.districts, leads.districts),
                    budget_min    = coalesce(excluded.budget_min, leads.budget_min),
                    budget_max    = coalesce(excluded.budget_max, leads.budget_max),
                    payment       = coalesce(excluded.payment, leads.payment),
                    timeline      = coalesce(excluded.timeline, leads.timeline),
                    notes         = coalesce(excluded.notes, leads.notes),
                    updated_at    = now()""")
                .param("c", conversationId).param("o", officeId)
                .param("name", text(a, "customer_name")).param("purpose", text(a, "purpose"))
                .param("ptype", text(a, "property_type")).param("districts", text(a, "districts"))
                .param("bmin", number(a, "budget_min")).param("bmax", number(a, "budget_max"))
                .param("payment", text(a, "payment")).param("timeline", text(a, "timeline"))
                .param("notes", text(a, "notes"))
                .update();
        Lead lead = lead();
        ObjectNode out = json.createObjectNode().put("saved", true);
        ArrayNode missing = out.putArray("still_unknown");
        if (lead.purpose == null) missing.add("purpose");
        if (lead.propertyType == null) missing.add("property_type");
        if (lead.districts == null) missing.add("districts");
        if (lead.budgetMax == null) missing.add("budget");
        if (lead.payment == null) missing.add("payment");
        if (lead.timeline == null) missing.add("timeline");
        return out;
    }

    private JsonNode requestViewing(JsonNode a) {
        UUID listingId;
        try {
            listingId = UUID.fromString(a.path("listing_id").asText());
        } catch (IllegalArgumentException e) {
            return error("listing_id must be an id returned by search_listings");
        }
        Optional<ListingStore.Listing> listing = listings.find(listingId);
        if (listing.isEmpty() || !"ACTIVE".equals(listing.get().status())) {
            return error("listing not available");
        }
        ListingStore.Listing l = listing.get();
        String mode = "SELF_VISIT".equals(a.path("mode").asText()) && l.vacant() ? "زيارة ذاتية" : "معاينة مع مسوّق";
        String summary = "طلب " + mode + ": " + l.title() + " في " + l.district()
                + " (ترخيص " + l.adLicense() + ")\nالموعد المطلوب: " + a.path("preferred_time").asText("غير محدد")
                + "\n" + leadLine();
        Optional<MarketerStore.Marketer> m = l.marketerId() != null
                ? marketers.find(l.marketerId()) : marketers.forDistrict(l.district());
        insertHandoff("VIEWING_REQUEST", summary, m.map(MarketerStore.Marketer::id).orElse(null), listingId);
        m.ifPresent(x -> alert.send(x, customerLabel, summary));
        return json.createObjectNode()
                .put("recorded", true)
                .put("next_step", "المكتب يؤكد الموعد مع العميل")
                .put("marketer_first_name", m.map(x -> x.name().split("\\s+")[0]).orElse(""));
    }

    private JsonNode handoff(String reason, String summary, UUID listingId) {
        Lead lead = lead();
        String district = lead.districts == null ? null : lead.districts.split("[,،]")[0].trim();
        Optional<MarketerStore.Marketer> m = marketers.forDistrict(district);
        String full = (summary == null || summary.isBlank() ? "" : summary + "\n") + leadLine();
        insertHandoff(reason, full, m.map(MarketerStore.Marketer::id).orElse(null), listingId);
        conversations.pauseAgent(conversationId, ConversationStore.humanPauseUntil(Instant.now(), pauseHours));
        handedOff = true;
        m.ifPresent(x -> alert.send(x, customerLabel, full));
        return json.createObjectNode()
                .put("handed_off", true)
                .put("marketer_first_name", m.map(x -> x.name().split("\\s+")[0]).orElse(""));
    }

    private void insertHandoff(String reason, String summary, UUID marketerId, UUID listingId) {
        jdbc.sql("""
                insert into handoffs (office_id, conversation_id, marketer_id, reason, summary, listing_id)
                values (?, ?, ?::uuid, ?, ?, ?::uuid)""")
                .params(officeId, conversationId, marketerId, reason, summary, listingId)
                .update();
    }

    private record Lead(String purpose, String propertyType, String districts, BigDecimal budgetMax,
                        String payment, String timeline) {
    }

    private Lead lead() {
        return jdbc.sql("select purpose, property_type, districts, budget_max, payment, timeline from leads where conversation_id = ?")
                .param(conversationId)
                .query((rs, n) -> new Lead(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBigDecimal(4),
                        rs.getString(5), rs.getString(6)))
                .optional()
                .orElse(new Lead(null, null, null, null, null, null));
    }

    private String leadLine() {
        Lead l = lead();
        List<String> parts = new ArrayList<>();
        if (l.purpose != null) parts.add("SALE".equals(l.purpose) ? "شراء" : "إيجار");
        if (l.propertyType != null) parts.add(l.propertyType);
        if (l.districts != null) parts.add(l.districts);
        if (l.budgetMax != null) parts.add("حتى " + l.budgetMax.stripTrailingZeros().toPlainString() + " ريال");
        if (l.payment != null) parts.add(switch (l.payment) {
            case "CASH" -> "كاش";
            case "FINANCE" -> "تمويل";
            case "INSTALLMENTS" -> "تقسيط";
            default -> "الدفع غير محدد";
        });
        if (l.timeline != null) parts.add(l.timeline);
        return parts.isEmpty() ? "لم تُجمع تفاصيل بعد" : String.join(" · ", parts);
    }

    private ObjectNode error(String message) {
        return json.createObjectNode().put("error", message);
    }

    private static String text(JsonNode a, String field) {
        String s = a.path(field).asText(null);
        return s == null || s.isBlank() || "UNKNOWN".equals(s) ? null : s.trim();
    }

    private static BigDecimal number(JsonNode a, String field) {
        JsonNode n = a.path(field);
        if (n.isNumber()) return n.decimalValue();
        if (n.isTextual()) {
            try {
                return new BigDecimal(n.asText().replaceAll("[^\\d.]", ""));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
