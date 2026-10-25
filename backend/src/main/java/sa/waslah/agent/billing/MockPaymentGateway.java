package sa.waslah.agent.billing;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Development stand-in: the "payment page" is the portal's own mock page, and paying is one API call. */
public class MockPaymentGateway implements PaymentGateway {

    private final String portalBaseUrl;
    private final Set<String> paid = ConcurrentHashMap.newKeySet();

    public MockPaymentGateway(String portalBaseUrl) {
        this.portalBaseUrl = portalBaseUrl;
    }

    @Override
    public String name() {
        return "mock";
    }

    @Override
    public Checkout create(long amountHalalas, String description, String invoiceId, String successUrl, String callbackUrl) {
        String ref = "mock_" + invoiceId;
        return new Checkout(ref, portalBaseUrl + "/billing/pay?invoice=" + invoiceId);
    }

    @Override
    public boolean isPaid(String providerRef) {
        return paid.contains(providerRef);
    }

    public void pay(String providerRef) {
        paid.add(providerRef);
    }
}
