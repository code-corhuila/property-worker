package co.edu.corhuila.property.worker.application.usecase;

import co.edu.corhuila.property.worker.application.port.in.Job;
import co.edu.corhuila.property.worker.application.port.out.ReservasApi;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.logging.Logger;

/**
 * The job expire-stale-reservations (ADR-014; HU-BOOK-005): every Reserva left PENDIENTE
 * longer than PENDING_TTL is expired through Booking's API. Booking owns the transition and
 * writes ReservaExpirada to its outbox; the worker only triggers it. A run reads at most
 * BATCH_SIZE reservations and stops at its deadline: what is left waits for the next run.
 */
public class ExpireStaleReservations implements Job {

    /** The largest page E-02 serves (07-api/guidelines.md, D-C3; clarified in ADR-014). */
    public static final int MAX_BATCH_SIZE = 100;
    private static final Logger LOG = Logger.getLogger(ExpireStaleReservations.class.getName());

    private final ReservasApi reservas;
    private final Clock clock;
    private final Duration pendingTtl;
    private final int batchSize;

    public ExpireStaleReservations(ReservasApi reservas, Clock clock, Duration pendingTtl, int batchSize) {
        if (batchSize < 1 || batchSize > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("BATCH_SIZE must be between 1 and " + MAX_BATCH_SIZE + " (E-02)");
        }
        if (pendingTtl.isNegative() || pendingTtl.isZero()) {
            throw new IllegalArgumentException("PENDING_TTL must be positive");
        }
        this.reservas = reservas;
        this.clock = clock;
        this.pendingTtl = pendingTtl;
        this.batchSize = batchSize;
    }

    @Override
    public String name() {
        return "expire-stale-reservations";
    }

    @Override
    public Result run(Instant deadline) {
        int expired = 0;
        int rejected = 0;
        int failed = 0;
        for (String id : reservas.listPendingCreatedBefore(clock.instant().minus(pendingTtl), batchSize)) {
            if (clock.instant().isAfter(deadline)) {
                break;                      // RUN_TIMEOUT: the rest waits for the next run
            }
            try {
                switch (reservas.expire(id)) {
                    case EXPIRED -> expired++;
                    case NOT_PENDING -> rejected++;     // counted, never retried (scenario 3)
                }
            } catch (RuntimeException e) {             // one failure does not stop the batch
                failed++;
                LOG.warning("expire reserva " + id + ": " + e.getMessage());
            }
        }
        return new Result(expired, rejected, failed);
    }
}
