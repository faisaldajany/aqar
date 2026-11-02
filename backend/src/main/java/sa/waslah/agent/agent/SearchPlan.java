package sa.waslah.agent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import sa.waslah.agent.listing.ListingStore.Query;
import sa.waslah.agent.listing.RiyadhAreas;
import sa.waslah.agent.listing.RiyadhAreas.Area;

/**
 * How search_listings answers. It searches exactly what the customer asked for; when that finds nothing it
 * widens in a fixed order, and says which step the results came from so the agent presents them honestly:
 * <ol>
 *   <li>EXACT: the districts and/or area asked for, all filters.</li>
 *   <li>NEARBY: other districts in the same part of Riyadh, same filters (e.g. no flat in المروج, one in الملقا).</li>
 *   <li>ABOVE_BUDGET: the place asked for, up to 15% over the maximum price.</li>
 *   <li>NONE.</li>
 * </ol>
 * Shared by the production tools and the eval harness so both behave the same.
 */
public final class SearchPlan {

    public enum Match { EXACT, NEARBY, ABOVE_BUDGET, NONE }

    /** How far over the customer's maximum the agent may offer, clearly labelled. */
    public static final BigDecimal STRETCH = new BigDecimal("1.15");
    static final int LIMIT = 3;

    public record Request(String purpose, String propertyType, List<String> districts, Area area,
                          BigDecimal maxPrice, BigDecimal minPrice, Integer minRooms, BigDecimal minAreaSqm) {

        /** Reads the tool arguments. Tolerates "district" (old name), lists in one string, and an area written as a district. */
        public static Request from(JsonNode a) {
            List<String> names = new ArrayList<>();
            names.addAll(split(text(a, "districts")));
            names.addAll(split(text(a, "district")));
            Area area = RiyadhAreas.parse(text(a, "area")).orElse(null);
            List<String> districts = new ArrayList<>();
            for (String n : names) {
                Optional<Area> asArea = RiyadhAreas.parse(n);
                // "شمال الرياض" passed as a district is an area; a real district name is never parsed as one.
                if (asArea.isPresent() && RiyadhAreas.areaOf(n).isEmpty()) {
                    if (area == null) area = asArea.get();
                } else {
                    String fixed = RiyadhAreas.correct(n);
                    if (!districts.contains(fixed)) districts.add(fixed);
                }
            }
            return new Request(text(a, "purpose"), text(a, "property_type"), List.copyOf(districts), area,
                    number(a, "max_price"), number(a, "min_price"),
                    a.hasNonNull("min_rooms") && a.path("min_rooms").asInt() > 0 ? a.path("min_rooms").asInt() : null,
                    number(a, "min_area_sqm"));
        }
    }

    public record Result<T>(Match match, List<T> listings, String note, Area area) {
    }

    private SearchPlan() {
    }

    public static <T> Result<T> run(Request r, Function<Query, List<T>> search) {
        List<String> place = new ArrayList<>(r.districts());
        if (r.area() != null) place.addAll(r.area().districts);

        List<T> hits = search.apply(query(r, place, List.of(), r.maxPrice(), r.minPrice()));
        if (!hits.isEmpty()) {
            String note = r.area() != null && r.districts().isEmpty()
                    ? "نتائج من " + r.area().arabicName + ". اذكر حي كل عقار." : null;
            return new Result<>(Match.EXACT, hits, note, r.area());
        }

        // Nearby: the parts of Riyadh the requested districts are in, minus those districts.
        Set<Area> areas = r.districts().stream().map(RiyadhAreas::areaOf).flatMap(Optional::stream)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (!areas.isEmpty()) {
            List<String> nearby = areas.stream().flatMap(a -> a.districts.stream()).toList();
            hits = search.apply(query(r, nearby, r.districts(), r.maxPrice(), r.minPrice()));
            if (!hits.isEmpty()) {
                String where = areas.stream().map(a -> a.arabicName).collect(Collectors.joining(" و"));
                return new Result<>(Match.NEARBY, hits,
                        "لا يوجد عقار مطابق في " + String.join(" أو ", r.districts())
                                + (r.maxPrice() != null ? " ضمن ميزانيته" : "") + ". هذه بدائل في أحياء قريبة من "
                                + where + ". قل للعميل بوضوح أنه لا يوجد حالياً في الحي الذي طلبه"
                                + (r.maxPrice() != null ? " ضمن ميزانيته (قد يوجد فيه بسعر أعلى)" : "")
                                + "، ثم اعرض البدائل واذكر حي كل عقار، واسأله هل تناسبه.",
                        areas.iterator().next());
            }
        }

        if (r.maxPrice() != null && r.maxPrice().signum() > 0) {
            BigDecimal stretched = r.maxPrice().multiply(STRETCH).setScale(0, RoundingMode.HALF_UP);
            hits = search.apply(query(r, place, List.of(), stretched, r.maxPrice()));
            if (!hits.isEmpty()) {
                return new Result<>(Match.ABOVE_BUDGET, hits,
                        "لا يوجد عقار ضمن الميزانية (" + r.maxPrice().toPlainString() + " ريال). هذه أعلى منها بقليل."
                                + " وضّح للعميل أن سعرها فوق ميزانيته وكم الفرق، واسأله هل يناسبه. لا تعرضها كأنها ضمن ميزانيته.",
                        r.area());
            }
        }

        return new Result<>(Match.NONE, List.of(),
                "لا توجد عقارات مطابقة حالياً. قل ذلك بوضوح، واعرض تسجيل طلبه ليتواصل معه المسوّق عند توفر عقار."
                        + " يمكنك أن تسأله إن كان يقبل حياً آخر أو ميزانية أعلى.", r.area());
    }

    private static Query query(Request r, List<String> districts, List<String> exclude, BigDecimal max, BigDecimal min) {
        return new Query(r.purpose(), r.propertyType(), districts, exclude, max, min, r.minRooms(), r.minAreaSqm(), LIMIT);
    }

    static List<String> split(String s) {
        if (s == null || s.isBlank()) return List.of();
        return Arrays.stream(s.split("\\s*[,،/|]\\s*|\\s+(?:او|أو|و)\\s+"))
                .map(String::trim).filter(x -> !x.isEmpty()).toList();
    }

    private static String text(JsonNode a, String field) {
        JsonNode v = a.path(field);
        if (v.isArray()) {
            List<String> parts = new ArrayList<>();
            v.forEach(x -> parts.add(x.asText()));
            return String.join(",", parts);
        }
        return v.isMissingNode() || v.isNull() || v.asText().isBlank() ? null : v.asText().trim();
    }

    private static BigDecimal number(JsonNode a, String field) {
        JsonNode v = a.path(field);
        if (v.isNumber()) return v.decimalValue().signum() > 0 ? v.decimalValue() : null;
        if (v.isTextual()) {
            try {
                BigDecimal d = new BigDecimal(v.asText().replace(",", "").trim());
                return d.signum() > 0 ? d : null;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
