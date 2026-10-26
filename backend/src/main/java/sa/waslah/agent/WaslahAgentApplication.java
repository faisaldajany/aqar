package sa.waslah.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class WaslahAgentApplication {
    public static void main(String[] args) {
        SpringApplication.run(WaslahAgentApplication.class, args);
    }
}
