package sa.waslah.agent.billing;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param provider          mock (portal pretends to pay; development and staging) or moyasar
 * @param moyasarSecretKey  sk_test_… or sk_live_…; never commit it
 * @param portalBaseUrl     where Moyasar sends the customer back after paying
 * @param apiBaseUrl        public URL of this backend, for Moyasar's payment notification
 * @param vatPercent        Saudi VAT added on top of plan prices (prices in the pricing doc exclude VAT)
 * @param graceDays         days after a missed renewal before the agent stops
 * @param moyasarTestSecretKey sk_test_… used for internal offices (see {@link PaymentGateways}); blank = none
 * @param internalEmailDomain  the company's email domain; offices whose owner confirmed an address on it are internal
 * @param moyasarWebhookSecret the Secret Token typed in Moyasar's dashboard webhook; blank = notifications are not
 *                             checked (they are harmless either way: the paid status is always asked from Moyasar)
 */
@ConfigurationProperties("waslah.billing")
public record BillingProperties(String provider, String moyasarSecretKey, String portalBaseUrl, String apiBaseUrl,
                                int vatPercent, int graceDays, String moyasarTestSecretKey, String internalEmailDomain,
                                String moyasarWebhookSecret) {

    public boolean moyasar() {
        return "moyasar".equalsIgnoreCase(provider);
    }
}
