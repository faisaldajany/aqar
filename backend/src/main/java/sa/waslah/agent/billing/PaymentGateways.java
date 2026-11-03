package sa.waslah.agent.billing;

import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Which payment gateway an office pays through. Internal offices (the Hidaa team's own test accounts) use Moyasar test
 * mode, so the team can try the whole subscription flow with test cards; every other office uses the main gateway
 * (Moyasar live in production). An office is internal when an owner's email is on the company domain AND that owner
 * confirmed it from a link sent to the address, so nobody can get test payments by typing a hidaaco.com email.
 * Invoices record the gateway's name, and their payment is always checked with that same gateway.
 */
@Component
public class PaymentGateways {

    public static final String TEST = "moyasar_test";

    public enum Mode {
        /** The main gateway. */
        LIVE,
        /** Owner email on the company domain, not confirmed yet: still the main gateway. */
        INTERNAL_UNCONFIRMED,
        /** Confirmed internal office: Moyasar test mode. */
        TEST
    }

    private final PaymentGateway main;
    private final PaymentGateway test;
    private final JdbcClient jdbc;
    private final String domain;

    @Autowired
    public PaymentGateways(PaymentGateway main, BillingProperties props, JdbcClient jdbc) {
        this(main, props.moyasarTestSecretKey() == null || props.moyasarTestSecretKey().isBlank()
                ? null : MoyasarGateway.testMode(props.moyasarTestSecretKey()), jdbc, props.internalEmailDomain());
    }

    PaymentGateways(PaymentGateway main, PaymentGateway test, JdbcClient jdbc, String domain) {
        this.main = main;
        this.test = test;
        this.jdbc = jdbc;
        this.domain = domain == null || domain.isBlank() ? "" : domain.trim().toLowerCase(Locale.ROOT);
    }

    public PaymentGateway main() {
        return main;
    }

    /** The gateway for an office: test mode for a confirmed internal office (when a test key is set), else the main one. */
    public PaymentGateway forOffice(UUID officeId) {
        return test != null && mode(officeId) == Mode.TEST ? test : main;
    }

    public Mode mode(UUID officeId) {
        if (domain.isEmpty()) return Mode.LIVE;
        record Owner(boolean confirmed) {
        }
        var owners = jdbc.sql("""
                        select email_confirmed_at is not null from users
                        where office_id = ? and role = 'OWNER' and disabled_at is null and lower(email) like ?""")
                .params(officeId, "%@" + domain)
                .query((rs, n) -> new Owner(rs.getBoolean(1))).list();
        if (owners.isEmpty()) return Mode.LIVE;
        return owners.stream().anyMatch(Owner::confirmed) ? Mode.TEST : Mode.INTERNAL_UNCONFIRMED;
    }

    /** Whether an email is on the company domain. */
    public boolean internalEmail(String email) {
        return !domain.isEmpty() && email != null && email.trim().toLowerCase(Locale.ROOT).endsWith("@" + domain);
    }

    public boolean testModeAvailable() {
        return test != null;
    }

    /** Asks the gateway that created the invoice whether it is paid; false for a gateway no longer configured. */
    public boolean isPaid(String provider, String providerRef) {
        PaymentGateway g = TEST.equals(provider) ? test : main;
        if (g == null || !g.name().equals(provider)) return false;
        return g.isPaid(providerRef);
    }
}
