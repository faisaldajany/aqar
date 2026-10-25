package sa.waslah.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "waslah")
public record WaslahProperties(
        String jwtSecret,
        String tokenKey,
        boolean cookieSecure,
        boolean devToolsEnabled,
        WhatsApp whatsapp,
        Agent agent) {

    public record WhatsApp(
            String mode,
            String graphVersion,
            String appId,
            String appSecret,
            String verifyToken,
            String configId,
            String registrationPin) {

        public boolean isMock() {
            return !"cloud".equalsIgnoreCase(mode);
        }
    }

    public record Agent(int humanPauseHours) {
    }
}
