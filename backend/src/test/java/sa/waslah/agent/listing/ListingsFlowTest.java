package sa.waslah.agent.listing;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.util.LinkedMultiValueMap;
import sa.waslah.agent.IntegrationTest;
import sa.waslah.agent.agent.llm.Llm;
import sa.waslah.agent.agent.llm.ScriptedLlmClient;
import sa.waslah.agent.common.ApiException;
import sa.waslah.agent.listing.importing.AqarLinkImporter;
import sa.waslah.agent.listing.importing.ImportService;
import sa.waslah.agent.listing.importing.PageFetcher;
import sa.waslah.agent.tenant.TenantContext;

/** Spreadsheet import, Aqar link drafts, the license guard and plan limits, against a real database. */
class ListingsFlowTest extends IntegrationTest {

    @TestConfiguration
    static class FakePages {
        static volatile String html = "";

        @Bean
        @Primary
        PageFetcher fakePageFetcher() {
            return (URI uri) -> html;
        }
    }

    @Autowired TestRestTemplate http;
    @Autowired ImportService imports;
    @Autowired AqarLinkImporter aqar;
    @Autowired ListingStore listings;
    @Autowired LicenseGuardJob guard;
    @Autowired JdbcClient jdbc;
    @Autowired Llm.Client llm;

    String cookie;
    UUID office;

    @BeforeEach
    void signup() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        ResponseEntity<Map> res = http.postForEntity("/api/auth/signup", Map.of(
                "officeName", "مكتب " + tag, "falLicense", "12345678", "fullName", "مالك",
                "email", tag + "@list.sa", "password", "password123"), Map.class);
        cookie = res.getHeaders().getFirst(HttpHeaders.SET_COOKIE).split(";")[0];
        office = UUID.fromString(String.valueOf(res.getBody().get("officeId")));
        ((ScriptedLlmClient) llm).reset();
    }

    @Test
    void csvUploadPreviewCommitAndReimportUpdates() {
        String csv = """
                الحي,نوع العقار,الغرض,السعر,رقم ترخيص الإعلان,تاريخ انتهاء الترخيص,الموقع
                النرجس,فلة,بيع,"1,950,000",7200418836,2027-06-30,"24.8521, 46.6487"
                الملقا,شقة,إيجار,65 ألف,7200402271,1448/11/01,
                الياسمين,دور,ايجار,85000,123,2027-01-01,
                """;
        Map<?, ?> preview = upload("listings.csv", csv.getBytes(StandardCharsets.UTF_8));
        assertThat(preview.get("total")).isEqualTo(3);
        assertThat(preview.get("ready")).isEqualTo(2);
        assertThat(preview.get("invalid")).isEqualTo(1);

        Map<?, ?> done = post("/api/imports/" + preview.get("batchId") + "/commit", Map.of());
        assertThat(done.get("created")).isEqualTo(2);
        assertThat(done.get("skipped")).isEqualTo(1);

        List<ListingStore.Listing> all = TenantContext.call(office, listings::all);
        assertThat(all).hasSize(2);
        assertThat(all).anyMatch(l -> l.hasLocation() && l.propertyType().equals("فيلا"));

        String again = "الحي,النوع,الغرض,السعر,رقم الترخيص,تاريخ الانتهاء\nالنرجس,فيلا,بيع,1900000,7200418836,2027-06-30\n";
        Map<?, ?> p2 = upload("update.csv", again.getBytes(StandardCharsets.UTF_8));
        Map<?, ?> d2 = post("/api/imports/" + p2.get("batchId") + "/commit", Map.of());
        assertThat(d2.get("updated")).isEqualTo(1);
        assertThat(d2.get("created")).isEqualTo(0);
        BigDecimal price = TenantContext.call(office, () -> jdbc.sql("select price from listings where ad_license = '7200418836'")
                .query(BigDecimal.class).single());
        assertThat(price).isEqualByComparingTo("1900000");
        // The location from the first import is kept when the update has none.
        assertThat(TenantContext.call(office, listings::all)).anyMatch(l -> l.adLicense().equals("7200418836") && l.hasLocation());
    }

    @Test
    void xlsxUploadWithDateCells() throws Exception {
        byte[] xlsx;
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            var sheet = wb.createSheet("عقارات");
            var h = sheet.createRow(0);
            String[] headers = {"الحي", "النوع", "الغرض", "السعر", "رقم ترخيص الإعلان", "تاريخ انتهاء الترخيص"};
            for (int i = 0; i < headers.length; i++) h.createCell(i).setCellValue(headers[i]);
            var r = sheet.createRow(1);
            r.createCell(0).setCellValue("العارض");
            r.createCell(1).setCellValue("أرض");
            r.createCell(2).setCellValue("للبيع");
            r.createCell(3).setCellValue(1350000);
            r.createCell(4).setCellValue("7200388712");
            var dateCell = r.createCell(5);
            dateCell.setCellValue(java.time.LocalDate.of(2027, 3, 22));
            var style = wb.createCellStyle();
            style.setDataFormat(wb.getCreationHelper().createDataFormat().getFormat("yyyy-mm-dd"));
            dateCell.setCellStyle(style);
            wb.write(out);
            xlsx = out.toByteArray();
        }
        Map<?, ?> preview = upload("listings.xlsx", xlsx);
        assertThat(preview.get("ready")).isEqualTo(1);
        post("/api/imports/" + preview.get("batchId") + "/commit", Map.of());
        LocalDate expiry = TenantContext.call(office, () -> listings.all().get(0).licenseExpiry());
        assertThat(expiry).isEqualTo(LocalDate.of(2027, 3, 22));
    }

    @Test
    void aqarDraftKeepsOnlyLicensesPrintedOnThePage() {
        FakePages.html = """
                <html><head><title>فيلا للبيع في حي القيروان</title>
                <meta property="og:image" content="https://images.aqar.fm/1.jpg"></head>
                <body>فيلا درج داخلي، المساحة 360 م2، السعر 2,100,000 ريال. رقم ترخيص الإعلان: 7200436621 صالح حتى 1448/10/30</body></html>""";
        ((ScriptedLlmClient) llm).thenExtract("""
                {"title":"فيلا درج داخلي","district":"حي القيروان","property_type":"فيلا","purpose":"SALE","price":2100000,
                 "area_sqm":360,"rooms":null,"ad_license":"7200436621","license_expiry":"1448/10/30"}""");
        AqarLinkImporter.Draft d = TenantContext.call(office, () -> aqar.draft("https://sa.aqar.fm/ad/123"));
        assertThat(d.listing().adLicense()).isEqualTo("7200436621");
        assertThat(d.listing().district()).isEqualTo("القيروان");
        assertThat(d.listing().photoUrls()).containsExactly("https://images.aqar.fm/1.jpg");
        assertThat(d.missing()).isEmpty();

        ((ScriptedLlmClient) llm).thenExtract("{\"ad_license\":\"7299999999\",\"price\":2100000}");
        AqarLinkImporter.Draft guessed = TenantContext.call(office, () -> aqar.draft("https://sa.aqar.fm/ad/123"));
        assertThat(guessed.listing().adLicense()).as("falls back to the one license on the page").isEqualTo("7200436621");
        assertThat(guessed.warnings()).isNotEmpty();
    }

    @Test
    void aqarRejectsOtherSites() {
        try {
            aqar.draft("https://example.com/ad/1");
            throw new AssertionError("expected rejection");
        } catch (ApiException e) {
            assertThat(e.code()).isEqualTo("not_aqar");
        }
    }

    @Test
    void licenseGuardAlertsOnceForExpiringAndExpired() {
        TenantContext.run(office, () -> {
            listings.upsert(office, input("7200000001", LocalDate.now().plusDays(3)));
            listings.upsert(office, input("7200000002", LocalDate.now().minusDays(1)));
            listings.upsert(office, input("7200000003", LocalDate.now().plusDays(60)));
        });
        assertThat(TenantContext.call(office, () -> guard.runFor(office, LocalDate.now()))).isEqualTo(2);
        assertThat(TenantContext.call(office, () -> guard.runFor(office, LocalDate.now()))).isZero();
        ResponseEntity<List> alerts = http.exchange("/api/alerts", org.springframework.http.HttpMethod.GET, auth(null), List.class);
        assertThat(alerts.getBody()).hasSize(2);
    }

    @Test
    void basicPlanAllowsTwoMarketers() {
        assertThat(postStatus("/api/marketers", Map.of("name", "خالد", "waPhone", "966551840022"))).isEqualTo(HttpStatus.OK);
        assertThat(postStatus("/api/marketers", Map.of("name", "سارة", "waPhone", "966569120477"))).isEqualTo(HttpStatus.OK);
        assertThat(postStatus("/api/marketers", Map.of("name", "فهد", "waPhone", "966548331190"))).isEqualTo(HttpStatus.CONFLICT);
    }

    // ---- helpers ----

    private ListingInput input(String license, LocalDate expiry) {
        return new ListingInput("شقة", "الملقا", "شقة", "RENT", new BigDecimal("60000"), null, 3, license, expiry, true,
                null, null, null, null, null, null, "MANUAL", null);
    }

    private Map<?, ?> upload(String name, byte[] bytes) {
        LinkedMultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return name;
            }
        });
        HttpHeaders h = new HttpHeaders();
        h.add(HttpHeaders.COOKIE, cookie);
        h.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<Map> res = http.postForEntity("/api/imports", new HttpEntity<>(form, h), Map.class);
        assertThat(res.getStatusCode()).as(String.valueOf(res.getBody())).isEqualTo(HttpStatus.OK);
        return res.getBody();
    }

    private Map<?, ?> post(String path, Object body) {
        ResponseEntity<Map> res = http.postForEntity(path, auth(body), Map.class);
        assertThat(res.getStatusCode()).as(String.valueOf(res.getBody())).isEqualTo(HttpStatus.OK);
        return res.getBody();
    }

    private HttpStatus postStatus(String path, Object body) {
        return HttpStatus.valueOf(http.postForEntity(path, auth(body), String.class).getStatusCode().value());
    }

    private HttpEntity<Object> auth(Object body) {
        HttpHeaders h = new HttpHeaders();
        h.add(HttpHeaders.COOKIE, cookie);
        h.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, h);
    }
}
