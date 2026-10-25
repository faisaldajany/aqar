package sa.waslah.agent.listing.importing;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import sa.waslah.agent.common.ApiException;
import sa.waslah.agent.listing.ListingStore;
import sa.waslah.agent.listing.importing.ListingRowParser.Field;
import sa.waslah.agent.tenant.TenantContext;

/**
 * Two-step spreadsheet import: upload parses and proposes a column mapping with a preview;
 * commit applies the (possibly corrected) mapping and upserts every valid row by ad license.
 */
@Service
public class ImportService {

    static final ZoneId RIYADH = ZoneId.of("Asia/Riyadh");
    static final int PREVIEW_ROWS = 50;

    public static final Map<Field, String> LABELS = labels();

    public record RowResult(int row, String title, String district, String purpose, String price, String license,
                            String expiry, String status, List<String> errors, List<String> warnings) {
    }

    public record Preview(UUID batchId, String fileName, List<String> headers, Map<Field, Integer> mapping,
                          List<Map<String, Object>> fields, int total, int ready, int withWarnings, int invalid,
                          List<RowResult> rows) {
    }

    public record Committed(int created, int updated, int skipped, List<RowResult> skippedRows) {
    }

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final ListingStore listings;
    private final TransactionTemplate tx;

    public ImportService(JdbcClient jdbc, ObjectMapper json, ListingStore listings, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.json = json;
        this.listings = listings;
        this.tx = tx;
    }

    public Preview upload(String fileName, byte[] bytes) {
        SheetReader.Table table = SheetReader.read(fileName, bytes);
        UUID office = TenantContext.require();
        UUID id = jdbc.sql("""
                insert into import_batches (office_id, file_name, headers, rows)
                values (?, ?, ?::jsonb, ?::jsonb) returning id""")
                .params(office, fileName == null ? "file" : fileName, write(table.headers()), write(table.rows()))
                .query(UUID.class).single();
        return preview(id, fileName, table, ListingRowParser.autoMap(table.headers()));
    }

    public Preview preview(UUID batchId, Map<Field, Integer> mapping) {
        Batch b = batch(batchId);
        return preview(batchId, b.fileName(), b.table(), mapping == null ? ListingRowParser.autoMap(b.table().headers()) : mapping);
    }

    public Committed commit(UUID batchId, Map<Field, Integer> mappingOrNull) {
        Batch b = batch(batchId);
        if (!"PENDING".equals(b.status())) {
            throw ApiException.conflict("import_done", "تم استيراد هذا الملف مسبقاً.");
        }
        Map<Field, Integer> mapping = mappingOrNull == null ? ListingRowParser.autoMap(b.table().headers()) : mappingOrNull;
        requireMapped(mapping);
        UUID office = TenantContext.require();
        LocalDate today = LocalDate.now(RIYADH);

        // Last row wins when the same license appears twice in one file.
        Map<String, Integer> lastRowForLicense = new LinkedHashMap<>();
        List<ListingRowParser.Parsed> parsed = new ArrayList<>();
        for (int i = 0; i < b.table().rows().size(); i++) {
            ListingRowParser.Parsed p = ListingRowParser.parse(b.table().rows().get(i), mapping, today);
            parsed.add(p);
            if (p.importable()) lastRowForLicense.put(p.listing().adLicense(), i);
        }
        Set<Integer> toWrite = new HashSet<>(lastRowForLicense.values());

        return tx.execute(s -> {
            int created = 0, updated = 0;
            List<RowResult> skipped = new ArrayList<>();
            for (int i = 0; i < parsed.size(); i++) {
                ListingRowParser.Parsed p = parsed.get(i);
                if (!p.importable()) {
                    skipped.add(result(i, p));
                    continue;
                }
                if (!toWrite.contains(i)) continue;
                if (listings.upsert(office, p.listing()).created()) created++;
                else updated++;
            }
            jdbc.sql("""
                    update import_batches set status = 'COMMITTED', created = ?, updated = ?, skipped = ?,
                           committed_at = now() where id = ?""")
                    .params(created, updated, skipped.size(), batchId).update();
            return new Committed(created, updated, skipped.size(), skipped);
        });
    }

    public void discard(UUID batchId) {
        jdbc.sql("update import_batches set status = 'DISCARDED' where id = ? and status = 'PENDING'").param(batchId).update();
    }

    private Preview preview(UUID id, String fileName, SheetReader.Table t, Map<Field, Integer> mapping) {
        LocalDate today = LocalDate.now(RIYADH);
        int ready = 0, warn = 0, bad = 0;
        List<RowResult> rows = new ArrayList<>();
        for (int i = 0; i < t.rows().size(); i++) {
            ListingRowParser.Parsed p = ListingRowParser.parse(t.rows().get(i), mapping, today);
            if (!p.importable()) bad++;
            else if (!p.warnings().isEmpty()) warn++;
            else ready++;
            if (rows.size() < PREVIEW_ROWS) rows.add(result(i, p));
        }
        List<Map<String, Object>> fields = new ArrayList<>();
        for (Field f : Field.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("field", f.name());
            m.put("label", LABELS.get(f));
            m.put("required", f.required);
            m.put("column", mapping.get(f));
            fields.add(m);
        }
        return new Preview(id, fileName, t.headers(), mapping, fields, t.rows().size(), ready, warn, bad, rows);
    }

    private static RowResult result(int i, ListingRowParser.Parsed p) {
        var l = p.listing();
        String status = !p.importable() ? "INVALID" : p.warnings().isEmpty() ? "READY" : "WARNING";
        return new RowResult(i + 2, l.title(), l.district(), l.purpose(),
                l.price() == null ? null : l.price().stripTrailingZeros().toPlainString(),
                l.adLicense(), l.licenseExpiry() == null ? null : l.licenseExpiry().toString(),
                status, p.errors(), p.warnings());
    }

    private static void requireMapped(Map<Field, Integer> mapping) {
        List<String> missing = new ArrayList<>();
        for (Field f : Field.values()) {
            if (f.required && mapping.get(f) == null) missing.add(LABELS.get(f));
        }
        if (!missing.isEmpty()) {
            throw ApiException.badRequest("unmapped_fields", "حدّد عمود: " + String.join("، ", missing));
        }
    }

    private record Batch(String fileName, String status, SheetReader.Table table) {
    }

    private Batch batch(UUID id) {
        return jdbc.sql("select file_name, status, headers::text, rows::text from import_batches where id = ?")
                .param(id)
                .query((rs, n) -> new Batch(rs.getString(1), rs.getString(2),
                        new SheetReader.Table(read(rs.getString(3), new TypeReference<List<String>>() { }),
                                read(rs.getString(4), new TypeReference<List<List<String>>>() { }))))
                .optional()
                .orElseThrow(() -> ApiException.notFound("no_import", "ملف الاستيراد غير موجود."));
    }

    private String write(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private <T> T read(String s, TypeReference<T> type) {
        try {
            return json.readValue(s, type);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<Field, String> labels() {
        Map<Field, String> m = new EnumMap<>(Field.class);
        m.put(Field.TITLE, "اسم العقار");
        m.put(Field.DISTRICT, "الحي");
        m.put(Field.TYPE, "نوع العقار");
        m.put(Field.PURPOSE, "الغرض (بيع/إيجار)");
        m.put(Field.PRICE, "السعر");
        m.put(Field.AREA, "المساحة");
        m.put(Field.ROOMS, "الغرف");
        m.put(Field.LICENSE, "رقم ترخيص الإعلان");
        m.put(Field.EXPIRY, "تاريخ انتهاء الترخيص");
        m.put(Field.VACANT, "شاغر أو مسكون");
        m.put(Field.LOCATION, "رابط الموقع");
        m.put(Field.LATITUDE, "خط العرض");
        m.put(Field.LONGITUDE, "خط الطول");
        m.put(Field.VIDEO, "رابط الفيديو أو الجولة");
        m.put(Field.PHOTOS, "روابط الصور");
        m.put(Field.DESCRIPTION, "الوصف");
        return m;
    }
}
