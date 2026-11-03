package sa.waslah.agent.billing;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import sa.waslah.agent.common.ApiException;
import sa.waslah.agent.common.RateLimiter;
import sa.waslah.agent.tenant.TenantContext;

@RestController
public class BillingController {

    private static final Logger log = LoggerFactory.getLogger(BillingController.class);

    public record CheckoutRequest(String plan, String cycle) {
    }

    public record PromoRequest(String code) {
    }

    private final BillingService billing;
    private final Subscription subscription;
    private final PlanLimits plans;
    private final BillingProperties props;
    private final sa.waslah.agent.config.WaslahProperties app;
    private final PromoService promos;
    private final RateLimiter limits;
    private final PaymentGateways gateways;
    private final sa.waslah.agent.auth.EmailConfirmationService confirmations;
    private final MoyasarEvents events;

    public BillingController(BillingService billing, Subscription subscription, PlanLimits plans, BillingProperties props,
                             sa.waslah.agent.config.WaslahProperties app, PromoService promos, RateLimiter limits,
                             PaymentGateways gateways, sa.waslah.agent.auth.EmailConfirmationService confirmations,
                             MoyasarEvents events) {
        this.events = events;
        this.limits = limits;
        this.gateways = gateways;
        this.confirmations = confirmations;
        this.app = app;
        this.promos = promos;
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
        // TEST: internal office, pays with Moyasar test cards. INTERNAL_UNCONFIRMED: owner email on the company domain,
        // not confirmed yet. LIVE: everyone else.
        PaymentGateways.Mode mode = gateways.mode(TenantContext.require());
        out.put("paymentMode", mode == PaymentGateways.Mode.TEST && !gateways.testModeAvailable() ? "LIVE" : mode.name());
        out.put("serving", s.serving(now));
        out.put("vatPercent", props.vatPercent());
        out.put("provider", props.moyasar() ? "moyasar" : "mock");
        out.put("mockPayments", !props.moyasar() && app.devToolsEnabled());
        out.put("suspended", s.suspendedAt() != null);
        Optional<PromoService.Applied> promo = promos.current();
        if (promo.isPresent()) {
            PromoService.Applied a = promo.get();
            Map<String, Object> pm = new LinkedHashMap<>();
            pm.put("code", a.promo().code());
            pm.put("kind", a.promo().kind());
            pm.put("summary", a.promo().summary());
            pm.put("percentOff", a.promo().percentOff());
            pm.put("amountOffSar", a.promo().amountOffHalalas() == null ? null : BillingService.sar(a.promo().amountOffHalalas()));
            pm.put("durationPeriods", a.promo().durationPeriods());
            pm.put("periodsUsed", a.periodsUsed());
            pm.put("remainingPeriods", a.remainingPeriods());
            pm.put("usedUp", a.usedUp());
            out.put("promo", pm);
        } else {
            out.put("promo", null);
        }
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
            // Prices after the office's discount, when it applies to this plan and cycle; null otherwise.
            m.put("discountedMonthlySar", discounted(promo, p, "MONTHLY", p.monthlySar));
            m.put("discountedAnnualSar", discounted(promo, p, "ANNUAL", p.annualSar));
            list.add(m);
        }
        out.put("plans", list);
        return out;
    }

    /** Sends (again) the link that confirms the signed-in user's company email. 3 an hour. */
    @PostMapping("/api/billing/confirm-email")
    Map<String, Object> resendConfirmation(@org.springframework.security.core.annotation.AuthenticationPrincipal
                                           org.springframework.security.oauth2.jwt.Jwt jwt) {
        UUID user = UUID.fromString(jwt.getSubject());
        limits.check("confirm-send:" + user, 3, Duration.ofHours(1));
        boolean sent = confirmations.send(user);
        return Map.of("sent", sent);
    }

    /** 10 tries an hour per office, so codes cannot be guessed by trying many. */
    @PostMapping("/api/billing/promo")
    Map<String, Object> applyPromo(@RequestBody PromoRequest r) {
        limits.check("promo:" + TenantContext.require(), 10, Duration.ofHours(1));
        PromoService.Promo p = promos.apply(r == null ? null : r.code());
        Map<String, Object> out = state();
        out.put("applied", p.summary());
        return out;
    }

    @DeleteMapping("/api/billing/promo")
    Map<String, Object> removePromo() {
        promos.remove();
        return state();
    }

    private String discounted(Optional<PromoService.Applied> promo, PlanLimits.Plan plan, String cycle, java.math.BigDecimal price) {
        if (promo.isEmpty()) return null;
        long gross = BillingService.halalas(price);
        long off = promos.discountHalalas(promo.get(), plan, cycle, gross);
        return off == 0 ? null : BillingService.sar(gross - off);
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

    /** The printable receipt (or a due invoice) in Hidaa's design. */
    @GetMapping("/api/billing/invoices/{id}/receipt")
    Map<String, Object> receipt(@PathVariable UUID id) {
        return billing.receipt(id);
    }

    /** «ادفع»: the invoice's payment page, renewed if it expired. */
    @PostMapping("/api/billing/invoices/{id}/pay")
    BillingService.Invoice pay(@PathVariable UUID id) {
        return billing.payPage(id);
    }

    /**
     * Moyasar's notifications: each invoice's own callback, and the dashboard webhook's events (see {@link MoyasarEvents}).
     * With MOYASAR_WEBHOOK_SECRET set, a notification without the right token is turned away (see {@link MoyasarWebhookAuth}).
     */
    @PostMapping("/webhooks/moyasar")
    ResponseEntity<Map<String, Object>> moyasar(@RequestBody JsonNode body,
                                                @RequestParam(name = "token", required = false) String token) {
        if (!MoyasarWebhookAuth.allowed(props.moyasarWebhookSecret(), body, token)) {
            log.warn("Moyasar notification turned away: missing or wrong secret token");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("ok", false));
        }
        boolean authenticated = MoyasarWebhookAuth.configured(props.moyasarWebhookSecret());
        return ResponseEntity.ok(events.handle(body, authenticated));
    }
}
