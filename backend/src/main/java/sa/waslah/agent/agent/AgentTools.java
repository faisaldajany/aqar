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
    String REQUEST_VIEWING = "request_viewing";
    String HANDOFF = "handoff_to_marketer";

    /** Executes one tool call and returns a JSON object for the model. Never throws for bad input. */
    JsonNode call(String name, JsonNode args);

    /** Ad license numbers returned by search in this run; the reply guard allows only these. */
    List<String> licensesSeen();

    /** True once this run handed the conversation to a human. */
    boolean handedOff();

    static List<Llm.Tool> specs(ObjectMapper json) {
        return List.of(
                tool(json, SEARCH,
                        "Search this office's available listings. Returns at most 3 matches with their ad license number and expiry. "
                                + "Only listings returned here may be mentioned to the customer.",
                        """
                        {"type":"object","properties":{
                          "purpose":{"type":"string","enum":["SALE","RENT"],"description":"SALE for buying, RENT for renting"},
                          "property_type":{"type":"string","description":"e.g. فيلا، شقة، أرض، دور، عمارة، محل"},
                          "district":{"type":"string","description":"Riyadh district in Arabic, e.g. النرجس"},
                          "max_price":{"type":"number","description":"Maximum price in SAR (annual rent for RENT)"},
                          "min_price":{"type":"number"},
                          "min_rooms":{"type":"integer"}}}"""),
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
                tool(json, REQUEST_VIEWING,
                        "The customer wants to view a specific listing returned by search. Records the request for the office to confirm.",
                        """
                        {"type":"object","properties":{
                          "listing_id":{"type":"string"},
                          "preferred_time":{"type":"string","description":"The time the customer asked for, in their words"},
                          "mode":{"type":"string","enum":["WITH_MARKETER","SELF_VISIT"]}},
                         "required":["listing_id","preferred_time"]}"""),
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
