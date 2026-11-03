package sa.waslah.agent.common;

import jakarta.validation.ConstraintViolation;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import org.springframework.validation.FieldError;

/**
 * An Arabic message for one invalid field that says which field it is and what is wrong with it, e.g.
 * "«كلمة المرور»: 8 أحرف على الأقل." A constraint with its own message (message = "...") keeps that message.
 */
final class ValidationMessages {

    static final String GENERIC = "راجع الحقول المطلوبة.";

    private static final Set<String> REQUIRED = Set.of("NotBlank", "NotNull", "NotEmpty");

    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("officeName", "اسم المكتب"),
            Map.entry("falLicense", "رقم رخصة فال"),
            Map.entry("fullName", "الاسم"),
            Map.entry("name", "الاسم"),
            Map.entry("email", "البريد الإلكتروني"),
            Map.entry("password", "كلمة المرور"),
            Map.entry("promoCode", "كود الخصم"),
            Map.entry("token", "الرابط"),
            Map.entry("code", "الرمز"),
            Map.entry("title", "عنوان الإعلان"),
            Map.entry("district", "الحي"),
            Map.entry("propertyType", "نوع العقار"),
            Map.entry("purpose", "الغرض"),
            Map.entry("price", "السعر"),
            Map.entry("adLicense", "رقم ترخيص الإعلان"),
            Map.entry("licenseExpiry", "تاريخ انتهاء الترخيص"),
            Map.entry("photoUrls", "الصور"),
            Map.entry("videoUrl", "رابط الفيديو"),
            Map.entry("latitude", "الموقع"),
            Map.entry("longitude", "الموقع"),
            Map.entry("status", "الحالة"),
            Map.entry("waPhone", "رقم واتساب"),
            Map.entry("phone", "رقم الجوال"),
            Map.entry("ownerPhone", "رقم الجوال"),
            Map.entry("accessMethod", "طريقة الدخول"),
            Map.entry("methods", "طرق الدخول"),
            Map.entry("days", "الأيام"),
            Map.entry("startTime", "وقت البداية"),
            Map.entry("endTime", "وقت النهاية"),
            Map.entry("selfStartTime", "بداية الزيارة الذاتية"),
            Map.entry("selfEndTime", "نهاية الزيارة الذاتية"),
            Map.entry("slotMinutes", "مدة الموعد"),
            Map.entry("bufferMinutes", "الفاصل بين المواعيد"),
            Map.entry("minLeadMinutes", "أقل مدة قبل الموعد"),
            Map.entry("reminderMinutes", "وقت التذكير"),
            Map.entry("codeWindowMinutes", "مدة صلاحية الرمز"),
            Map.entry("template", "نص الرسالة"),
            Map.entry("templateSelf", "رسالة الزيارة الذاتية"),
            Map.entry("templateRep", "رسالة الزيارة مع المسوّق"),
            Map.entry("mode", "نوع الزيارة"),
            Map.entry("text", "الرسالة"),
            Map.entry("message", "الرسالة"),
            Map.entry("topic", "الموضوع"),
            Map.entry("reportHour", "ساعة التقرير"));

    private ValidationMessages() {
    }

    static boolean required(FieldError e) {
        return e.getCode() != null && REQUIRED.contains(e.getCode());
    }

    /** "days[0]" and "access.methods" are labelled as "days" and "methods". */
    static String label(String field) {
        String base = field == null ? "" : field.replaceAll("\\[[^]]*]", "");
        int dot = base.lastIndexOf('.');
        return LABELS.get(dot >= 0 ? base.substring(dot + 1) : base);
    }

    static String of(FieldError e) {
        Map<String, Object> attrs = Map.of();
        if (e.contains(ConstraintViolation.class)) {
            ConstraintViolation<?> v = e.unwrap(ConstraintViolation.class);
            String template = v.getMessageTemplate();
            if (template != null && !template.isBlank() && !template.startsWith("{")) return v.getMessage();
            attrs = v.getConstraintDescriptor().getAttributes();
        }
        String label = label(e.getField());
        if (label == null) return GENERIC;
        String name = "«" + label + "»: ";
        String code = e.getCode() == null ? "" : e.getCode();
        Object value = e.getRejectedValue();
        if (REQUIRED.contains(code)) return name + "هذا الحقل مطلوب.";
        return switch (code) {
            case "Email" -> name + "الصيغة غير صحيحة، مثل name@example.com";
            case "Size" -> size(name, value, attrs);
            case "Min", "DecimalMin" -> name + "أقل قيمة " + attrs.getOrDefault("value", "") + ".";
            case "Max", "DecimalMax" -> name + "أعلى قيمة " + attrs.getOrDefault("value", "") + ".";
            case "Positive" -> name + "يجب أن تكون القيمة أكبر من صفر.";
            case "PositiveOrZero" -> name + "لا تقبل قيمة سالبة.";
            default -> name + "القيمة غير صحيحة.";
        };
    }

    private static String size(String name, Object value, Map<String, Object> attrs) {
        int min = attrs.get("min") instanceof Integer i ? i : 0;
        int max = attrs.get("max") instanceof Integer i ? i : Integer.MAX_VALUE;
        if (value instanceof Collection<?> c) {
            return c.size() < min ? name + "اختر " + min + " على الأقل." : name + max + " على الأكثر.";
        }
        int length = value instanceof CharSequence s ? s.length() : 0;
        if (length < min) return name + chars(min) + " على الأقل.";
        if (max != Integer.MAX_VALUE) return name + chars(max) + " على الأكثر.";
        return name + "الطول غير مناسب.";
    }

    static String chars(int n) {
        if (n == 1) return "حرف واحد";
        if (n == 2) return "حرفان";
        if (n >= 3 && n <= 10) return n + " أحرف";
        return n + " حرفاً";
    }
}
