package sa.waslah.agent.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import sa.waslah.agent.listing.ListingStore.Query;
import sa.waslah.agent.listing.RiyadhAreas;
import sa.waslah.agent.listing.RiyadhAreas.Area;

class SearchPlanTest {

    static final ObjectMapper JSON = new ObjectMapper();

    record L(String district, String type, String purpose, long price, int rooms, int area) {
    }

    static final List<L> OFFICE = List.of(
            new L("الملقا", "شقة", "RENT", 65_000, 3, 160),
            new L("القيروان", "شقة", "RENT", 48_000, 2, 120),
            new L("الروضة", "شقة", "RENT", 42_000, 3, 140),
            new L("النرجس", "فيلا", "SALE", 1_780_000, 4, 312),
            new L("النرجس", "فيلا", "SALE", 1_950_000, 5, 375),
            new L("حي العارض", "أرض", "SALE", 1_350_000, 0, 625));

    /** Same rules as ListingStore.search. */
    static final List<Query> seen = new ArrayList<>();

    static List<L> search(Query q) {
        seen.add(q);
        return OFFICE.stream()
                .filter(l -> q.purpose() == null || l.purpose().equals(q.purpose()))
                .filter(l -> q.propertyType() == null || l.type().equals(q.propertyType()))
                .filter(l -> q.districts().isEmpty() || q.districts().stream().anyMatch(d -> n(l.district()).contains(n(d))))
                .filter(l -> q.excludeDistricts().stream().noneMatch(d -> n(l.district()).contains(n(d))))
                .filter(l -> q.maxPrice() == null || BigDecimal.valueOf(l.price()).compareTo(q.maxPrice()) <= 0)
                .filter(l -> q.minPrice() == null || BigDecimal.valueOf(l.price()).compareTo(q.minPrice()) >= 0)
                .filter(l -> q.minRooms() == null || l.rooms() >= q.minRooms())
                .filter(l -> q.minAreaSqm() == null || BigDecimal.valueOf(l.area()).compareTo(q.minAreaSqm()) >= 0)
                .sorted((a, b) -> Long.compare(a.price(), b.price()))
                .limit(q.limit()).toList();
    }

    static String n(String s) {
        return RiyadhAreas.normalize(s);
    }

    static SearchPlan.Result<L> run(String args) throws Exception {
        seen.clear();
        return SearchPlan.run(SearchPlan.Request.from(JSON.readTree(args)), SearchPlanTest::search);
    }

    @Nested
    class ReadingArguments {
        @Test
        void listsInOneStringAndTheOldFieldName() throws Exception {
            var r = SearchPlan.Request.from(JSON.readTree("{\"districts\":\"النرجس أو الملقا، حطين\",\"district\":\"العارض\"}"));
            assertThat(r.districts()).containsExactly("النرجس", "الملقا", "حطين", "العارض");
            assertThat(r.area()).isNull();
        }

        @Test
        void anAreaPassedAsADistrictIsAnArea() throws Exception {
            var r = SearchPlan.Request.from(JSON.readTree("{\"district\":\"شمال الرياض\"}"));
            assertThat(r.districts()).isEmpty();
            assertThat(r.area()).isEqualTo(Area.NORTH);
        }

        @Test
        void areaNamesInEitherLanguage() {
            assertThat(RiyadhAreas.parse("NORTH")).contains(Area.NORTH);
            assertThat(RiyadhAreas.parse("الشمال")).contains(Area.NORTH);
            assertThat(RiyadhAreas.parse("شرق الرياض")).contains(Area.EAST);
            assertThat(RiyadhAreas.parse("وسط الرياض")).contains(Area.CENTER);
            assertThat(RiyadhAreas.parse("النسيم الشرقي")).isEmpty();
        }

        @Test
        void oneLetterTyposInDistrictNamesAreCorrected() throws Exception {
            var r = SearchPlan.Request.from(JSON.readTree("{\"districts\":\"الععارض، النرجس، الدرعية\"}"));
            assertThat(r.districts()).containsExactly("العارض", "النرجس", "الدرعية");
            assertThat(RiyadhAreas.correct("الصناعة")).isEqualTo("الصناعة");
        }

        @Test
        void zeroAndJunkNumbersAreIgnored() throws Exception {
            var r = SearchPlan.Request.from(JSON.readTree("{\"max_price\":0,\"min_price\":\"abc\",\"min_rooms\":0}"));
            assertThat(r.maxPrice()).isNull();
            assertThat(r.minPrice()).isNull();
            assertThat(r.minRooms()).isNull();
        }
    }

    @Nested
    class Areas {
        @Test
        void districtsMapToOneArea() {
            assertThat(RiyadhAreas.areaOf("حي المروج")).contains(Area.NORTH);
            assertThat(RiyadhAreas.areaOf("مروج")).contains(Area.NORTH);
            assertThat(RiyadhAreas.areaOf("الروضة")).contains(Area.EAST);
            assertThat(RiyadhAreas.areaOf("النسيم")).contains(Area.EAST);
            assertThat(RiyadhAreas.areaOf("الدرعية")).isEmpty();
        }

        @Test
        void noDistrictIsInTwoAreas() {
            List<String> all = RiyadhAreas.allDistricts().stream().map(RiyadhAreas::normalize).toList();
            assertThat(all).doesNotHaveDuplicates();
        }
    }

    @Test
    void exactMatch() throws Exception {
        var r = run("{\"districts\":\"الملقا\",\"property_type\":\"شقة\"}");
        assertThat(r.match()).isEqualTo(SearchPlan.Match.EXACT);
        assertThat(r.listings()).extracting(L::district).containsExactly("الملقا");
        assertThat(seen).hasSize(1);
    }

    @Test
    void severalDistricts() throws Exception {
        var r = run("{\"districts\":\"الملقا، القيروان\",\"property_type\":\"شقة\"}");
        assertThat(r.listings()).extracting(L::district).containsExactly("القيروان", "الملقا");
    }

    @Test
    void wholeAreaStaysInsideIt() throws Exception {
        var north = run("{\"area\":\"NORTH\",\"property_type\":\"شقة\",\"purpose\":\"RENT\"}");
        assertThat(north.match()).isEqualTo(SearchPlan.Match.EXACT);
        assertThat(north.listings()).extracting(L::district).containsExactlyInAnyOrder("الملقا", "القيروان");
        assertThat(north.note()).contains("شمال الرياض");

        var east = run("{\"area\":\"EAST\",\"property_type\":\"شقة\"}");
        assertThat(east.listings()).extracting(L::district).containsExactly("الروضة");
    }

    @Test
    void nothingInTheDistrictOffersNearbyOnesInTheSameArea() throws Exception {
        var r = run("{\"districts\":\"المروج\",\"property_type\":\"شقة\",\"purpose\":\"RENT\"}");
        assertThat(r.match()).isEqualTo(SearchPlan.Match.NEARBY);
        assertThat(r.listings()).extracting(L::district).containsExactly("القيروان", "الملقا");
        assertThat(r.note()).contains("المروج").contains("شمال الرياض");
        // Never offers the east-Riyadh flat as "nearby" to a north district.
        assertThat(r.listings()).extracting(L::district).doesNotContain("الروضة");
        assertThat(seen.get(1).excludeDistricts()).containsExactly("المروج");
    }

    @Test
    void nearbyKeepsTheBudget() throws Exception {
        var r = run("{\"districts\":\"المروج\",\"property_type\":\"شقة\",\"max_price\":50000}");
        assertThat(r.match()).isEqualTo(SearchPlan.Match.NEARBY);
        assertThat(r.listings()).extracting(L::district).containsExactly("القيروان");
        assertThat(r.note()).contains("ضمن ميزانيته");
    }

    @Test
    void slightlyOverBudgetIsOfferedAndLabelled() throws Exception {
        var r = run("{\"districts\":\"النرجس\",\"property_type\":\"فيلا\",\"max_price\":1700000}");
        assertThat(r.match()).isEqualTo(SearchPlan.Match.ABOVE_BUDGET);
        assertThat(r.listings()).extracting(L::price).containsExactly(1_780_000L, 1_950_000L);
        assertThat(r.note()).contains("1700000").contains("فوق ميزانيته");
    }

    @Test
    void farOverBudgetIsNothing() throws Exception {
        var r = run("{\"districts\":\"النرجس\",\"property_type\":\"فيلا\",\"max_price\":500000}");
        assertThat(r.match()).isEqualTo(SearchPlan.Match.NONE);
        assertThat(r.listings()).isEmpty();
    }

    @Test
    void unknownDistrictHasNoNearby() throws Exception {
        var r = run("{\"districts\":\"الدرعية\",\"property_type\":\"فيلا\"}");
        assertThat(r.match()).isEqualTo(SearchPlan.Match.NONE);
        assertThat(seen).hasSize(1);
    }

    @Test
    void roomsAndSize() throws Exception {
        assertThat(run("{\"districts\":\"النرجس\",\"min_rooms\":5}").listings()).extracting(L::price).containsExactly(1_950_000L);
        assertThat(run("{\"min_area_sqm\":600}").listings()).extracting(L::district).containsExactly("حي العارض");
    }

    @Test
    void priceRange() throws Exception {
        var r = run("{\"property_type\":\"شقة\",\"min_price\":40000,\"max_price\":50000}");
        assertThat(r.listings()).extracting(L::price).containsExactly(42_000L, 48_000L);
    }
}
