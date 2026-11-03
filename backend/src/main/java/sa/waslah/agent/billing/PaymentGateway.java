package sa.waslah.agent.billing;

import java.util.Optional;

/** A hosted payment page for one invoice. The customer pays there; we only ever trust a server-side status check. */
public interface PaymentGateway {

    record Checkout(String providerRef, String url) {
    }

    /**
     * One payment attempt on a hosted invoice, as the provider reports it.
     *
     * @param status        paid, failed, refunded, voided, …
     * @param refundedHalalas how much of it was refunded so far
     * @param invoiceRef    the hosted invoice it was made on (our provider_ref)
     * @param message       the provider's reason, e.g. "INSUFFICIENT_FUNDS" or "Card declined"
     */
    record Payment(String id, String status, long amountHalalas, long refundedHalalas, String invoiceRef, String message) {
    }

    String name();

    Checkout create(long amountHalalas, String description, String invoiceId, String successUrl, String callbackUrl);

    /** Asks the provider (never the browser) whether the hosted invoice has been paid. */
    boolean isPaid(String providerRef);

    /** A payment by its id, from the provider; empty if unknown. */
    default Optional<Payment> payment(String paymentId) {
        return Optional.empty();
    }

    /** The hosted invoice's status from the provider (initiated, paid, expired, canceled, …), or null if unknown. */
    default String invoiceStatus(String providerRef) {
        return null;
    }
}
