package sa.waslah.agent.billing;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param provider          mock (portal pretends to pay; development and staging) or moyasar
 * @param moyasarSecretKey  sk_test_… or sk_live_…; never commit it
 * @param portalBaseUrl     where Moyasar sends the customer back after paying
 * @param apiBaseUrl        public URL of this backend, for Moyasar's payment notification
 * @param vatPercent        Saudi VAT added on top of plan prices (prices in the pricing doc exclude VAT)
 * @param graceDays         days after a missed renewal before the agent stops
 */
@ConfigurationProperties("waslah.billing")
public record BillingProperties(String provider, String moyasarSecretKey, String portalBaseUrl, String apiBaseUrl,
                                int vatPercent, int graceDays) {

    public boolean moyasar() {
        return "moyasar".equalsIgnoreCase(provider);
    }
}
