package sa.waslah.agent.listing;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Arrays;
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
                          LocalDate licenseExpiry, boolean vacant, String status, UUID marketerId,
                          Double latitude, Double longitude, String videoUrl, List<String> photoUrls,
                          int uploadedPhotos, String description, String source, String sourceUrl) {

        public boolean hasLocation() {
            return latitude != null && longitude != null;
        }

        public boolean licenseValid(LocalDate today) {
            return !licenseExpiry.isBefore(today);
        }
    }

    public record Query(String purpose, String propertyType, String district, BigDecimal maxPrice,
                        BigDecimal minPrice, Integer minRooms, int limit) {
    }

    public record Photo(UUID id, String contentType, byte[] bytes) {
    }

    /** Result of an upsert keyed on the ad license. */
    public record Saved(UUID id, boolean created) {
    }

    private static final String COLUMNS = """
            l.id, l.title, l.district, l.property_type, l.purpose, l.price, l.area_sqm, l.rooms, l.ad_license,
            l.license_expiry, l.vacant, l.status, l.marketer_id, l.latitude, l.longitude, l.video_url, l.photo_urls,
            (select count(*) from listing_photos p where p.listing_id = l.id) as uploaded_photos,
            l.description, l.source, l.source_url""";

    private final JdbcClient jdbc;

    public ListingStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Listing> search(Query q) {
        return jdbc.sql("select " + COLUMNS + " " + """
                 from listings l
                 where l.status = 'ACTIVE' and l.license_expiry >= current_date
                   and (cast(:purpose as text) is null or l.purpose = :purpose)
                   and (cast(:ptype as text) is null or ar_norm(l.property_type) like '%' || ar_norm(:ptype) || '%')
                   and (cast(:district as text) is null or ar_norm(l.district) like '%' || ar_norm(:district) || '%')
                   and (cast(:maxp as numeric) is null or l.price <= :maxp)
                   and (cast(:minp as numeric) is null or l.price >= :minp)
                   and (cast(:rooms as int) is null or coalesce(l.rooms, 0) >= :rooms)
                 order by l.price
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
        return jdbc.sql("select " + COLUMNS + " from listings l where l.id = ?").param(id).query(ListingStore::map).optional();
    }

    public List<Listing> all() {
        return jdbc.sql("select " + COLUMNS + " from listings l order by l.created_at desc").query(ListingStore::map).list();
    }

    /** Creates the listing, or updates the one with the same ad license in this office. */
    public Saved upsert(UUID officeId, ListingInput in) {
        return jdbc.sql("""
                insert into listings (office_id, title, district, property_type, purpose, price, area_sqm, rooms,
                                      ad_license, license_expiry, vacant, marketer_id, latitude, longitude, video_url,
                                      photo_urls, description, source, source_url)
                values (:office, :title, :district, :ptype, :purpose, :price, :area, :rooms, :license, :expiry,
                        :vacant, :marketer, :lat, :lng, :video, cast(:photos as text[]), :descr, :source, :surl)
                on conflict (office_id, ad_license) do update set
                    title          = excluded.title,
                    district       = excluded.district,
                    property_type  = excluded.property_type,
                    purpose        = excluded.purpose,
                    price          = excluded.price,
                    area_sqm       = coalesce(excluded.area_sqm, listings.area_sqm),
                    rooms          = coalesce(excluded.rooms, listings.rooms),
                    license_expiry = excluded.license_expiry,
                    vacant         = excluded.vacant,
                    marketer_id    = coalesce(excluded.marketer_id, listings.marketer_id),
                    latitude       = coalesce(excluded.latitude, listings.latitude),
                    longitude      = coalesce(excluded.longitude, listings.longitude),
                    video_url      = coalesce(excluded.video_url, listings.video_url),
                    photo_urls     = case when cardinality(excluded.photo_urls) > 0 then excluded.photo_urls else listings.photo_urls end,
                    description    = coalesce(excluded.description, listings.description),
                    status         = case when listings.status = 'SOLD' then listings.status else 'ACTIVE' end,
                    updated_at     = now()
                returning id, (xmax = 0) as created""")
                .param("office", officeId)
                .param("title", in.title())
                .param("district", in.district())
                .param("ptype", PropertyTypes.canonical(in.propertyType()))
                .param("purpose", in.purpose())
                .param("price", in.price())
                .param("area", in.areaSqm())
                .param("rooms", in.rooms())
                .param("license", in.adLicense())
                .param("expiry", Date.valueOf(in.licenseExpiry()))
                .param("vacant", in.vacant() == null ? Boolean.TRUE : in.vacant())
                .param("marketer", in.marketerId())
                .param("lat", in.latitude())
                .param("lng", in.longitude())
                .param("video", blankToNull(in.videoUrl()))
                .param("photos", pgArray(in.photoUrls()))
                .param("descr", blankToNull(in.description()))
                .param("source", in.source())
                .param("surl", blankToNull(in.sourceUrl()))
                .query((rs, n) -> new Saved(rs.getObject("id", UUID.class), rs.getBoolean("created")))
                .single();
    }

    /** Kept for callers that build a field map (tests, older code). */
    public UUID insert(UUID officeId, Map<String, Object> f) {
        return upsert(officeId, new ListingInput(
                (String) f.get("title"), (String) f.get("district"), (String) f.get("propertyType"),
                (String) f.get("purpose"), (BigDecimal) f.get("price"), (BigDecimal) f.get("areaSqm"),
                (Integer) f.get("rooms"), (String) f.get("adLicense"), (LocalDate) f.get("licenseExpiry"),
                (Boolean) f.get("vacant"), (UUID) f.get("marketerId"), (Double) f.get("latitude"),
                (Double) f.get("longitude"), (String) f.get("videoUrl"), null, null, "MANUAL",
                (String) f.get("aqarUrl"))).id();
    }

    public void setStatus(UUID id, String status) {
        jdbc.sql("update listings set status = ?, updated_at = now() where id = ?").params(status, id).update();
    }

    public UUID addPhoto(UUID officeId, UUID listingId, String contentType, byte[] bytes) {
        return jdbc.sql("""
                insert into listing_photos (office_id, listing_id, content_type, bytes, position)
                values (?, ?, ?, ?, (select coalesce(max(position) + 1, 0) from listing_photos where listing_id = ?))
                returning id""")
                .params(officeId, listingId, contentType, bytes, listingId)
                .query(UUID.class).single();
    }

    public int photoCount(UUID listingId) {
        return jdbc.sql("select count(*) from listing_photos where listing_id = ?").param(listingId).query(Integer.class).single();
    }

    public List<Photo> photos(UUID listingId, int limit) {
        return jdbc.sql("select id, content_type, bytes from listing_photos where listing_id = ? order by position limit ?")
                .params(listingId, limit)
                .query((rs, n) -> new Photo(rs.getObject("id", UUID.class), rs.getString("content_type"), rs.getBytes("bytes")))
                .list();
    }

    public Optional<Photo> photo(UUID listingId, UUID photoId) {
        return jdbc.sql("select id, content_type, bytes from listing_photos where listing_id = ? and id = ?")
                .params(listingId, photoId)
                .query((rs, n) -> new Photo(rs.getObject("id", UUID.class), rs.getString("content_type"), rs.getBytes("bytes")))
                .optional();
    }

    public void deletePhoto(UUID listingId, UUID photoId) {
        jdbc.sql("delete from listing_photos where listing_id = ? and id = ?").params(listingId, photoId).update();
    }

    private static Listing map(ResultSet rs, int n) throws SQLException {
        int rooms = rs.getInt("rooms");
        Integer roomsOrNull = rs.wasNull() ? null : rooms;
        double lat = rs.getDouble("latitude");
        Double latOrNull = rs.wasNull() ? null : lat;
        double lng = rs.getDouble("longitude");
        Double lngOrNull = rs.wasNull() ? null : lng;
        Array photos = rs.getArray("photo_urls");
        return new Listing(
                rs.getObject("id", UUID.class),
                rs.getString("title"),
                rs.getString("district"),
                rs.getString("property_type"),
                rs.getString("purpose"),
                rs.getBigDecimal("price"),
                rs.getBigDecimal("area_sqm"),
                roomsOrNull,
                rs.getString("ad_license"),
                rs.getDate("license_expiry").toLocalDate(),
                rs.getBoolean("vacant"),
                rs.getString("status"),
                rs.getObject("marketer_id", UUID.class),
                latOrNull,
                lngOrNull,
                rs.getString("video_url"),
                photos == null ? List.of() : Arrays.asList((String[]) photos.getArray()),
                rs.getInt("uploaded_photos"),
                rs.getString("description"),
                rs.getString("source"),
                rs.getString("source_url"));
    }

    static String pgArray(List<String> items) {
        StringBuilder b = new StringBuilder("{");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) b.append(',');
            b.append('"').append(items.get(i).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return b.append('}').toString();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
