package sa.waslah.agent.listing;

import java.util.Map;

/** Maps common spellings customers and offices use to one canonical property type. */
public final class PropertyTypes {

    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("فلة", "فيلا"), Map.entry("فله", "فيلا"), Map.entry("فيلة", "فيلا"), Map.entry("فيله", "فيلا"),
            Map.entry("villa", "فيلا"), Map.entry("فلل", "فيلا"), Map.entry("فلا", "فيلا"), Map.entry("فيلات", "فيلا"),
            Map.entry("فلّة", "فيلا"), Map.entry("شقق", "شقة"), Map.entry("أراضي", "أرض"), Map.entry("اراضي", "أرض"),
            Map.entry("أدوار", "دور"), Map.entry("ادوار", "دور"), Map.entry("عماير", "عمارة"), Map.entry("عمائر", "عمارة"),
            Map.entry("شقه", "شقة"), Map.entry("apartment", "شقة"), Map.entry("flat", "شقة"),
            Map.entry("ارض", "أرض"), Map.entry("land", "أرض"),
            Map.entry("دبلكس", "دوبلكس"), Map.entry("duplex", "دوبلكس"),
            Map.entry("عماره", "عمارة"), Map.entry("building", "عمارة"),
            Map.entry("محلات", "محل"), Map.entry("shop", "محل"),
            Map.entry("مكتب تجاري", "مكتب"), Map.entry("office", "مكتب"));

    private PropertyTypes() {
    }

    public static String canonical(String raw) {
        if (raw == null) {
            return null;
        }
        String t = raw.trim().toLowerCase();
        return ALIASES.getOrDefault(t, raw.trim());
    }
}
