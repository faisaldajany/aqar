package sa.waslah.agent.listing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Everything needed to create or update one listing, from any source (form, file, Aqar link). */
public record ListingInput(
        String title,
        String district,
        String propertyType,
        String purpose,
        BigDecimal price,
        BigDecimal areaSqm,
        Integer rooms,
        String adLicense,
        LocalDate licenseExpiry,
        Boolean vacant,
        UUID marketerId,
        Double latitude,
        Double longitude,
        String videoUrl,
        List<String> photoUrls,
        String description,
        String source,
        String sourceUrl) {

    public ListingInput {
        photoUrls = photoUrls == null ? List.of() : List.copyOf(photoUrls);
        if (source == null) source = "MANUAL";
    }
}
