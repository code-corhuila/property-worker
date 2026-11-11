package co.edu.corhuila.property.worker.adapter.out.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.corhuila.property.worker.adapter.Correlation;
import co.edu.corhuila.property.worker.application.port.out.ReservasApi.Expiry;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/** The client against a fake Booking over real HTTP (JDK HttpServer). */
class ReservasHttpClientTest {

    record Answer(int status, String body) { }

    final Deque<Answer> answers = new ArrayDeque<>();
    final List<String> requests = new ArrayList<>();
    HttpServer server;
    ReservasHttpClient client;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI() + " auth="
                    + exchange.getRequestHeaders().getFirst("Authorization") + " cid="
                    + exchange.getRequestHeaders().getFirst("X-Correlation-Id"));
            Answer a = answers.isEmpty() ? new Answer(200, "{}") : answers.poll();
            byte[] body = a.body().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(a.status(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        client = new ReservasHttpClient("http://127.0.0.1:" + server.getAddress().getPort(), "svc-token",
                Duration.ofSeconds(2), 3, Duration.ofMillis(5));
        MDC.put(Correlation.MDC_KEY, "worker-run-1");
    }

    @AfterEach
    void stop() {
        server.stop(0);
        MDC.remove(Correlation.MDC_KEY);
    }

    @Test
    void listsWithTheQueryOfAdr014TheTokenAndTheCorrelationIdOfTheRun() {
        answers.add(new Answer(200, "{\"data\":[{\"id\":\"r1\"},{\"id\":\"r2\"}],\"meta\":{}}"));
        var ids = client.listPendingCreatedBefore(Instant.parse("2026-11-09T11:45:00.500Z"), 100);
        assertEquals(List.of("r1", "r2"), ids);
        assertEquals("GET /api/v1/reservas?estado=PENDIENTE&limit=100&creadaAntesDe=2026-11-09T11%3A45%3A00Z "
                + "auth=Bearer svc-token cid=worker-run-1", requests.get(0));
    }

    @Test
    void anExpiryAnswered200IsExpired() {
        answers.add(new Answer(200, "{\"estado\":\"EXPIRADA\"}"));
        assertEquals(Expiry.EXPIRED, client.expire("r1"));
        assertTrue(requests.get(0).startsWith("POST /api/v1/reservas/r1/expirar "));
    }

    @Test
    void a422IsNotPendingAndIsNotRetried() {
        answers.add(new Answer(422, "{\"error\":\"INVALID_STATUS_TRANSITION\"}"));
        assertEquals(Expiry.NOT_PENDING, client.expire("r1"));
        assertEquals(1, requests.size());
    }

    @Test
    void a5xxIsRetriedWithinTheAttempts() {
        answers.add(new Answer(503, "{\"error\":\"INTERNAL_ERROR\"}"));
        answers.add(new Answer(200, "{}"));
        assertEquals(Expiry.EXPIRED, client.expire("r1"));
        assertEquals(2, requests.size());
    }

    @Test
    void afterTheAttemptsItThrows() {
        for (int i = 0; i < 3; i++) {
            answers.add(new Answer(503, "{}"));
        }
        assertThrows(IllegalStateException.class, () -> client.expire("r1"));
        assertEquals(3, requests.size());
    }

    @Test
    void aClientErrorIsThrownAtOnce() {
        answers.add(new Answer(400, "{\"error\":\"VALIDATION_ERROR\",\"traceId\":\"t\"}"));
        var e = assertThrows(IllegalStateException.class, () -> client.expire("not-a-uuid"));
        assertTrue(e.getMessage().contains("VALIDATION_ERROR"));
        assertEquals(1, requests.size());
    }
}
