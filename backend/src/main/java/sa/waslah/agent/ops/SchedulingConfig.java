package sa.waslah.agent.ops;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * In-process timers (single server, local dev). On Cloud Run, where the app scales to zero, set
 * JOBS_SCHEDULER=external: timers are off and Cloud Scheduler calls {@link JobsController} instead.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "waslah.jobs.scheduler", havingValue = "internal", matchIfMissing = true)
public class SchedulingConfig {
}
