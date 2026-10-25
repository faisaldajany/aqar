package sa.waslah.agent.whatsapp;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import sa.waslah.agent.config.WaslahProperties;

@Configuration
public class WhatsAppConfig {

    @Bean
    WhatsAppClient whatsAppClient(WaslahProperties props, RestClient.Builder builder) {
        if (props.whatsapp().isMock()) {
            return new MockWhatsAppClient();
        }
        return new CloudApiWhatsAppClient(builder, props.whatsapp().graphVersion());
    }

    @Bean
    MetaGraphClient metaGraphClient(WaslahProperties props, RestClient.Builder builder) {
        return new MetaGraphClient(builder, props.whatsapp());
    }
}
