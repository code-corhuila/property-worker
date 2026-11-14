package co.edu.corhuila.property.worker.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** ADR-014, rule 6: the worker calls Booking with its own token; without it, it does not start. */
class WorkerApplicationTest {

    @Test
    void withoutAServiceTokenTheWorkerDoesNotStartAndSaysWhy() {
        for (String token : new String[] {null, "", "  "}) {
            var e = assertThrows(IllegalStateException.class, () -> WorkerApplication.requireToken(token));
            assertTrue(e.getMessage().contains("SERVICE_TOKEN is required"));
        }
        assertEquals("a.b.c", WorkerApplication.requireToken("a.b.c"));
    }
}
