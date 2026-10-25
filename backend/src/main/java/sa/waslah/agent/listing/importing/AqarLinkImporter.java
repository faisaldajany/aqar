package sa.waslah.agent.listing.importing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Service;
import sa.waslah.agent.agent.llm.Llm;
import sa.waslah.agent.common.ApiException;
import sa.waslah.agent.listing.ListingInput;
import sa.waslah.agent.listing.PropertyTypes;

/**
 * Turns a public Aqar listing URL into a draft listing for the office to confirm.
 * The page text goes to the model for structured extraction; every value is then checked in code.
 * In particular the ad license is kept only if that exact number appears on the page, so a
 * model guess can never become a published license.
 *
 * Aqar has no public API; this reads the public listing page and can break if Aqar changes it.
 */
@Service
public class AqarLinkImporter {

    public record Draft(ListingInput listing, List<String> warnings, List<String> missing) {
    }

    static final Set<String> HOSTS = Set.of("sa.aqar.fm", "aqar.fm", "www.aqar.fm");
    private static final Pattern LICENSE = Pattern.compile("(?<!\\d)7\\d{9}(?!\\d)");
    private static final int MAX_TEXT = 12_000;

    private static final String INSTRUCTION = """
            You extract one Saudi real-estate listing from the text of its web page.
            Return only values written on the page; use null for anything not stated. Do not guess.
            purpose is SALE or RENT. price is a number in SAR (annual rent for RENT).
            ad_license is the advertising license number (رقم ترخيص الإعلان) exactly as written.
            license_expiry is the license end date exactly as written (Hijri or Gregorian).
            district is the Riyadh district name in Arabic without the word حي.""";

    private final PageFetcher fetcher;
    private final Llm.Client llm;
    private final ObjectMapper json;

    public AqarLinkImporter(PageFetcher fetcher, Llm.Client llm, ObjectMapper json) {
        this.fetcher = fetcher;
        this.llm = llm;
        this.json = json;
    }

    public Draft draft(String rawUrl) {
        URI uri = validate(rawUrl);
        String html = fetcher.fetch(uri);
        Document doc = Jsoup.parse(html, uri.toString());

        StringBuilder text = new StringBuilder();
        text.append(doc.title()).append('\n');
        for (Element m : doc.select("meta[property=og:title], meta[property=og:description], meta[name=description]")) {
            text.append(m.attr("content")).append('\n');
        }
        for (Element s : doc.select("script[type=application/ld+json]")) {
            text.append(s.data()).append('\n');
        }
        text.append(doc.body() == null ? "" : doc.body().text());
        String pageText = ListingRowParser.westernDigits(text.toString());
        if (pageText.length() > MAX_TEXT) pageText = pageText.substring(0, MAX_TEXT);

        JsonNode x;
        try {
            x = llm.extractJson(INSTRUCTION, pageText, schema());
        } catch (Llm.LlmException e) {
            throw ApiException.badGateway("extract_failed", "تعذّر قراءة بيانات الإعلان. أدخل العقار يدوياً أو حاول لاحقاً.");
        }

        List<String> warnings = new ArrayList<>();
        Set<String> licensesOnPage = new LinkedHashSet<>();
        Matcher lm = LICENSE.matcher(pageText);
        while (lm.find()) licensesOnPage.add(lm.group());

        String license = ListingRowParser.digits(text(x, "ad_license"));
        if (!license.isEmpty() && !licensesOnPage.contains(license)) {
            warnings.add("رقم الترخيص المستخرج غير موجود في الصفحة، فلم يُعتمد.");
            license = "";
        }
        if (license.isEmpty() && licensesOnPage.size() == 1) {
            license = licensesOnPage.iterator().next();
        }

        LocalDate expiry = ListingRowParser.date(text(x, "license_expiry"));
        if (expiry != null && expiry.isBefore(LocalDate.now(ZoneId.of("Asia/Riyadh")))) {
            warnings.add("ترخيص الإعلان منتهٍ؛ لن يعرضه الوكيل حتى التجديد.");
        }
        String purpose = ListingRowParser.purpose(text(x, "purpose"));
        BigDecimal price = number(x, "price");
        BigDecimal area = number(x, "area_sqm");
        Integer rooms = x.path("rooms").canConvertToInt() ? x.path("rooms").asInt() : null;
        Double lat = x.path("latitude").isNumber() ? x.path("latitude").asDouble() : null;
        Double lng = x.path("longitude").isNumber() ? x.path("longitude").asDouble() : null;
        if (lat != null && lng != null && !(lat > 15 && lat < 33 && lng > 34 && lng < 56)) {
            lat = null;
            lng = null;
        }

        List<String> photos = new ArrayList<>();
        for (Element m : doc.select("meta[property=og:image]")) {
            String u = m.absUrl("content");
            if (u.startsWith("https://") && photos.size() < 10) photos.add(u);
        }

        ListingInput in = new ListingInput(
                emptyToNull(text(x, "title")),
                emptyToNull(text(x, "district").replaceFirst("^حي\\s+", "")),
                emptyToNull(PropertyTypes.canonical(text(x, "property_type"))),
                purpose, price, area, rooms, emptyToNull(license), expiry, Boolean.TRUE, null, lat, lng, null,
                photos, emptyToNull(text(x, "description")), "AQAR_LINK", uri.toString());

        List<String> missing = new ArrayList<>();
        if (in.district() == null) missing.add("الحي");
        if (in.propertyType() == null) missing.add("نوع العقار");
        if (in.purpose() == null) missing.add("الغرض");
        if (in.price() == null) missing.add("السعر");
        if (in.adLicense() == null) missing.add("رقم ترخيص الإعلان");
        if (in.licenseExpiry() == null) missing.add("تاريخ انتهاء الترخيص");
        return new Draft(in, warnings, missing);
    }

    static URI validate(String raw) {
        try {
            URI u = URI.create(raw == null ? "" : raw.trim());
            String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
            if (!"https".equalsIgnoreCase(u.getScheme()) || !HOSTS.contains(host)) {
                throw ApiException.badRequest("not_aqar", "الصق رابط إعلان من موقع عقار يبدأ بـ https://sa.aqar.fm/");
            }
            return u;
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("not_aqar", "الرابط غير صالح.");
        }
    }

    private ObjectNode schema() {
        ObjectNode s = json.createObjectNode().put("type", "object");
        ObjectNode p = s.putObject("properties");
        for (String f : new String[] {"title", "district", "property_type", "purpose", "ad_license", "license_expiry", "description"}) {
            p.putObject(f).put("type", "string").put("nullable", true);
        }
        for (String f : new String[] {"price", "area_sqm", "latitude", "longitude"}) {
            p.putObject(f).put("type", "number").put("nullable", true);
        }
        p.putObject("rooms").put("type", "integer").put("nullable", true);
        return s;
    }

    private static String text(JsonNode x, String f) {
        JsonNode n = x.path(f);
        return n.isNull() || n.isMissingNode() ? "" : n.asText("").trim();
    }

    private static BigDecimal number(JsonNode x, String f) {
        JsonNode n = x.path(f);
        if (n.isNumber() && n.decimalValue().signum() > 0) return n.decimalValue();
        return n.isTextual() ? ListingRowParser.money(n.asText()) : null;
    }

    private static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
