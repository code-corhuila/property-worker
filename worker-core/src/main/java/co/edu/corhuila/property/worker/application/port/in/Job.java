package co.edu.corhuila.property.worker.application.port.in;

import java.time.Instant;

/** A scheduled job of the worker (ADR-014). */
public interface Job {

    /**
     * One run's summary. {@code expired}: Booking answered 200 (also when it was already
     * EXPIRADA); {@code rejected}: 422, the Reserva left PENDIENTE meanwhile, counted and not
     * retried; {@code failed}: anything else, left for the next run. A job reports partial
     * failure; it does not throw on it.
     */
    record Result(int expired, int rejected, int failed) { }

    String name();

    /** Past the deadline the job stops and leaves the rest for the next run. */
    Result run(Instant deadline);
}
