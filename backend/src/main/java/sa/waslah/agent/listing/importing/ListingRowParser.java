package sa.waslah.agent.listing.importing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.chrono.HijrahDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import sa.waslah.agent.listing.ListingInput;
import sa.waslah.agent.listing.PropertyTypes;

/**
 * Maps spreadsheet columns to listing fields and turns one row into a listing, with the reasons
 * it cannot be imported. Handles what Saudi offices actually type: Arabic-Indic digits, "65 ألف",
 * "1.95 مليون", Hijri expiry dates, and Google Maps links for the location.
 */
public final class ListingRowParser {

    public enum Field {
        TITLE(false, "اسم العقار", "الوصف المختصر", "العنوان", "اسم", "title", "name"),
        DISTRICT(true, "الحي", "حي", "district", "neighborhood", "area name"),
        TYPE(true, "نوع العقار", "النوع", "نوع", "type", "category", "property type"),
        PURPOSE(true, "الغرض", "نوع العرض", "بيع/إيجار", "purpose", "offer", "deal"),
        PRICE(true, "السعر", "سعر", "القيمة", "price", "amount"),
        AREA(false, "المساحة", "مساحة", "area", "size", "sqm", "م2", "م²"),
        ROOMS(false, "الغرف", "غرف", "عدد الغرف", "rooms", "bedrooms"),
        LICENSE(true, "رقم ترخيص الإعلان", "ترخيص الإعلان", "رقم الترخيص", "الترخيص", "ad license", "license"),
        EXPIRY(true, "تاريخ انتهاء الترخيص", "انتهاء الترخيص", "تاريخ الانتهاء", "الانتهاء", "expiry", "expires", "license expiry"),
        VACANT(false, "شاغر", "الحالة", "مسكون", "vacant", "occupancy"),
        LOCATION(false, "الموقع", "رابط الموقع", "قوقل ماب", "خرائط", "location", "map", "google maps"),
        LATITUDE(false, "خط العرض", "latitude", "lat"),
        LONGITUDE(false, "خط الطول", "longitude", "lng", "lon"),
        VIDEO(false, "فيديو", "جولة", "رابط الفيديو", "video", "tour"),
        PHOTOS(false, "الصور", "روابط الصور", "صور", "photos", "images"),
        DESCRIPTION(false, "الوصف", "تفاصيل", "ملاحظات", "description", "details", "notes");

        public final boolean required;
        final String[] synonyms;

        Field(boolean required, String... synonyms) {
            this.required = required;
            this.synonyms = synonyms;
        }
    }

    public record Parsed(ListingInput listing, List<String> errors, List<String> warnings) {
        public boolean importable() {
            return errors.isEmpty();
        }
    }

    private ListingRowParser() {
    }

    /** Best guess of which column holds each field. Exact names win over partial matches. */
    public static Map<Field, Integer> autoMap(List<String> headers) {
        Map<Field, Integer> map = new EnumMap<>(Field.class);
        List<Integer> taken = new ArrayList<>();
        // 1. Exact header names.
        for (Field f : Field.values()) {
            for (int i = 0; i < headers.size() && !map.containsKey(f); i++) {
                if (taken.contains(i)) continue;
                String h = normHeader(headers.get(i));
                for (String s : f.synonyms) {
                    if (!h.isEmpty() && h.equals(normHeader(s))) {
                        map.put(f, i);
                        taken.add(i);
                        break;
                    }
                }
            }
        }
        // 2. Headers containing a synonym; the longest matching synonym wins, so "اسم الحي" is the district.
        record Candidate(Field field, int column, int length) {
        }
        List<Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < headers.size(); i++) {
            if (taken.contains(i)) continue;
            String h = normHeader(headers.get(i));
            boolean expiryHeader = h.contains("انتهاء") || h.contains("expir");
            for (Field f : Field.values()) {
                if (map.containsKey(f) || (expiryHeader && f != Field.EXPIRY)) continue;
                for (String s : f.synonyms) {
                    String n = normHeader(s);
                    if (n.length() >= 3 && h.contains(n)) candidates.add(new Candidate(f, i, n.length()));
                }
                if (expiryHeader && f == Field.EXPIRY) candidates.add(new Candidate(f, i, 100));
            }
        }
        candidates.sort((a, b) -> Integer.compare(b.length(), a.length()));
        for (Candidate c : candidates) {
            if (!map.containsKey(c.field()) && !taken.contains(c.column())) {
                map.put(c.field(), c.column());
                taken.add(c.column());
            }
        }
        return map;
    }

    public static Parsed parse(List<String> row, Map<Field, Integer> map, LocalDate today) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        String district = cell(row, map, Field.DISTRICT);
        String type = PropertyTypes.canonical(cell(row, map, Field.TYPE));
        String purpose = purpose(cell(row, map, Field.PURPOSE));
        BigDecimal price = money(cell(row, map, Field.PRICE));
        String license = digits(cell(row, map, Field.LICENSE));
        LocalDate expiry = date(cell(row, map, Field.EXPIRY));

        if (district.isEmpty()) errors.add("ينقص الحي");
        if (type.isEmpty()) errors.add("ينقص نوع العقار");
        if (purpose == null) errors.add("الغرض غير واضح (بيع أو إيجار)");
        if (price == null) errors.add("السعر غير صالح");
        if (license.isEmpty()) errors.add("ينقص رقم ترخيص الإعلان");
        else if (!license.matches("7\\d{9}")) errors.add("رقم ترخيص الإعلان يجب أن يكون 10 أرقام يبدأ بـ7");
        if (expiry == null) errors.add("تاريخ انتهاء الترخيص غير صالح");
        else if (expiry.isBefore(today)) warnings.add("الترخيص منتهٍ: يُحفظ ولا يعرضه الوكيل حتى التجديد");
        else if (!expiry.isAfter(today.plusDays(7))) warnings.add("الترخيص ينتهي خلال 7 أيام");

        BigDecimal area = money(cell(row, map, Field.AREA));
        Integer rooms = integer(cell(row, map, Field.ROOMS));
        Double[] loc = location(cell(row, map, Field.LOCATION));
        Double lat = loc[0] != null ? loc[0] : decimal(cell(row, map, Field.LATITUDE));
        Double lng = loc[1] != null ? loc[1] : decimal(cell(row, map, Field.LONGITUDE));
        if (lat != null && lng != null && !(lat > 15 && lat < 33 && lng > 34 && lng < 56)) {
            warnings.add("الموقع خارج السعودية: تُرك فارغاً");
            lat = null;
            lng = null;
        }
        String title = cell(row, map, Field.TITLE);
        if (title.isEmpty() && !type.isEmpty()) title = type + (district.isEmpty() ? "" : " في " + district);

        ListingInput in = new ListingInput(title, district, type, purpose, price, area, rooms, license, expiry,
                vacant(cell(row, map, Field.VACANT)), null, lat, lng, url(cell(row, map, Field.VIDEO)),
                urls(cell(row, map, Field.PHOTOS)), emptyToNull(cell(row, map, Field.DESCRIPTION)), "FILE", null);
        return new Parsed(in, errors, warnings);
    }

    // ---- value parsing -----------------------------------------------------------------------

    static String cell(List<String> row, Map<Field, Integer> map, Field f) {
        Integer i = map.get(f);
        return i == null || i < 0 || i >= row.size() || row.get(i) == null ? "" : row.get(i).trim();
    }

    public static String westernDigits(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            if (c >= '٠' && c <= '٩') b.append((char) ('0' + c - '٠'));
            else if (c >= '۰' && c <= '۹') b.append((char) ('0' + c - '۰'));
            else if (c == '٫') b.append('.');
            else if (c == '٬' || c == '،') b.append(',');
            else b.append(c);
        }
        return b.toString();
    }

    static String digits(String s) {
        return westernDigits(s).replaceAll("\\D", "");
    }

    static String purpose(String s) {
        String t = s.toLowerCase(Locale.ROOT);
        if (t.isBlank()) return null;
        if (t.contains("ايجار") || t.contains("إيجار") || t.contains("أجار") || t.contains("rent") || t.contains("تأجير")) return "RENT";
        if (t.contains("بيع") || t.contains("تمليك") || t.contains("sale") || t.contains("sell")) return "SALE";
        return null;
    }

    private static final Pattern NUMBER = Pattern.compile("(\\d+(?:[.,]\\d+)*)");

    /** "1,950,000", "1.95 مليون", "65 ألف", "٦٥٠٠٠ ريال". */
    static BigDecimal money(String s) {
        String t = westernDigits(s).replace(" ", "");
        if (t.isEmpty()) return null;
        Matcher m = NUMBER.matcher(t);
        if (!m.find()) return null;
        String num = m.group(1);
        boolean million = t.contains("مليون") || t.toLowerCase(Locale.ROOT).contains("m");
        boolean thousand = t.contains("ألف") || t.contains("الف") || t.toLowerCase(Locale.ROOT).endsWith("k");
        try {
            BigDecimal v;
            if ((million || thousand) && num.matches("\\d+[.,]\\d{1,2}")) {
                v = new BigDecimal(num.replace(',', '.'));
            } else {
                v = new BigDecimal(num.replace(",", ""));
            }
            if (million) v = v.multiply(BigDecimal.valueOf(1_000_000));
            else if (thousand) v = v.multiply(BigDecimal.valueOf(1_000));
            return v.signum() > 0 ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static Integer integer(String s) {
        String d = digits(s);
        if (d.isEmpty() || d.length() > 3) return null;
        return Integer.parseInt(d);
    }

    static Double decimal(String s) {
        String t = westernDigits(s).trim();
        try {
            return t.isEmpty() ? null : Double.parseDouble(t);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static final Pattern YMD = Pattern.compile("^(\\d{4})[-/.](\\d{1,2})[-/.](\\d{1,2})");
    private static final Pattern DMY = Pattern.compile("^(\\d{1,2})[-/.](\\d{1,2})[-/.](\\d{4})");

    /** ISO, d/m/yyyy, or Hijri (any year before 1600 is read as Hijri, e.g. 1448/06/15). */
    static LocalDate date(String s) {
        String t = westernDigits(s).replace("هـ", "").replace("ه", "").replace("م", "").trim();
        if (t.isEmpty()) return null;
        int y, mo, d;
        Matcher a = YMD.matcher(t);
        Matcher b = DMY.matcher(t);
        if (a.find()) {
            y = Integer.parseInt(a.group(1));
            mo = Integer.parseInt(a.group(2));
            d = Integer.parseInt(a.group(3));
        } else if (b.find()) {
            d = Integer.parseInt(b.group(1));
            mo = Integer.parseInt(b.group(2));
            y = Integer.parseInt(b.group(3));
        } else if (t.matches("\\d{5}")) {
            return LocalDate.of(1899, 12, 30).plusDays(Long.parseLong(t)); // Excel serial date stored as text
        } else {
            return null;
        }
        try {
            if (y < 1600) {
                return LocalDate.from(HijrahDate.of(y, mo, d));
            }
            return LocalDate.of(y, mo, d);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static final Pattern MAPS_AT = Pattern.compile("@(-?\\d+\\.\\d+),(-?\\d+\\.\\d+)");
    private static final Pattern MAPS_Q = Pattern.compile("[?&](?:q|query|ll)=(-?\\d+\\.\\d+)(?:,|%2C)(-?\\d+\\.\\d+)");
    private static final Pattern PAIR = Pattern.compile("^(-?\\d{1,2}\\.\\d+)\\s*[,،]\\s*(-?\\d{1,3}\\.\\d+)$");

    /** Google Maps link or "24.85, 46.64". */
    static Double[] location(String s) {
        String t = westernDigits(s).trim();
        for (Pattern p : new Pattern[] {MAPS_AT, MAPS_Q, PAIR}) {
            Matcher m = p.matcher(t);
            if (m.find()) {
                return new Double[] {Double.parseDouble(m.group(1)), Double.parseDouble(m.group(2))};
            }
        }
        return new Double[] {null, null};
    }

    static Boolean vacant(String s) {
        String t = s.toLowerCase(Locale.ROOT);
        if (t.isBlank()) return Boolean.TRUE;
        if (t.contains("مسكون") || t.contains("مؤجر") || t.contains("occupied") || t.equals("لا") || t.equals("no")) return Boolean.FALSE;
        return Boolean.TRUE;
    }

    static String url(String s) {
        String t = s.trim();
        return t.startsWith("https://") || t.startsWith("http://") ? t : null;
    }

    static List<String> urls(String s) {
        List<String> out = new ArrayList<>();
        for (String part : s.split("[\\s,|]+")) {
            String u = url(part);
            if (u != null && out.size() < 10) out.add(u);
        }
        return out;
    }

    private static String emptyToNull(String s) {
        return s.isBlank() ? null : s;
    }

    private static String normHeader(String s) {
        return westernDigits(s == null ? "" : s).toLowerCase(Locale.ROOT)
                .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ة', 'ه').replace('ى', 'ي')
                .replaceAll("[\\s_\\-:*()]+", " ").trim();
    }
}
