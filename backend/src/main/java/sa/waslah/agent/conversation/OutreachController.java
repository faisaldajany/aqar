package sa.waslah.agent.conversation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import sa.waslah.agent.common.ApiException;
import sa.waslah.agent.listing.ListingStore;

/** Customers waiting for a listing, and the automatic check-in with quiet ones (see {@link CustomerOutreach}). */
@RestController
public class OutreachController {

    public record Send(List<UUID> conversationIds) {
    }

    public record FollowUps(Integer days) {
    }

    private final CustomerOutreach outreach;
    private final ListingStore listings;

    public OutreachController(CustomerOutreach outreach, ListingStore listings) {
        this.outreach = outreach;
        this.listings = listings;
    }

    /** For the listings page: listing id → customers it fits who have not been told yet. */
    @GetMapping("/api/listings/matches")
    Map<UUID, Integer> counts() {
        return outreach.counts();
    }

    @GetMapping("/api/listings/{id}/matches")
    Map<String, Object> matches(@PathVariable UUID id) {
        ListingStore.Listing l = listings.find(id).orElseThrow(() -> ApiException.notFound("no_listing", "العقار غير موجود."));
        Map<String, Object> listing = new LinkedHashMap<>();
        listing.put("id", l.id());
        listing.put("title", l.title());
        listing.put("district", l.district());
        listing.put("propertyType", l.propertyType());
        listing.put("purpose", l.purpose());
        listing.put("price", l.price());
        listing.put("status", l.status());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("listing", listing);
        out.put("matches", outreach.matches(id));
        return out;
    }

    @PostMapping("/api/listings/{id}/matches/send")
    Map<String, Object> send(@PathVariable UUID id, @RequestBody Send s) {
        List<CustomerOutreach.Result> results = outreach.send(id, s == null ? null : s.conversationIds());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sent", results.stream().filter(CustomerOutreach.Result::sent).count());
        out.put("results", results);
        out.put("matches", outreach.matches(id));
        return out;
    }

    @GetMapping("/api/customers/followups")
    Map<String, Object> followUps() {
        return outreach.followUpSettings();
    }

    @PutMapping("/api/customers/followups")
    Map<String, Object> setFollowUps(@RequestBody FollowUps f) {
        return outreach.setFollowUpDays(f == null ? null : f.days());
    }
}
