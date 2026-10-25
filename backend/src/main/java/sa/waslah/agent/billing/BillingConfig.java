package sa.waslah.agent.billing;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class BillingConfig {

    @Bean
    PaymentGateway paymentGateway(BillingProperties props) {
        return props.moyasar() ? new MoyasarGateway(props.moyasarSecretKey()) : new MockPaymentGateway(props.portalBaseUrl());
    }
}
