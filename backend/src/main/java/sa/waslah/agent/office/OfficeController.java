package sa.waslah.agent.office;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import sa.waslah.agent.billing.PlanLimits;

/** Alerts and plan usage for the signed-in office. */
@RestController
public class OfficeController {

    private final OfficeAlerts alerts;
    private final PlanLimits plans;

    public OfficeController(OfficeAlerts alerts, PlanLimits plans) {
        this.alerts = alerts;
        this.plans = plans;
    }

    @GetMapping("/api/alerts")
    List<OfficeAlerts.Alert> alerts(@RequestParam(defaultValue = "false") boolean unread) {
        return alerts.recent(unread, 100);
    }

    @GetMapping("/api/alerts/count")
    Map<String, Integer> count() {
        return alerts.unreadCount();
    }

    @PostMapping("/api/alerts/{id}/read")
    Map<String, Object> read(@PathVariable UUID id) {
        alerts.markRead(id);
        return Map.of("read", true);
    }

    @PostMapping("/api/alerts/read-all")
    Map<String, Object> readAll() {
        alerts.markAllRead();
        return Map.of("read", true);
    }

    @GetMapping("/api/usage")
    Map<String, Object> usage() {
        PlanLimits.Usage u = plans.usageThisMonth();
        return Map.of(
                "plan", u.plan().name(),
                "planName", u.plan().arabicName,
                "periodStart", u.periodStart().toString(),
                "replies", u.replies(),
                "included", u.includedReplies(),
                "percent", u.percent(),
                "overageReplies", u.overageReplies(),
                "overageSar", u.overageSar(),
                "marketersLimit", u.plan().marketers);
    }
}
