package sa.waslah.agent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import sa.waslah.agent.agent.llm.Llm;

/**
 * The actions the agent can take. The production implementation reads and writes the office's
 * data (DbAgentTools); the eval harness uses an in-memory fixture with the same rules.
 */
public interface AgentTools {

    String SEARCH = "search_listings";
    String SAVE_LEAD = "save_lead";
    String GET_SLOTS = "get_viewing_slots";
    String BOOK = "book_viewing";
    String CANCEL = "cancel_viewing";
    String HANDOFF = "handoff_to_marketer";
    String SEND_CARD = "send_listing_card";

    /** Executes one tool call and returns a JSON object for the model. Never throws for bad input. */
    JsonNode call(String name, JsonNode args);

    /** Ad license numbers returned by search in this run; the reply guard allows only these. */
    List<String> licensesSeen();

    /** True once this run handed the conversation to a human. */
    boolean handedOff();

    static List<Llm.Tool> specs(ObjectMapper json) {
        return List.of(
                tool(json, SEARCH,
                        "Search this office's available listings. Returns at most 3 with their ad license number and expiry. "
                                + "If nothing matches exactly it returns nearby districts or slightly-over-budget listings instead; "
                                + "the 'match' field says which (EXACT, NEARBY, ABOVE_BUDGET, NONE) and 'note' says how to present them. "
                                + "Only listings returned here may be mentioned to the customer.",
                        """
                        {"type":"object","properties":{
                          "purpose":{"type":"string","enum":["SALE","RENT"],"description":"SALE for buying, RENT for renting. Omit unless the customer said which; never assume."},
                          "property_type":{"type":"string","description":"e.g. فيلا، شقة، أرض، دور، عمارة، محل"},
                          "districts":{"type":"string","description":"One or more Riyadh districts in Arabic, comma separated, e.g. النرجس، الملقا. Omit when the customer named only a part of the city."},
                          "area":{"type":"string","enum":["NORTH","SOUTH","EAST","WEST","CENTER"],"description":"Part of Riyadh when the customer says e.g. شمال الرياض / الشمال / شرق الرياض. Searches all its districts."},
                          "max_price":{"type":"number","description":"Maximum price in SAR as a full number (1500000, not 1.5). For RENT it is the annual rent: multiply a monthly amount by 12."},
                          "min_price":{"type":"number","description":"Minimum price in SAR, only when the customer gave a range (بين 40 و 60 ألف)."},
                          "min_rooms":{"type":"integer","description":"Minimum bedrooms"},
                          "min_area_sqm":{"type":"number","description":"Minimum area in square metres"}}}"""),
                tool(json, SAVE_LEAD,
                        "Save what you learned about the customer's need. Call it whenever you learn something new; send only known fields.",
                        """
                        {"type":"object","properties":{
                          "customer_name":{"type":"string"},
                          "purpose":{"type":"string","enum":["SALE","RENT"]},
                          "property_type":{"type":"string"},
                          "districts":{"type":"string","description":"One or more districts, comma separated"},
                          "budget_min":{"type":"number"},
                          "budget_max":{"type":"number"},
                          "payment":{"type":"string","enum":["CASH","FINANCE","INSTALLMENTS","UNKNOWN"]},
                          "timeline":{"type":"string","description":"When they need it, in the customer's words"},
                          "notes":{"type":"string"}}}"""),
                tool(json, GET_SLOTS,
                        "Free viewing times for a listing returned by search. WITH_MARKETER = a marketer shows it; "
                                + "SELF_VISIT = the customer visits alone (vacant listings, office rules apply). "
                                + "Returns up to 6 slots with an exact 'start' to pass to book_viewing.",
                        """
                        {"type":"object","properties":{
                          "listing_id":{"type":"string"},
                          "mode":{"type":"string","enum":["WITH_MARKETER","SELF_VISIT"]},
                          "date":{"type":"string","description":"Optional day the customer asked for, YYYY-MM-DD (Riyadh)"}},
                         "required":["listing_id","mode"]}"""),
                tool(json, BOOK,
                        "Book a viewing at one of the slots returned by get_viewing_slots, after the customer chose it. "
                                + "The customer receives the office's confirmation message automatically.",
                        """
                        {"type":"object","properties":{
                          "listing_id":{"type":"string"},
                          "start":{"type":"string","description":"The exact 'start' value of the chosen slot"},
                          "mode":{"type":"string","enum":["WITH_MARKETER","SELF_VISIT"]},
                          "customer_name":{"type":"string"}},
                         "required":["listing_id","start","mode"]}"""),
                tool(json, CANCEL,
                        "Cancel the customer's upcoming viewing when they ask to cancel or change it (then offer new slots).",
                        """
                        {"type":"object","properties":{
                          "booking_id":{"type":"string","description":"Optional; the customer's only upcoming booking is used if omitted"}}}"""),
                tool(json, SEND_CARD,
                        "Send the customer the listing's map pin and up to 3 photos on WhatsApp, and get its video/tour link. "
                                + "Use when the customer shows interest in a specific listing, or asks for photos or the location.",
                        """
                        {"type":"object","properties":{
                          "listing_id":{"type":"string","description":"An id returned by search_listings"}},
                         "required":["listing_id"]}"""),
                tool(json, HANDOFF,
                        "Hand the conversation to a human marketer. Use for price negotiation, discounts, payment plans, contracts, "
                                + "legal or financing questions, complaints, anything you cannot answer from the tools, "
                                + "or when the customer asks for a person.",
                        """
                        {"type":"object","properties":{
                          "reason":{"type":"string","enum":["NEGOTIATION","CUSTOMER_ASKED","OUT_OF_SCOPE","COMPLAINT","SERIOUS_BUYER","OTHER"]},
                          "summary":{"type":"string","description":"Three short lines in Arabic for the marketer: need, budget and payment, next step"}},
                         "required":["reason","summary"]}"""));
    }

    private static Llm.Tool tool(ObjectMapper json, String name, String description, String schema) {
        try {
            return new Llm.Tool(name, description, (ObjectNode) json.readTree(schema));
        } catch (Exception e) {
            throw new IllegalStateException("Bad tool schema for " + name, e);
        }
    }
}
