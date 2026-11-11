package co.edu.corhuila.property.worker.adapter.out.booking;

import co.edu.corhuila.property.worker.adapter.Correlation;
import co.edu.corhuila.property.worker.application.port.out.ReservasApi;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.MDC;

/**
 * Booking's public API (E-02, E-19) with the worker's service token: never its schema
 * (ADR-014, rule 8). Bounded retries with exponential backoff and full jitter, only on what a
 * retry can fix: network errors, 429 and 5xx (rule 5). A 422 of E-19 is an answer, not an error.
 */
public class ReservasHttpClient implements ReservasApi {

    private final String base;
    private final String token;
    private final Duration timeout;
    private final int attempts;
    private final Duration backoff;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();

    public ReservasHttpClient(String baseUrl, String token, Duration timeout, int attempts, Duration backoff) {
        this.base = baseUrl;
        this.token = token;
        this.timeout = timeout;
        this.attempts = attempts;
        this.backoff = backoff;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    public List<String> listPendingCreatedBefore(Instant before, int limit) {
        String query = "?estado=PENDIENTE&limit=" + limit + "&creadaAntesDe="
                + URLEncoder.encode(before.truncatedTo(ChronoUnit.SECONDS).toString(), StandardCharsets.UTF_8);
        List<String> ids = new ArrayList<>();
        for (JsonNode reserva : json(call("GET", "/api/v1/reservas" + query)).path("data")) {
            ids.add(reserva.get("id").asText());
        }
        return ids;
    }

    @Override
    public Expiry expire(String reservaId) {
        HttpResponse<String> resp = call("POST", "/api/v1/reservas/" + URLEncoder.encode(reservaId, StandardCharsets.UTF_8)
                + "/expirar");
        return resp.statusCode() == 422 ? Expiry.NOT_PENDING : Expiry.EXPIRED;
    }

    /** Returns a 2xx or a 422; throws on any other answer, after the retries that apply. */
    private HttpResponse<String> call(String method, String path) {
        RuntimeException last = null;
        for (int attempt = 0; attempt < attempts; attempt++) {
            if (attempt > 0) {
                sleep(ThreadLocalRandom.current().nextLong(backoff.toMillis() << attempt));  // full jitter
            }
            HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(base + path)).timeout(timeout)
                    .header("Authorization", "Bearer " + token)
                    .method(method, HttpRequest.BodyPublishers.noBody());
            String correlationId = MDC.get(Correlation.MDC_KEY);
            if (correlationId != null) {
                req.header("X-Correlation-Id", correlationId);
            }
            try {
                HttpResponse<String> resp = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
                int status = resp.statusCode();
                if (status < 400 || status == 422) {
                    return resp;
                }
                JsonNode envelope = jsonQuietly(resp.body());
                var error = new IllegalStateException("%s %s: status %d %s (traceId %s)".formatted(method, path,
                        status, envelope.path("error").asText(), envelope.path("traceId").asText()));
                if (status != 429 && status < 500) {
                    throw error;                        // the request is wrong: a retry gives the same
                }
                last = error;
            } catch (IOException e) {
                last = new IllegalStateException(method + " " + path + ": " + e.getMessage(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new IllegalStateException("after " + attempts + " attempts", last);
    }

    private JsonNode json(HttpResponse<String> resp) {
        try {
            return json.readTree(resp.body());
        } catch (IOException e) {
            throw new IllegalStateException("Booking answered a body that is not JSON", e);
        }
    }

    private JsonNode jsonQuietly(String body) {
        try {
            return json.readTree(body);
        } catch (IOException e) {
            return json.createObjectNode();
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
