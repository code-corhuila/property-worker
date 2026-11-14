package co.edu.corhuila.property.worker.application.usecase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import co.edu.corhuila.property.worker.application.port.in.Job;
import co.edu.corhuila.property.worker.application.port.out.ReservasApi;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The four scenarios of HU-BOOK-005, with Booking faked behind the port. */
class ExpireStaleReservationsTest {

    static final Instant NOW = Instant.parse("2026-11-09T12:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    static final Duration TTL = Duration.ofMinutes(15);

    /** Booking: the stale ids it lists, and what it answers to each expiry. */
    static class FakeBooking implements ReservasApi {
        final List<String> stale = new ArrayList<>();
        final Map<String, Object> answers = new HashMap<>();
        final List<String> expireCalls = new ArrayList<>();
        Instant askedBefore;
        int askedLimit;

        @Override
        public List<String> listPendingCreatedBefore(Instant before, int limit) {
            askedBefore = before;
            askedLimit = limit;
            return stale.stream().limit(limit).toList();
        }

        @Override
        public Expiry expire(String reservaId) {
            expireCalls.add(reservaId);
            Object answer = answers.getOrDefault(reservaId, Expiry.EXPIRED);
            if (answer instanceof RuntimeException e) {
                throw e;
            }
            return (Expiry) answer;
        }
    }

    final FakeBooking booking = new FakeBooking();

    Job.Result run(int batchSize) {
        return new ExpireStaleReservations(booking, CLOCK, TTL, batchSize).run(NOW.plusSeconds(50));
    }

    @Test
    void scenario1ExpiresWhatIsPendingLongerThanTheTtl() {
        booking.stale.addAll(List.of("r1", "r2"));
        assertEquals(new Job.Result(2, 0, 0), run(100));
        assertEquals(NOW.minus(TTL), booking.askedBefore);
        assertEquals(List.of("r1", "r2"), booking.expireCalls);
    }

    @Test
    void scenario2ExpiringOneAlreadyExpiredIsJustAnotherSuccess() {
        booking.stale.add("r1");
        run(100);
        assertEquals(new Job.Result(1, 0, 0), run(100), "Booking answers 200 again; nothing changes");
    }

    @Test
    void scenario3ConfirmedMeanwhileIsCountedAndNotRetried() {
        booking.stale.addAll(List.of("r1", "r2"));
        booking.answers.put("r1", ReservasApi.Expiry.NOT_PENDING);
        assertEquals(new Job.Result(1, 1, 0), run(100));
        assertEquals(List.of("r1", "r2"), booking.expireCalls, "one call each: no retry of the 422");
    }

    @Test
    void scenario4AtMostBatchSizePerRunTheRestWaits() {
        for (int i = 0; i < 7; i++) {
            booking.stale.add("r" + i);
        }
        assertEquals(new Job.Result(3, 0, 0), run(3));
        assertEquals(3, booking.askedLimit);
    }

    @Test
    void scenario4StopsAtTheRunTimeout() {
        booking.stale.addAll(List.of("r1", "r2"));
        var job = new ExpireStaleReservations(booking, CLOCK, TTL, 100);
        assertEquals(new Job.Result(0, 0, 0), job.run(NOW.minusSeconds(1)));
    }

    @Test
    void aFailureOnOneReservationDoesNotStopTheBatch() {
        booking.stale.addAll(List.of("r1", "r2", "r3"));
        booking.answers.put("r2", new IllegalStateException("503 after 3 attempts"));
        assertEquals(new Job.Result(2, 0, 1), run(100));
    }

    @Test
    void theBatchSizeIsAtMostTheLimitOfE02() {
        assertThrows(IllegalArgumentException.class, () -> new ExpireStaleReservations(booking, CLOCK, TTL, 101));
        assertThrows(IllegalArgumentException.class, () -> new ExpireStaleReservations(booking, CLOCK, TTL, 0));
        assertThrows(IllegalArgumentException.class, () -> new ExpireStaleReservations(booking, CLOCK, Duration.ZERO, 10));
    }
}
