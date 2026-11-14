package co.edu.corhuila.property.worker.application.port.out;

import java.time.Instant;
import java.util.List;

/**
 * Booking as the worker sees it: its public API, never its schema (ADR-014, rule 8).
 * Every call carries the worker's own service token.
 */
public interface ReservasApi {

    /** What Booking answered to an expiry. */
    enum Expiry {
        /** 200: now EXPIRADA, or it already was (the job is safe to run twice). */
        EXPIRED,
        /** 422 INVALID_STATUS_TRANSITION: it was CONFIRMADA or CANCELADA meanwhile. */
        NOT_PENDING
    }

    /** E-02: at most {@code limit} PENDIENTE reservations created before that instant. */
    List<String> listPendingCreatedBefore(Instant before, int limit);

    /** E-19. Any answer other than 200 or 422 throws, after the bounded retries. */
    Expiry expire(String reservaId);
}
