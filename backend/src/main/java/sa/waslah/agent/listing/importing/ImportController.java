package sa.waslah.agent.listing.importing;

import java.io.IOException;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import sa.waslah.agent.listing.importing.ListingRowParser.Field;

@RestController
@RequestMapping("/api/imports")
public class ImportController {

    /** Field name → column index (or null to leave unmapped). */
    public record MappingRequest(Map<String, Integer> mapping) {
        Map<Field, Integer> toFields() {
            if (mapping == null) return null;
            Map<Field, Integer> m = new EnumMap<>(Field.class);
            mapping.forEach((k, v) -> {
                if (v != null && v >= 0) m.put(Field.valueOf(k), v);
            });
            return m;
        }
    }

    public record AqarLink(String url) {
    }

    public record FeedRequest(String aqarProfile) {
    }

    private final ImportService imports;
    private final AqarLinkImporter aqar;
    private final org.springframework.jdbc.core.simple.JdbcClient jdbc;

    public ImportController(ImportService imports, AqarLinkImporter aqar, org.springframework.jdbc.core.simple.JdbcClient jdbc) {
        this.imports = imports;
        this.aqar = aqar;
        this.jdbc = jdbc;
    }

    /** Reads a public Aqar listing into a draft; the office reviews it and saves via POST /api/listings. */
    @PostMapping("/aqar-link")
    AqarLinkImporter.Draft aqarLink(@RequestBody AqarLink r) {
        return aqar.draft(r.url());
    }

    /** Records interest in an official Aqar feed (not available yet). */
    @PostMapping("/aqar-feed")
    Map<String, Object> aqarFeed(@RequestBody(required = false) FeedRequest r) {
        jdbc.sql("""
                insert into aqar_feed_requests (office_id, aqar_profile) values (?, ?)
                on conflict (office_id) do update set aqar_profile = coalesce(excluded.aqar_profile, aqar_feed_requests.aqar_profile)""")
                .params(sa.waslah.agent.tenant.TenantContext.require(), r == null ? null : r.aqarProfile())
                .update();
        return Map.of("requested", true);
    }

    @PostMapping
    ImportService.Preview upload(@RequestParam("file") MultipartFile file) throws IOException {
        return imports.upload(file.getOriginalFilename(), file.getBytes());
    }

    @PostMapping("/{id}/preview")
    ImportService.Preview preview(@PathVariable UUID id, @RequestBody(required = false) MappingRequest r) {
        return imports.preview(id, r == null ? null : r.toFields());
    }

    @PostMapping("/{id}/commit")
    ImportService.Committed commit(@PathVariable UUID id, @RequestBody(required = false) MappingRequest r) {
        return imports.commit(id, r == null ? null : r.toFields());
    }

    @PostMapping("/{id}/discard")
    Map<String, Object> discard(@PathVariable UUID id) {
        imports.discard(id);
        return Map.of("discarded", true);
    }
}
