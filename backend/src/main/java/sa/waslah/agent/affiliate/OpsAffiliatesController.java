package sa.waslah.agent.affiliate;

import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import sa.waslah.agent.admin.OpsAuth;
import sa.waslah.agent.common.ClientIp;

/** The team's console for marketers: add them, set their rate, see their clients and earnings, record payouts. */
@RestController
public class OpsAffiliatesController {

    private final OpsAuth ops;
    private final AffiliateService affiliates;
    private final JdbcClient jdbc;

    public OpsAffiliatesController(OpsAuth ops, AffiliateService affiliates, JdbcClient jdbc) {
        this.ops = ops;
        this.affiliates = affiliates;
        this.jdbc = jdbc;
    }

    record Payout(String reference, String note) {
    }

    @GetMapping("/api/ops/affiliates")
    List<Map<String, Object>> list(HttpServletRequest http) {
        ops.require(http);
        return affiliates.list();
    }

    @GetMapping("/api/ops/affiliates/options")
    List<Map<String, Object>> options(HttpServletRequest http) {
        ops.require(http);
        return affiliates.options();
    }

    /** Adds a marketer and sends the invitation. */
    @PostMapping("/api/ops/affiliates")
    Map<String, Object> create(HttpServletRequest http, @RequestBody AffiliateService.NewAffiliate n) {
        ops.require(http);
        UUID id = affiliates.create(n);
        AffiliateService.Invite invite = affiliates.invite(id);
        audit(http, "AFFILIATE_CREATED", id, n.fullName() + " · " + n.commissionPercent().stripTrailingZeros().toPlainString() + "%");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("inviteUrl", invite.url());
        m.put("emailed", invite.emailed());
        return m;
    }

    @GetMapping("/api/ops/affiliates/{id}")
    Map<String, Object> page(HttpServletRequest http, @PathVariable UUID id) {
        ops.require(http);
        return affiliates.page(id, true);
    }

    @PatchMapping("/api/ops/affiliates/{id}")
    Map<String, Object> update(HttpServletRequest http, @PathVariable UUID id, @RequestBody AffiliateService.Changes c) {
        ops.require(http);
        affiliates.update(id, c);
        StringBuilder what = new StringBuilder();
        if (c != null && c.commissionPercent() != null) what.append("rate ").append(c.commissionPercent().stripTrailingZeros().toPlainString()).append("% ");
        if (c != null && c.active() != null) what.append(c.active() ? "on " : "off ");
        audit(http, "AFFILIATE_UPDATED", id, what.toString().trim());
        return affiliates.page(id, true);
    }

    @PostMapping("/api/ops/affiliates/{id}/invite")
    AffiliateService.Invite invite(HttpServletRequest http, @PathVariable UUID id) {
        ops.require(http);
        AffiliateService.Invite i = affiliates.invite(id);
        audit(http, "AFFILIATE_INVITE", id, i.emailed() ? "emailed" : "shown");
        return i;
    }

    @PostMapping("/api/ops/affiliates/{id}/payouts")
    Map<String, Object> payout(HttpServletRequest http, @PathVariable UUID id, @RequestBody(required = false) Payout p) {
        ops.require(http);
        Map<String, Object> out = affiliates.payout(id, p == null ? null : p.reference(), p == null ? null : p.note());
        audit(http, "AFFILIATE_PAYOUT", id, out.get("amountHalalas") + " halalas" + (p != null && p.reference() != null ? " · " + p.reference() : ""));
        return out;
    }

    private void audit(HttpServletRequest http, String action, UUID affiliate, String detail) {
        jdbc.sql("insert into ops_audit (action, office_id, target, detail, ip_hash) values (?, null, ?, ?, ?)")
                .params(action, affiliate.toString(), detail == null ? null : detail.length() > 500 ? detail.substring(0, 500) : detail,
                        ClientIp.hash(http, "ops"))
                .update();
    }
}
