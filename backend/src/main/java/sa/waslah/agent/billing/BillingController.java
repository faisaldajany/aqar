package sa.waslah.agent.billing;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import sa.waslah.agent.common.ApiException;
import sa.waslah.agent.tenant.TenantContext;

@RestController
public class BillingController {

    private static final Logger log = LoggerFactory.getLogger(BillingController.class);

    public record CheckoutRequest(String plan, String cycle) {
    }

    private final BillingService billing;
    private final Subscription subscription;
    private final PlanLimits plans;
    private final BillingProperties props;
    private final sa.waslah.agent.config.WaslahProperties app;

    public BillingController(BillingService billing, Subscription subscription, PlanLimits plans, BillingProperties props,
                             sa.waslah.agent.config.WaslahProperties app) {
        this.app = app;
        this.billing = billing;
        this.subscription = subscription;
        this.plans = plans;
        this.props = props;
    }

    @GetMapping("/api/billing")
    Map<String, Object> state() {
        Instant now = Instant.now();
        Subscription.State s = subscription.state();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", s.status());
        out.put("plan", s.plan().name());
        out.put("cycle", s.cycle());
        out.put("trialEndsAt", s.trialEndsAt().toString());
        out.put("trialDaysLeft", s.trialDaysLeft(now));
        out.put("paidUntil", s.paidUntil() == null ? null : s.paidUntil().toString());
        out.put("serving", s.serving(now));
        out.put("vatPercent", props.vatPercent());
        out.put("provider", props.moyasar() ? "moyasar" : "mock");
        out.put("mockPayments", !props.moyasar() && app.devToolsEnabled());
        List<Map<String, Object>> list = new ArrayList<>();
        for (PlanLimits.Plan p : PlanLimits.Plan.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.name());
            m.put("name", p.arabicName);
            m.put("monthlySar", p.monthlySar);
            m.put("annualSar", p.annualSar);
            m.put("includedConversations", p.includedConversations);
            m.put("marketers", p.marketers);
            m.put("whatsappNumbers", p.whatsappNumbers);
            m.put("overageSar", p.overagePerConversationSar);
            list.add(m);
        }
        out.put("plans", list);
        return out;
    }

    @PostMapping("/api/billing/checkout")
    BillingService.Invoice checkout(@RequestBody CheckoutRequest r) {
        PlanLimits.Plan plan;
        try {
            plan = PlanLimits.Plan.valueOf(r.plan());
        } catch (RuntimeException e) {
            throw ApiException.badRequest("bad_plan", "باقة غير معروفة.");
        }
        return billing.checkout(TenantContext.require(), plan, r.cycle() == null ? "MONTHLY" : r.cycle());
    }

    @GetMapping("/api/billing/invoices")
    List<BillingService.Invoice> invoices() {
        return billing.list();
    }

    @PostMapping("/api/billing/invoices/{id}/confirm")
    BillingService.Invoice confirm(@PathVariable UUID id) {
        return billing.confirm(id);
    }

    @PostMapping("/api/billing/invoices/{id}/mock-pay")
    BillingService.Invoice mockPay(@PathVariable UUID id) {
        if (!app.devToolsEnabled()) {
            throw ApiException.notFound("not_found", "غير متاح.");
        }
        return billing.mockPay(id);
    }

    /**
     * Moyasar's payment notification. The body only tells us which invoice to re-check; the paid status
     * itself always comes from Moyasar's API, so a forged notification cannot mark anything paid.
     */
    @PostMapping("/webhooks/moyasar")
    Map<String, Object> moyasar(@RequestBody JsonNode body) {
        List<String> refs = new ArrayList<>();
        for (JsonNode n : List.of(body.path("id"), body.path("invoice_id"), body.path("data").path("id"),
                body.path("data").path("invoice_id"))) {
            if (n.isTextual()) refs.add(n.asText());
        }
        for (String ref : refs) {
            Optional<UUID[]> hit = billing.officeForProviderRef(ref);
            if (hit.isPresent()) {
                UUID office = hit.get()[0], invoice = hit.get()[1];
                try {
                    TenantContext.run(office, () -> billing.confirm(invoice));
                } catch (RuntimeException e) {
                    log.warn("Moyasar notification for {} not processed: {}", ref, e.getMessage());
                }
                return Map.of("ok", true);
            }
        }
        return Map.of("ok", false);
    }
}
