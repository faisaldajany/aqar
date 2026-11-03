package sa.waslah.agent.conversation;

import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Each marketer's numbers, and moving unanswered customers on (see {@link HandoffRouting}). */
@RestController
public class MarketerPerformanceController {

    public record Routing(Integer timeoutMinutes) {
    }

    private final HandoffRouting routing;

    public MarketerPerformanceController(HandoffRouting routing) {
        this.routing = routing;
    }

    @GetMapping("/api/marketers/performance")
    List<Map<String, Object>> performance(@RequestParam(defaultValue = "30") int days) {
        return routing.performance(days == 7 || days == 90 ? days : 30);
    }

    @GetMapping("/api/marketers/routing")
    Map<String, Object> settings() {
        return routing.settings();
    }

    @PutMapping("/api/marketers/routing")
    Map<String, Object> set(@RequestBody Routing r) {
        return routing.setTimeout(r == null ? null : r.timeoutMinutes());
    }
}
