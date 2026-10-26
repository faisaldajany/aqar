package sa.waslah.agent.ops;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import sa.waslah.agent.IntegrationTest;

/** Cloud Scheduler entry points: token-protected, every job runnable on demand. */
@TestPropertySource(properties = {"waslah.jobs.token=test-job-token", "waslah.jobs.scheduler=external"})
class JobsTest extends IntegrationTest {

    @Autowired TestRestTemplate rest;

    private int call(String job, String token) {
        HttpHeaders h = new HttpHeaders();
        if (token != null) h.set("X-Job-Token", token);
        return rest.postForEntity("/internal/jobs/" + job, new HttpEntity<>(null, h), Map.class).getStatusCode().value();
    }

    @Test
    void rejectsMissingOrWrongToken() {
        assertThat(call("bookings", null)).isEqualTo(404);
        assertThat(call("bookings", "wrong")).isEqualTo(404);
    }

    @Test
    void runsEveryJob() {
        for (String job : new String[]{"bookings", "daily-report", "license-guard", "billing", "retention", "support-retry"}) {
            assertThat(call(job, "test-job-token")).as(job).isEqualTo(200);
        }
        assertThat(call("nope", "test-job-token")).isEqualTo(404);
    }
}
