package sa.waslah.agent.listing;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The five parts of Riyadh customers talk about ("شمال الرياض"), and the districts in each. Used to search a
 * whole area, and to offer nearby districts when the one asked for has nothing. Boundaries are the common
 * usage, not official sub-municipality lines; a district belongs to exactly one area.
 */
public final class RiyadhAreas {

    public enum Area {
        NORTH("شمال الرياض", List.of(
                "النرجس", "الملقا", "الياسمين", "حطين", "القيروان", "العارض", "الصحافة", "الربيع", "الندى", "العقيق",
                "الوادي", "النخيل", "الغدير", "المروج", "النفل", "المصيف", "التعاون", "الازدهار", "المرسلات", "الفلاح",
                "الواحة", "صلاح الدين", "الملك فهد", "المغرزات", "بنبان", "الخير", "الرائد")),
        EAST("شرق الرياض", List.of(
                "الرمال", "قرطبة", "اليرموك", "غرناطة", "الحمراء", "النهضة", "الروضة", "القدس", "الخليج", "الريان",
                "الروابي", "السلي", "المونسية", "النسيم الشرقي", "النسيم الغربي", "الجزيرة", "السعادة", "الفيحاء",
                "المنار", "النظيم", "الندوة", "الربوة", "إشبيلية", "الملك فيصل", "القادسية", "الأندلس", "المشرق",
                "خشم العان", "الجنادرية", "الصفا", "الزهراء", "الشهداء")),
        WEST("غرب الرياض", List.of(
                "طويق", "ظهرة لبن", "لبن", "العريجاء", "العريجاء الغربي", "العريجاء الوسطى", "السويدي",
                "السويدي الغربي", "البديعة", "ظهرة البديعة", "شبرا", "الزهرة", "عرقة", "الخزامى", "السفارات",
                "المهدية", "هجرة وادي لبن", "ظهرة نمار")),
        SOUTH("جنوب الرياض", List.of(
                "الشفا", "العزيزية", "الدار البيضاء", "المنصورية", "الحزم", "نمار", "بدر", "المروة", "الدريهمية",
                "عكاظ", "منفوحة", "منفوحة الجديدة", "الفيصلية", "المصانع", "عتيقة", "اليمامة", "الحائر", "سلطانة",
                "ديراب", "طيبة", "المصفاة", "الجرادية", "الغنامية", "أحد", "الخالدية")),
        CENTER("وسط الرياض", List.of(
                "العليا", "السليمانية", "الملز", "المربع", "الورود", "الملك عبدالعزيز", "الملك عبدالله", "الرحمانية",
                "المعذر", "المحمدية", "الوزارات", "الفوطة", "الديرة", "البطحاء", "الصالحية", "جرير", "الضباط",
                "الوشام", "الشميسي", "أم الحمام الشرقي", "أم الحمام الغربي", "الناصرية", "المعذر الشمالي",
                "الفاخرية", "الملك عبدالعزيز الجديد", "ثليم", "العود", "المرقب", "معكال"));

        public final String arabicName;
        public final List<String> districts;

        Area(String arabicName, List<String> districts) {
            this.arabicName = arabicName;
            this.districts = districts;
        }
    }

    private static final Map<String, Area> BY_DISTRICT = new HashMap<>();

    static {
        for (Area a : Area.values()) {
            for (String d : a.districts) {
                Area prev = BY_DISTRICT.put(normalize(d), a);
                if (prev != null) throw new IllegalStateException(d + " is in both " + prev + " and " + a);
            }
        }
    }

    private RiyadhAreas() {
    }

    /** The area a district is in, if it is one we know. Accepts "حي المروج", "المروج", "مروج". */
    public static Optional<Area> areaOf(String district) {
        if (district == null || district.isBlank()) return Optional.empty();
        String n = normalize(district);
        Area exact = BY_DISTRICT.get(n);
        if (exact != null) return Optional.of(exact);
        // "النسيم" covers النسيم الشرقي and الغربي: accept a partial name when every match is in one area.
        List<Area> partial = BY_DISTRICT.entrySet().stream().filter(e -> e.getKey().startsWith(n + " "))
                .map(Map.Entry::getValue).distinct().toList();
        return partial.size() == 1 ? Optional.of(partial.get(0)) : Optional.empty();
    }

    /**
     * The known district a slightly misspelled name means ("الععارض" -> "العارض"): one letter added, dropped or
     * changed, and only when exactly one known district is that close. Known and unknown-but-unlike names come back as is.
     */
    public static String correct(String district) {
        if (district == null || district.isBlank()) return district;
        String n = normalize(district);
        if (BY_DISTRICT.containsKey(n) || n.length() < 4 || areaOf(district).isPresent()) return district;
        List<String> close = allDistricts().stream().filter(d -> editDistanceAtMostOne(n, normalize(d))).toList();
        return close.size() == 1 ? close.get(0) : district;
    }

    static boolean editDistanceAtMostOne(String a, String b) {
        if (Math.abs(a.length() - b.length()) > 1) return false;
        int i = 0, j = 0, edits = 0;
        while (i < a.length() && j < b.length()) {
            if (a.charAt(i) == b.charAt(j)) {
                i++;
                j++;
                continue;
            }
            if (++edits > 1) return false;
            if (a.length() > b.length()) i++;
            else if (a.length() < b.length()) j++;
            else {
                i++;
                j++;
            }
        }
        return edits + (a.length() - i) + (b.length() - j) <= 1;
    }

    /** NORTH, "north", "شمال", "شمال الرياض", "الشمال" all give NORTH. */
    public static Optional<Area> parse(String raw) {
        if (raw == null || raw.isBlank()) return Optional.empty();
        String t = normalize(raw).replace("رياض", "").trim();
        for (Area a : Area.values()) {
            if (t.equalsIgnoreCase(a.name()) || t.equals(normalize(a.arabicName).replace("رياض", "").trim())) {
                return Optional.of(a);
            }
        }
        if (t.startsWith("شمال") || t.equals("north")) return Optional.of(Area.NORTH);
        if (t.startsWith("جنوب") || t.equals("south")) return Optional.of(Area.SOUTH);
        if (t.startsWith("شرق") || t.equals("east")) return Optional.of(Area.EAST);
        if (t.startsWith("غرب") || t.equals("west")) return Optional.of(Area.WEST);
        if (t.startsWith("وسط") || t.equals("center") || t.equals("centre")) return Optional.of(Area.CENTER);
        return Optional.empty();
    }

    /** Same rules as the database's ar_norm(): unify alef/teh marbuta/yeh, drop "حي " and the leading "ال". */
    public static String normalize(String s) {
        if (s == null) return "";
        String t = s.trim().toLowerCase(Locale.ROOT)
                .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ة', 'ه').replace('ى', 'ي');
        t = t.replaceFirst("^حي\\s+", "");
        t = t.replaceFirst("^ال", "");
        return t.replaceAll("\\s+", " ");
    }

    /** All district names, for tests and the admin view. */
    public static List<String> allDistricts() {
        return Arrays.stream(Area.values()).flatMap(a -> a.districts.stream()).toList();
    }
}
