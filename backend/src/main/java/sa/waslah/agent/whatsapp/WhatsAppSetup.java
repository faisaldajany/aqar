package sa.waslah.agent.whatsapp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import sa.waslah.agent.common.ApiException;
import sa.waslah.agent.common.Digits;
import sa.waslah.agent.conversation.ConversationStore;
import sa.waslah.agent.office.OfficeSettingsController;
import sa.waslah.agent.tenant.TenantContext;

/**
 * The step-by-step setup for an office that has no WhatsApp Business account yet, up to the first customer message.
 * The owner confirms the steps done on the phone (with the number and the date where it helps); Hidaa checks the rest
 * itself: 7 days of use since the number was registered in the app (Meta's condition for connecting a WhatsApp
 * Business app number), the connection, and the first message from a real customer. Runs as the current office.
 */
@Service
public class WhatsAppSetup {

    static final ZoneId RIYADH = ZoneId.of("Asia/Riyadh");
    /** Meta connects a WhatsApp Business app number only after about a week of real use in the app. */
    static final int ACTIVE_DAYS = 7;
    static final LocalDate EARLIEST = LocalDate.of(2018, 1, 1); // WhatsApp Business launched in 2018

    /** In order. Facebook comes before the 7-day wait, so it can be done while waiting. */
    static final List<String> ORDER = List.of("number", "app", "registered", "profile", "facebook", "active", "connect", "test");
    /** Confirmed by the owner; the others are checked by Hidaa. */
    static final Set<String> CONFIRMED = Set.of("number", "app", "registered", "profile", "facebook");
    static final Map<String, String> LABELS = Map.of(
            "number", "رقم جوال المكتب",
            "app", "تطبيق واتساب للأعمال",
            "registered", "تسجيل الرقم في التطبيق",
            "profile", "الملف التجاري",
            "active", "7 أيام استخدام",
            "facebook", "حساب فيسبوك",
            "connect", "الربط بحداء",
            "test", "أول رسالة من عميل");

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public WhatsAppSetup(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    record Row(Boolean hasBusinessApp, String phone, LocalDate since, Map<String, String> steps) {
        static final Row EMPTY = new Row(null, null, null, Map.of());
    }

    private Row row() {
        return jdbc.sql("select has_business_app, office_phone, business_app_since, steps::text from whatsapp_setup")
                .query((rs, n) -> new Row((Boolean) rs.getObject(1), rs.getString(2),
                        rs.getDate(3) == null ? null : rs.getDate(3).toLocalDate(), steps(rs.getString(4))))
                .optional().orElse(Row.EMPTY);
    }

    private Map<String, String> steps(String text) {
        try {
            return text == null ? Map.of() : json.readValue(text, new TypeReference<Map<String, String>>() { });
        } catch (Exception e) {
            return Map.of();
        }
    }

    public Map<String, Object> state() {
        return state(LocalDate.now(RIYADH));
    }

    Map<String, Object> state(LocalDate today) {
        Row r = row();
        Optional<Instant> connectedAt = jdbc.sql("select min(connected_at) from wa_accounts").query(Timestamp.class)
                .optional().map(Timestamp::toInstant);
        Optional<Instant> firstMessage = jdbc.sql("""
                        select min(m.created_at) from messages m
                        join conversations c on c.id = m.conversation_id
                        join contacts k on k.id = c.contact_id
                        where m.direction = 'IN' and k.wa_id <> ?""")
                .param(ConversationStore.SANDBOX).query(Timestamp.class).optional().map(Timestamp::toInstant);
        boolean connected = connectedAt.isPresent();
        LocalDate readyOn = r.since() == null ? null : r.since().plusDays(ACTIVE_DAYS);

        List<Map<String, Object>> steps = new ArrayList<>();
        String current = null;
        int done = 0;
        for (String key : ORDER) {
            boolean ok;
            String at;
            switch (key) {
                case "active" -> {
                    ok = connected || (readyOn != null && !today.isBefore(readyOn));
                    at = readyOn == null ? null : readyOn.toString();
                }
                case "connect" -> {
                    ok = connected;
                    at = connectedAt.map(Instant::toString).orElse(null);
                }
                case "test" -> {
                    ok = firstMessage.isPresent();
                    at = firstMessage.map(Instant::toString).orElse(null);
                }
                // An office that is already connected has done everything before the connection.
                default -> {
                    ok = connected || r.steps().containsKey(key);
                    at = r.steps().get(key);
                }
            }
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("key", key);
            s.put("label", LABELS.get(key));
            s.put("done", ok);
            s.put("doneAt", at);
            s.put("auto", !CONFIRMED.contains(key));
            steps.add(s);
            if (ok) done++;
            else if (current == null) current = key;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("hasBusinessApp", r.hasBusinessApp());
        out.put("phone", r.phone() == null ? null : "0" + r.phone().substring(3));
        out.put("businessAppSince", r.since() == null ? null : r.since().toString());
        out.put("readyOn", readyOn == null ? null : readyOn.toString());
        out.put("daysLeft", readyOn == null || connected ? 0 : Math.max(0, ChronoUnit.DAYS.between(today, readyOn)));
        out.put("officeName", jdbc.sql("select name from offices").query(String.class).optional().orElse(""));
        out.put("connected", connected);
        out.put("steps", steps);
        out.put("current", current);
        out.put("done", done);
        out.put("total", ORDER.size());
        return out;
    }

    /** "Is the office number on WhatsApp Business already?" Only changes the wording of the steps. */
    public Map<String, Object> choose(boolean hasBusinessApp) {
        jdbc.sql("""
                        insert into whatsapp_setup (office_id, has_business_app) values (?, ?)
                        on conflict (office_id) do update set has_business_app = excluded.has_business_app, updated_at = now()""")
                .params(TenantContext.require(), hasBusinessApp).update();
        return state();
    }

    /** The owner confirms a step; the number and the registration date are checked first. */
    public Map<String, Object> confirm(String key, String value) {
        return confirm(key, value, LocalDate.now(RIYADH));
    }

    Map<String, Object> confirm(String key, String value, LocalDate today) {
        if (!CONFIRMED.contains(key)) {
            throw ApiException.badRequest("auto_step", "هذه الخطوة يتحقق منها حداء تلقائياً.");
        }
        String phone = null;
        LocalDate since = null;
        if ("number".equals(key)) {
            if (value == null || value.isBlank()) throw ApiException.badRequest("bad_phone", "اكتب رقم جوال المكتب.");
            phone = OfficeSettingsController.normalizePhone(value);
        }
        if ("registered".equals(key)) {
            try {
                since = LocalDate.parse(Digits.latin(value == null ? "" : value.trim()));
            } catch (DateTimeParseException e) {
                throw ApiException.badRequest("bad_date", "اختر تاريخ تسجيل الرقم في التطبيق.");
            }
            if (since.isAfter(today)) throw ApiException.badRequest("bad_date", "التاريخ لا يكون بعد اليوم.");
            if (since.isBefore(EARLIEST)) throw ApiException.badRequest("bad_date", "تحقق من التاريخ.");
        }
        jdbc.sql("""
                        insert into whatsapp_setup (office_id, office_phone, business_app_since, steps)
                        values (?, ?, ?, jsonb_build_object(?::text, ?::text))
                        on conflict (office_id) do update set
                            office_phone = coalesce(excluded.office_phone, whatsapp_setup.office_phone),
                            business_app_since = coalesce(excluded.business_app_since, whatsapp_setup.business_app_since),
                            steps = whatsapp_setup.steps || excluded.steps,
                            updated_at = now()""")
                .params(TenantContext.require(), phone, since == null ? null : java.sql.Date.valueOf(since),
                        key, Instant.now().truncatedTo(ChronoUnit.SECONDS).toString())
                .update();
        return state();
    }

    /** "تعديل": the step is open again (with its number or date cleared). */
    public Map<String, Object> undo(String key) {
        if (!CONFIRMED.contains(key)) {
            throw ApiException.badRequest("auto_step", "هذه الخطوة يتحقق منها حداء تلقائياً.");
        }
        jdbc.sql("""
                        update whatsapp_setup set steps = steps - ?::text,
                            office_phone = case when ?::text = 'number' then null else office_phone end,
                            business_app_since = case when ?::text = 'registered' then null else business_app_since end,
                            updated_at = now()""")
                .params(key, key, key).update();
        return state();
    }
}
