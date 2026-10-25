package sa.waslah.agent.listing;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Listings under the current office (RLS). Search returns only active listings with a valid ad license. */
@Repository
public class ListingStore {

    public record Listing(UUID id, String title, String district, String propertyType, String purpose,
                          BigDecimal price, BigDecimal areaSqm, Integer rooms, String adLicense,
                          LocalDate licenseExpiry, boolean vacant, String status, UUID marketerId) {
    }

    public record Query(String purpose, String propertyType, String district, BigDecimal maxPrice,
                        BigDecimal minPrice, Integer minRooms, int limit) {
    }

    private static final String COLUMNS = """
            id, title, district, property_type, purpose, price, area_sqm, rooms, ad_license, license_expiry,
            vacant, status, marketer_id""";

    private final JdbcClient jdbc;

    public ListingStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Listing> search(Query q) {
        return jdbc.sql("select " + COLUMNS + """
                 from listings
                 where status = 'ACTIVE' and license_expiry >= current_date
                   and (cast(:purpose as text) is null or purpose = :purpose)
                   and (cast(:ptype as text) is null or ar_norm(property_type) like '%' || ar_norm(:ptype) || '%')
                   and (cast(:district as text) is null or ar_norm(district) like '%' || ar_norm(:district) || '%')
                   and (cast(:maxp as numeric) is null or price <= :maxp)
                   and (cast(:minp as numeric) is null or price >= :minp)
                   and (cast(:rooms as int) is null or coalesce(rooms, 0) >= :rooms)
                 order by price
                 limit :lim""")
                .param("purpose", blankToNull(q.purpose()))
                .param("ptype", blankToNull(PropertyTypes.canonical(q.propertyType())))
                .param("district", blankToNull(q.district()))
                .param("maxp", q.maxPrice())
                .param("minp", q.minPrice())
                .param("rooms", q.minRooms())
                .param("lim", Math.max(1, Math.min(q.limit(), 10)))
                .query(ListingStore::map)
                .list();
    }

    public Optional<Listing> find(UUID id) {
        return jdbc.sql("select " + COLUMNS + " from listings where id = ?").param(id).query(ListingStore::map).optional();
    }

    public List<Listing> all() {
        return jdbc.sql("select " + COLUMNS + " from listings order by created_at desc").query(ListingStore::map).list();
    }

    public UUID insert(UUID officeId, Map<String, Object> f) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                insert into listings (id, office_id, title, district, property_type, purpose, price, area_sqm, rooms,
                                      ad_license, license_expiry, vacant, marketer_id, aqar_url)
                values (:id, :office, :title, :district, :ptype, :purpose, :price, :area, :rooms,
                        :license, :expiry, :vacant, :marketer, :aqar)""")
                .param("id", id).param("office", officeId)
                .param("title", f.get("title")).param("district", f.get("district"))
                .param("ptype", PropertyTypes.canonical((String) f.get("propertyType")))
                .param("purpose", f.get("purpose")).param("price", f.get("price"))
                .param("area", f.get("areaSqm")).param("rooms", f.get("rooms"))
                .param("license", f.get("adLicense"))
                .param("expiry", Date.valueOf((LocalDate) f.get("licenseExpiry")))
                .param("vacant", f.getOrDefault("vacant", Boolean.TRUE))
                .param("marketer", f.get("marketerId")).param("aqar", f.get("aqarUrl"))
                .update();
        return id;
    }

    private static Listing map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        int rooms = rs.getInt("rooms");
        return new Listing(
                rs.getObject("id", UUID.class),
                rs.getString("title"),
                rs.getString("district"),
                rs.getString("property_type"),
                rs.getString("purpose"),
                rs.getBigDecimal("price"),
                rs.getBigDecimal("area_sqm"),
                rs.wasNull() ? null : rooms,
                rs.getString("ad_license"),
                rs.getDate("license_expiry").toLocalDate(),
                rs.getBoolean("vacant"),
                rs.getString("status"),
                rs.getObject("marketer_id", UUID.class));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
