package sa.waslah.agent.report;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("waslah.report")
public record ReportProperties(String template, String templateLanguage) {
}
