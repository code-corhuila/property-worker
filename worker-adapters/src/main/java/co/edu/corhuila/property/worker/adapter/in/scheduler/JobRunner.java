package co.edu.corhuila.property.worker.adapter.in.scheduler;

import co.edu.corhuila.property.worker.adapter.Correlation;
import co.edu.corhuila.property.worker.application.port.in.Job;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Runs the job with a fixed delay between runs, so runs never overlap inside one process.
 * One correlation id per run (ADR-014, rule 7): every call to Booking carries it, and so does
 * every log line of the run. A run past RUN_TIMEOUT stops and leaves the rest for the next one.
 */
public class JobRunner {

    private static final Logger LOG = LoggerFactory.getLogger(JobRunner.class);
    private final Job job;
    private final Duration runTimeout;

    public JobRunner(Job job, Duration runTimeout) {
        this.job = job;
        this.runTimeout = runTimeout;
    }

    @Scheduled(fixedDelayString = "${worker.expire-every}", initialDelayString = "${worker.expire-every}")
    public void run() {
        MDC.put(Correlation.MDC_KEY, "worker-" + UUID.randomUUID());
        long start = System.nanoTime();
        try {
            Job.Result r = job.run(Instant.now().plus(runTimeout));
            LOG.info("run finished job={} expired={} rejected={} failed={} durationMs={}", job.name(), r.expired(),
                    r.rejected(), r.failed(), (System.nanoTime() - start) / 1_000_000);
        } catch (RuntimeException e) {
            LOG.error("run failed job={}", job.name(), e);    // e.g. Booking down: the next run tries again
        } finally {
            MDC.remove(Correlation.MDC_KEY);
        }
    }
}
