package co.edu.corhuila.property.worker.adapter;

/**
 * The MDC key that carries the X-Correlation-Id of a run. The inbound adapter sets it; the
 * outbound adapters send it on every call. Neither knows the other.
 */
public final class Correlation {

    public static final String MDC_KEY = "correlationId";

    private Correlation() { }
}
