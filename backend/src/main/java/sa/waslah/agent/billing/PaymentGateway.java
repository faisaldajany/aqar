package sa.waslah.agent.billing;

/** A hosted payment page for one invoice. The customer pays there; we only ever trust a server-side status check. */
public interface PaymentGateway {

    record Checkout(String providerRef, String url) {
    }

    String name();

    Checkout create(long amountHalalas, String description, String invoiceId, String successUrl, String callbackUrl);

    /** Asks the provider (never the browser) whether the hosted invoice has been paid. */
    boolean isPaid(String providerRef);
}
