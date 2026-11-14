package co.edu.corhuila.property.worker.app;

import co.edu.corhuila.property.worker.adapter.in.scheduler.JobRunner;
import co.edu.corhuila.property.worker.adapter.out.booking.ReservasHttpClient;
import co.edu.corhuila.property.worker.application.usecase.ExpireStaleReservations;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Composition root of the worker. Every limit is configuration with a default, read here. */
@SpringBootApplication
@EnableScheduling
public class WorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(WorkerApplication.class, args);
    }

    /** The worker's own service token (svc-worker, issued by Identity): without it, it does not start. */
    static String requireToken(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("SERVICE_TOKEN is required: Booking validates every call of the worker "
                    + "(WORKER_SERVICE_TOKEN in env/<environment>.env)");
        }
        return token;
    }

    @Bean
    JobRunner expireStaleReservations(@Value("${worker.booking-url}") String bookingUrl,
                                      @Value("${worker.service-token}") String token,
                                      @Value("${worker.pending-ttl}") Duration pendingTtl,
                                      @Value("${worker.batch-size}") int batchSize,
                                      @Value("${worker.run-timeout}") Duration runTimeout,
                                      @Value("${worker.http.timeout}") Duration httpTimeout,
                                      @Value("${worker.http.attempts}") int attempts,
                                      @Value("${worker.http.backoff}") Duration backoff) {
        var booking = new ReservasHttpClient(bookingUrl, requireToken(token), httpTimeout, attempts, backoff);
        return new JobRunner(new ExpireStaleReservations(booking, Clock.systemUTC(), pendingTtl, batchSize), runTimeout);
    }
}
