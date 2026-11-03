package sa.waslah.agent.ops;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public liveness check through the load balancer (actuator runs on an internal port): 200 when the API is up and
 * the database answers, 503 otherwise. No data in the response.
 */
@RestController
public class HealthController {

    private final JdbcClient jdbc;

    public HealthController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/api/public/health")
    ResponseEntity<Map<String, Object>> health() {
        try {
            jdbc.sql("select 1").query(Integer.class).single();
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (RuntimeException e) {
            return ResponseEntity.status(503).body(Map.of("ok", false));
        }
    }
}
