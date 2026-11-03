package sa.waslah.agent.billing;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Development stand-in: the "payment page" is the portal's own mock page, and paying is one API call. */
public class MockPaymentGateway implements PaymentGateway {

    private final String portalBaseUrl;
    private final Set<String> paid = ConcurrentHashMap.newKeySet();
    private final Map<String, Payment> payments = new ConcurrentHashMap<>();
    private final Map<String, String> invoiceStatus = new ConcurrentHashMap<>();
    private final Map<String, Integer> created = new ConcurrentHashMap<>();

    public MockPaymentGateway(String portalBaseUrl) {
        this.portalBaseUrl = portalBaseUrl;
    }

    @Override
    public String name() {
        return "mock";
    }

    @Override
    public Checkout create(long amountHalalas, String description, String invoiceId, String successUrl, String callbackUrl) {
        // A second page for the same invoice (the first one expired) gets its own id, as with Moyasar.
        int n = created.merge(invoiceId, 1, Integer::sum);
        String ref = "mock_" + invoiceId + (n == 1 ? "" : "_" + n);
        return new Checkout(ref, portalBaseUrl + "/billing/pay?invoice=" + invoiceId + (n == 1 ? "" : "&page=" + n));
    }

    @Override
    public boolean isPaid(String providerRef) {
        return paid.contains(providerRef);
    }

    @Override
    public Optional<Payment> payment(String paymentId) {
        return Optional.ofNullable(payments.get(paymentId));
    }

    @Override
    public Optional<Payment> paidPayment(String providerRef) {
        return paid.contains(providerRef)
                ? Optional.of(new Payment("mock_pay_" + providerRef, "paid", 0, 0, providerRef, null, "mada", "1111", "000000000001"))
                : Optional.empty();
    }

    @Override
    public String invoiceStatus(String providerRef) {
        return invoiceStatus.getOrDefault(providerRef, paid.contains(providerRef) ? "paid" : "initiated");
    }

    public void pay(String providerRef) {
        paid.add(providerRef);
    }

    /** Test helpers: what the provider would report for a payment attempt, or for an expired page. */
    public void record(Payment p) {
        payments.put(p.id(), p);
    }

    public void setInvoiceStatus(String providerRef, String status) {
        invoiceStatus.put(providerRef, status);
    }
}
