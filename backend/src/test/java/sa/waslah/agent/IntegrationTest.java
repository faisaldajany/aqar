package sa.waslah.agent;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Boots the app against a real Postgres. Flyway migrates as the container superuser; the app
 * connects as waslah_app (not superuser, not owner), exactly as in production, so RLS is live.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "waslah.whatsapp.mode=mock",
                "waslah.whatsapp.app-secret=test-app-secret",
                "waslah.whatsapp.verify-token=test-verify-token",
                "waslah.llm.provider=scripted",
                "waslah.agent.enabled=false",
                "waslah.agent.debounce-ms=0",
                "waslah.booking.job-initial-delay-ms=86400000"
        })
public abstract class IntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16").withInitScript("db/test-init.sql");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", () -> "waslah_app");
        r.add("spring.datasource.password", () -> "waslah_app");
        r.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        r.add("spring.flyway.user", POSTGRES::getUsername);
        r.add("spring.flyway.password", POSTGRES::getPassword);
    }
}
