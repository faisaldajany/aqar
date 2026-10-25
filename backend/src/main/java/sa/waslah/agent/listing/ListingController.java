package sa.waslah.agent.listing;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import sa.waslah.agent.billing.PlanLimits;
import sa.waslah.agent.common.ApiException;
import sa.waslah.agent.tenant.TenantContext;

/** Listings, their photos, and marketers for the signed-in office. */
@RestController
public class ListingController {

    public record ListingRequest(
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
            @DecimalMin("15") @DecimalMax("33") Double latitude,
            @DecimalMin("34") @DecimalMax("56") Double longitude,
            @Pattern(regexp = "^https?://.*") String videoUrl,
            List<@Pattern(regexp = "^https://.*") String> photoUrls,
            String description,
            @Pattern(regexp = "MANUAL|AQAR_LINK") String source,
            String sourceUrl) {

        ListingInput toInput() {
            return new ListingInput(title.trim(), district.trim(), propertyType, purpose, price, areaSqm, rooms, adLicense,
                    licenseExpiry, vacant, marketerId, latitude, longitude, videoUrl, photoUrls, description,
                    source == null ? "MANUAL" : source, sourceUrl);
        }
    }

    public record StatusRequest(@NotBlank @Pattern(regexp = "ACTIVE|HIDDEN|SOLD") String status) {
    }

    public record NewMarketer(@NotBlank String name, @NotBlank @Pattern(regexp = "^9665\\d{8}$") String waPhone,
                              List<String> districts) {
    }

    static final Set<String> PHOTO_TYPES = Set.of("image/jpeg", "image/png", "image/webp");
    static final int MAX_PHOTOS = 10;
    static final int MAX_PHOTO_BYTES = 5 * 1024 * 1024;

    private final ListingStore listings;
    private final MarketerStore marketers;
    private final PlanLimits plans;

    public ListingController(ListingStore listings, MarketerStore marketers, PlanLimits plans) {
        this.listings = listings;
        this.marketers = marketers;
        this.plans = plans;
    }

    @GetMapping("/api/listings")
    List<ListingStore.Listing> list() {
        return listings.all();
    }

    @GetMapping("/api/listings/{id}")
    ListingStore.Listing get(@PathVariable UUID id) {
        return require(id);
    }

    /** Creates a listing, or updates the existing one with the same ad license. */
    @PostMapping("/api/listings")
    Map<String, Object> save(@Valid @RequestBody ListingRequest r) {
        ListingStore.Saved s = listings.upsert(TenantContext.require(), r.toInput());
        return Map.of("id", s.id(), "created", s.created());
    }

    @PutMapping("/api/listings/{id}")
    Map<String, Object> update(@PathVariable UUID id, @Valid @RequestBody ListingRequest r) {
        ListingStore.Listing existing = require(id);
        if (!existing.adLicense().equals(r.adLicense())) {
            throw ApiException.badRequest("license_changed", "لا يمكن تغيير رقم ترخيص الإعلان لعقار قائم. أضفه كعقار جديد.");
        }
        ListingStore.Saved s = listings.upsert(TenantContext.require(), r.toInput());
        return Map.of("id", s.id(), "created", false);
    }

    @PostMapping("/api/listings/{id}/status")
    Map<String, Object> status(@PathVariable UUID id, @Valid @RequestBody StatusRequest r) {
        require(id);
        listings.setStatus(id, r.status());
        return Map.of("status", r.status());
    }

    @PostMapping("/api/listings/{id}/photos")
    Map<String, Object> addPhoto(@PathVariable UUID id, @RequestParam("file") MultipartFile file) throws IOException {
        require(id);
        String type = file.getContentType() == null ? "" : file.getContentType();
        if (!PHOTO_TYPES.contains(type)) {
            throw ApiException.badRequest("photo_type", "الصور المقبولة: JPG أو PNG أو WEBP.");
        }
        if (file.getSize() > MAX_PHOTO_BYTES) {
            throw ApiException.badRequest("photo_size", "حجم الصورة أكبر من 5 ميجابايت.");
        }
        if (listings.photoCount(id) >= MAX_PHOTOS) {
            throw ApiException.badRequest("photo_limit", "الحد الأقصى " + MAX_PHOTOS + " صور لكل عقار.");
        }
        UUID photoId = listings.addPhoto(TenantContext.require(), id, type, file.getBytes());
        return Map.of("id", photoId);
    }

    @GetMapping("/api/listings/{id}/photos")
    List<Map<String, Object>> photos(@PathVariable UUID id) {
        require(id);
        return listings.photos(id, MAX_PHOTOS).stream()
                .map(p -> Map.<String, Object>of("id", p.id(), "url", "/api/listings/" + id + "/photos/" + p.id()))
                .toList();
    }

    @GetMapping("/api/listings/{id}/photos/{photoId}")
    ResponseEntity<byte[]> photo(@PathVariable UUID id, @PathVariable UUID photoId) {
        ListingStore.Photo p = listings.photo(id, photoId)
                .orElseThrow(() -> ApiException.notFound("no_photo", "الصورة غير موجودة."));
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(p.contentType())).body(p.bytes());
    }

    @DeleteMapping("/api/listings/{id}/photos/{photoId}")
    Map<String, Object> deletePhoto(@PathVariable UUID id, @PathVariable UUID photoId) {
        require(id);
        listings.deletePhoto(id, photoId);
        return Map.of("deleted", true);
    }

    @GetMapping("/api/marketers")
    List<MarketerStore.Marketer> marketers() {
        return marketers.active();
    }

    @PostMapping("/api/marketers")
    Map<String, Object> createMarketer(@Valid @RequestBody NewMarketer m) {
        int limit = plans.current().marketers();
        if (marketers.active().size() >= limit) {
            throw ApiException.conflict("marketer_limit",
                    "باقتك تسمح بـ" + limit + " مسوّقين. رقِّ الباقة أو أضف مسوّقاً إضافياً.");
        }
        return Map.of("id", marketers.insert(TenantContext.require(), m.name().trim(), m.waPhone(),
                m.districts() == null ? List.of() : m.districts()));
    }

    @PostMapping("/api/marketers/{id}/deactivate")
    Map<String, Object> deactivate(@PathVariable UUID id) {
        marketers.deactivate(id);
        return Map.of("active", false);
    }

    private ListingStore.Listing require(UUID id) {
        return listings.find(id).orElseThrow(() -> ApiException.notFound("no_listing", "العقار غير موجود."));
    }
}
