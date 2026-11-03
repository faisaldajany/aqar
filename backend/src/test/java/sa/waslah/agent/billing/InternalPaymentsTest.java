package sa.waslah.agent.billing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import sa.waslah.agent.IntegrationTest;
import sa.waslah.agent.auth.AuthService;

/** Offices whose owner confirmed a company (hidaaco.com) email pay through Moyasar test mode; everyone else, the main gateway. */
class InternalPaymentsTest extends IntegrationTest {

    @Autowired AuthService auth;
    @Autowired JdbcClient jdbc;

    static final class Stub implements PaymentGateway {
        final String name;
        final Set<String> paid = new HashSet<>();

        Stub(String name) {
            this.name = name;
        }

        public String name() {
            return name;
        }

        public Checkout create(long amount, String description, String invoiceId, String successUrl, String callbackUrl) {
            return new Checkout(name + "-" + invoiceId, "https://pay.example/" + name);
        }

        public boolean isPaid(String ref) {
            return paid.contains(ref);
        }
    }

    UUID office(String email) {
        return auth.signup(new AuthService.Signup("مكتب " + email, "1100012345", "فيصل", email, "password123")).officeId();
    }

    @Test
    void onlyAConfirmedCompanyEmailMakesAnOfficeInternal() {
        Stub live = new Stub("moyasar"), test = new Stub(PaymentGateways.TEST);
        PaymentGateways gateways = new PaymentGateways(live, test, jdbc, "hidaaco.com");
        String tag = UUID.randomUUID().toString().substring(0, 8);

        UUID customer = office(tag + "@gmail.com");
        assertThat(gateways.mode(customer)).isEqualTo(PaymentGateways.Mode.LIVE);
        assertThat(gateways.forOffice(customer)).isSameAs(live);

        UUID team = office("faisal-" + tag + "@HidaaCo.com");
        assertThat(gateways.mode(team)).as("typing the domain is not enough").isEqualTo(PaymentGateways.Mode.INTERNAL_UNCONFIRMED);
        assertThat(gateways.forOffice(team)).isSameAs(live);

        jdbc.sql("update users set email_confirmed_at = now() where office_id = ?").param(team).update();
        assertThat(gateways.mode(team)).isEqualTo(PaymentGateways.Mode.TEST);
        assertThat(gateways.forOffice(team)).isSameAs(test);

        assertThat(new PaymentGateways(live, null, jdbc, "hidaaco.com").forOffice(team))
                .as("no test key configured: the main gateway").isSameAs(live);
        assertThat(gateways.internalEmail("Someone@hidaaco.com")).isTrue();
        assertThat(gateways.internalEmail("someone@hidaaco.com.evil.sa")).isFalse();
    }

    @Test
    void paymentIsCheckedWithTheGatewayThatCreatedTheInvoice() {
        Stub live = new Stub("moyasar"), test = new Stub(PaymentGateways.TEST);
        PaymentGateways gateways = new PaymentGateways(live, test, jdbc, "hidaaco.com");
        test.paid.add("t-1");
        live.paid.add("l-1");
        assertThat(gateways.isPaid(PaymentGateways.TEST, "t-1")).isTrue();
        assertThat(gateways.isPaid("moyasar", "t-1")).as("a test payment never counts as live").isFalse();
        assertThat(gateways.isPaid("moyasar", "l-1")).isTrue();
        assertThat(gateways.isPaid("mock", "l-1")).as("an old mock invoice is not paid on Moyasar").isFalse();
        assertThat(new PaymentGateways(live, null, jdbc, "hidaaco.com").isPaid(PaymentGateways.TEST, "t-1")).isFalse();
    }
}
