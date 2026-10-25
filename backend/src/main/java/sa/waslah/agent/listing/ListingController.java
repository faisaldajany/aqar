package sa.waslah.agent.listing;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import sa.waslah.agent.tenant.TenantContext;

/** Minimal listing and marketer APIs. Bulk Excel/Aqar import arrives in Phase 3. */
@RestController
public class ListingController {

    public record NewListing(
            @NotBlank String title,
            @NotBlank String district,
            @NotBlank String propertyType,
            @NotBlank @Pattern(regexp = "SALE|RENT") String purpose,
            @NotNull @Positive BigDecimal price,
            BigDecimal areaSqm,
            Integer rooms,
            @NotBlank @Pattern(regexp = "^7\\d{9}$", message = "رقم ترخيص الإعلان 10 أرقام يبدأ بـ7") String adLicense,
            @NotNull LocalDate licenseExpiry,
            Boolean vacant,
            UUID marketerId,
            String aqarUrl) {
    }

    public record NewMarketer(@NotBlank String name, @NotBlank @Pattern(regexp = "^9665\\d{8}$") String waPhone,
                              List<String> districts) {
    }

    private final ListingStore listings;
    private final MarketerStore marketers;

    public ListingController(ListingStore listings, MarketerStore marketers) {
        this.listings = listings;
        this.marketers = marketers;
    }

    @GetMapping("/api/listings")
    List<ListingStore.Listing> list() {
        return listings.all();
    }

    @PostMapping("/api/listings")
    Map<String, Object> create(@Valid @RequestBody NewListing l) {
        Map<String, Object> f = new HashMap<>();
        f.put("title", l.title());
        f.put("district", l.district());
        f.put("propertyType", l.propertyType());
        f.put("purpose", l.purpose());
        f.put("price", l.price());
        f.put("areaSqm", l.areaSqm());
        f.put("rooms", l.rooms());
        f.put("adLicense", l.adLicense());
        f.put("licenseExpiry", l.licenseExpiry());
        f.put("vacant", l.vacant() == null ? Boolean.TRUE : l.vacant());
        f.put("marketerId", l.marketerId());
        f.put("aqarUrl", l.aqarUrl());
        return Map.of("id", listings.insert(TenantContext.require(), f));
    }

    @GetMapping("/api/marketers")
    List<MarketerStore.Marketer> marketers() {
        return marketers.active();
    }

    @PostMapping("/api/marketers")
    Map<String, Object> createMarketer(@Valid @RequestBody NewMarketer m) {
        return Map.of("id", marketers.insert(TenantContext.require(), m.name().trim(), m.waPhone(),
                m.districts() == null ? List.of() : m.districts()));
    }
}
