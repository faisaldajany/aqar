package sa.waslah.agent.listing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import sa.waslah.agent.listing.importing.ListingRowParser;
import sa.waslah.agent.listing.importing.ListingRowParser.Field;
import sa.waslah.agent.listing.importing.SheetReader;

class ImportParsingTest {

    static final LocalDate TODAY = LocalDate.of(2026, 10, 25);

    @Test
    void mapsArabicAndEnglishHeaders() {
        Map<Field, Integer> m = ListingRowParser.autoMap(List.of(
                "اسم الحي", "نوع العقار", "الغرض", "السعر (ريال)", "المساحة م2", "رقم ترخيص الإعلان",
                "تاريخ انتهاء الترخيص", "رابط الموقع", "Video"));
        assertThat(m.get(Field.DISTRICT)).isEqualTo(0);
        assertThat(m.get(Field.TYPE)).isEqualTo(1);
        assertThat(m.get(Field.PURPOSE)).isEqualTo(2);
        assertThat(m.get(Field.PRICE)).isEqualTo(3);
        assertThat(m.get(Field.AREA)).isEqualTo(4);
        assertThat(m.get(Field.LICENSE)).isEqualTo(5);
        assertThat(m.get(Field.EXPIRY)).isEqualTo(6);
        assertThat(m.get(Field.LOCATION)).isEqualTo(7);
        assertThat(m.get(Field.VIDEO)).isEqualTo(8);
        assertThat(m.get(Field.TITLE)).isNull();
    }

    @Test
    void parsesWhatOfficesActuallyType() {
        Map<Field, Integer> m = ListingRowParser.autoMap(List.of("الحي", "النوع", "الغرض", "السعر", "رقم الترخيص",
                "تاريخ الانتهاء", "الموقع", "الغرف"));
        ListingRowParser.Parsed p = ListingRowParser.parse(List.of(
                "حي النرجس", "فلة", "للبيع", "١٫٩٥ مليون", "٧٢٠٠٤١٨٨٣٦", "1448/06/15",
                "https://www.google.com/maps/place/x/@24.8521,46.6487,17z", "٥"), m, TODAY);
        assertThat(p.errors()).isEmpty();
        assertThat(p.listing().propertyType()).isEqualTo("فيلا");
        assertThat(p.listing().purpose()).isEqualTo("SALE");
        assertThat(p.listing().price()).isEqualByComparingTo(new BigDecimal("1950000"));
        assertThat(p.listing().adLicense()).isEqualTo("7200418836");
        assertThat(p.listing().licenseExpiry()).isAfter(TODAY);
        assertThat(p.listing().licenseExpiry().getYear()).isEqualTo(2026);
        assertThat(p.listing().latitude()).isEqualTo(24.8521);
        assertThat(p.listing().rooms()).isEqualTo(5);
        assertThat(p.listing().title()).isEqualTo("فيلا في حي النرجس");
    }

    @Test
    void rentInThousandsAndGregorianDayFirst() {
        Map<Field, Integer> m = ListingRowParser.autoMap(List.of("district", "type", "purpose", "price", "license", "expiry"));
        ListingRowParser.Parsed p = ListingRowParser.parse(List.of("الملقا", "شقه", "إيجار", "65 ألف", "7200402271", "30/04/2027"), m, TODAY);
        assertThat(p.errors()).isEmpty();
        assertThat(p.listing().purpose()).isEqualTo("RENT");
        assertThat(p.listing().price()).isEqualByComparingTo("65000");
        assertThat(p.listing().licenseExpiry()).isEqualTo(LocalDate.of(2027, 4, 30));
    }

    @Test
    void reportsWhatIsWrong() {
        Map<Field, Integer> m = ListingRowParser.autoMap(List.of("الحي", "النوع", "الغرض", "السعر", "رقم الترخيص", "تاريخ الانتهاء"));
        ListingRowParser.Parsed bad = ListingRowParser.parse(List.of("", "شقة", "؟", "abc", "12345", "x"), m, TODAY);
        assertThat(bad.importable()).isFalse();
        assertThat(bad.errors()).hasSize(5);

        ListingRowParser.Parsed expired = ListingRowParser.parse(List.of("الياسمين", "دور", "ايجار", "85000", "7200371904", "2026-01-20"), m, TODAY);
        assertThat(expired.importable()).isTrue();
        assertThat(expired.warnings()).anyMatch(w -> w.contains("منتهٍ"));
    }

    @Test
    void readsCsvWithBomSemicolonsAndQuotes() {
        String csv = "﻿الحي;السعر;الوصف\n\"النرجس\";\"1,950,000\";\"فيلا \"\"مودرن\"\"; زاوية\"\n\nالملقا;65000;شقة\n";
        SheetReader.Table t = SheetReader.read("listings.csv", csv.getBytes(StandardCharsets.UTF_8));
        assertThat(t.headers()).containsExactly("الحي", "السعر", "الوصف");
        assertThat(t.rows()).hasSize(2);
        assertThat(t.rows().get(0).get(2)).isEqualTo("فيلا \"مودرن\"; زاوية");
    }
}
