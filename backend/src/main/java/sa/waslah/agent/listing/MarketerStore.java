package sa.waslah.agent.listing;

import java.sql.Array;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Marketers under the current office (RLS). */
@Repository
public class MarketerStore {

    public record Marketer(UUID id, String name, String waPhone, List<String> districts, boolean active) {
    }

    private final JdbcClient jdbc;

    public MarketerStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Marketer> active() {
        return jdbc.sql("select id, name, wa_phone, districts, active from marketers where active order by created_at")
                .query(MarketerStore::map).list();
    }

    public Optional<Marketer> find(UUID id) {
        return jdbc.sql("select id, name, wa_phone, districts, active from marketers where id = ?")
                .param(id).query(MarketerStore::map).optional();
    }

    /** The active marketer covering this district, else the first active marketer. */
    public Optional<Marketer> forDistrict(String district) {
        if (district != null && !district.isBlank()) {
            Optional<Marketer> m = jdbc.sql("""
                    select id, name, wa_phone, districts, active from marketers
                    where active and exists (select 1 from unnest(districts) d where ar_norm(d) = ar_norm(?))
                    order by created_at limit 1""")
                    .param(district).query(MarketerStore::map).optional();
            if (m.isPresent()) {
                return m;
            }
        }
        return active().stream().findFirst();
    }

    public void deactivate(UUID id) {
        jdbc.sql("update marketers set active = false where id = ?").param(id).update();
    }

    public UUID insert(UUID officeId, String name, String waPhone, List<String> districts) {
        UUID id = UUID.randomUUID();
        jdbc.sql("insert into marketers (id, office_id, name, wa_phone, districts) values (?, ?, ?, ?, ?::text[])")
                .params(id, officeId, name, waPhone, toPgArray(districts))
                .update();
        return id;
    }

    private static String toPgArray(List<String> items) {
        StringBuilder b = new StringBuilder("{");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) b.append(',');
            b.append('"').append(items.get(i).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return b.append('}').toString();
    }

    private static Marketer map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        Array arr = rs.getArray("districts");
        List<String> districts = arr == null ? List.of() : Arrays.asList((String[]) arr.getArray());
        return new Marketer(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("wa_phone"),
                districts, rs.getBoolean("active"));
    }
}
