package sa.waslah.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "waslah")
public record WaslahProperties(
        String jwtSecret,
        String tokenKey,
        boolean cookieSecure,
        boolean devToolsEnabled,
        WhatsApp whatsapp,
        Agent agent,
        Llm llm) {

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

    public record Agent(
            boolean enabled,
            int humanPauseHours,
            long debounceMs,
            int historyLimit,
            String handoffTemplate,
            String handoffTemplateLanguage,
            int dailyReplyCap,
            String reminderTemplate,
            String followUpTemplate) {
    }

    public record Llm(
            String provider,
            String apiKey,
            String model,
            String thinkingLevel,
            double temperature,
            int maxOutputTokens,
            int timeoutSeconds) {

        public boolean useGemini() {
            return "gemini".equalsIgnoreCase(provider) && apiKey != null && !apiKey.isBlank();
        }
    }
}
