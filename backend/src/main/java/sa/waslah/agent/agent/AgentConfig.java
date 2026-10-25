package sa.waslah.agent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestClient;
import sa.waslah.agent.agent.llm.GeminiClient;
import sa.waslah.agent.agent.llm.Llm;
import sa.waslah.agent.agent.llm.ScriptedLlmClient;
import sa.waslah.agent.config.WaslahProperties;

@Configuration
@EnableAsync
public class AgentConfig {

    private static final Logger log = LoggerFactory.getLogger(AgentConfig.class);

    @Bean
    Llm.Client llmClient(WaslahProperties props, RestClient.Builder builder, ObjectMapper json) {
        WaslahProperties.Llm c = props.llm();
        if (c.useGemini()) {
            log.info("Agent model: {} (thinking={})", c.model(), c.thinkingLevel());
            return new GeminiClient(builder, json, new GeminiClient.Settings(c.apiKey(), c.model(), c.thinkingLevel(),
                    c.temperature(), c.maxOutputTokens(), Duration.ofSeconds(c.timeoutSeconds())));
        }
        log.warn("No model API key configured: the agent answers with a fixed development reply.");
        return new ScriptedLlmClient();
    }

    @Bean
    AgentBrain agentBrain(Llm.Client client, ObjectMapper json) {
        return new AgentBrain(client, json);
    }

    @Bean(name = "agentExecutor")
    ThreadPoolTaskExecutor agentExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(4);
        ex.setMaxPoolSize(16);
        ex.setQueueCapacity(500);
        ex.setThreadNamePrefix("agent-");
        ex.initialize();
        return ex;
    }
}
