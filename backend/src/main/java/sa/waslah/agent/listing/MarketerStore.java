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

    /**
     * Who gets a new customer: among the active marketers covering this district (or all active ones if none does),
     * the one with the fewest open handoffs, then the one who got a handoff longest ago, so work is shared.
     */
    public Optional<Marketer> forDistrict(String district) {
        return forDistrict(district, List.of());
    }

    /** The same, leaving out these marketers (the ones a handoff has already been with). */
    public Optional<Marketer> forDistrict(String district, List<UUID> exclude) {
        String skip = "{" + String.join(",", exclude.stream().map(UUID::toString).toList()) + "}";
        if (district != null && !district.isBlank()) {
            Optional<Marketer> m = pick("and exists (select 1 from unnest(m.districts) d where ar_norm(d) = ar_norm(:district))",
                    district, skip);
            if (m.isPresent()) return m;
        }
        return pick("", null, skip);
    }

    private Optional<Marketer> pick(String where, String district, String skip) {
        return jdbc.sql("""
                        select m.id, m.name, m.wa_phone, m.districts, m.active from marketers m
                        where m.active and not (m.id = any(cast(:skip as uuid[])))""" + " " + where + """
                        order by (select count(*) from handoffs h where h.marketer_id = m.id and h.status = 'OPEN'),
                                 (select max(coalesce(h.assigned_at, h.created_at)) from handoffs h where h.marketer_id = m.id) nulls first,
                                 m.created_at
                        limit 1""")
                .param("skip", skip).param("district", district)
                .query(MarketerStore::map).optional();
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
