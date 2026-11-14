package co.edu.corhuila.property.booking.adapter.out.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.corhuila.property.booking.application.port.out.ReservaRepository.Created;
import co.edu.corhuila.property.booking.application.port.out.ReservaRepository.Filter;
import co.edu.corhuila.property.booking.application.port.out.ReservaSuperpuestaException;
import co.edu.corhuila.property.booking.application.usecase.ReservaEvents;
import co.edu.corhuila.property.booking.domain.model.DateRange;
import co.edu.corhuila.property.booking.domain.model.EstadoReserva;
import co.edu.corhuila.property.booking.domain.model.InvalidStatusTransitionException;
import co.edu.corhuila.property.booking.domain.model.Money;
import co.edu.corhuila.property.booking.domain.model.Reserva;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs against the REAL engine with the schema of property-booking-db applied, as
 * booking_app: it also proves the grants are enough. Skipped without
 * TEST_DATABASE_URL (Annex C); scripts/test-db.sh prepares one.
 */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class JdbcReservaRepositoryIntegrationTest {

    JdbcReservaRepository repository;
    JdbcTemplate jdbc;
    UUID propiedad;

    @BeforeEach
    void connect() {
        var dataSource = new DriverManagerDataSource(System.getenv("TEST_DATABASE_URL"),
                System.getenv("TEST_DATABASE_USER"), System.getenv("TEST_DATABASE_PASSWORD"));
        jdbc = new JdbcTemplate(dataSource);
        repository = new JdbcReservaRepository(jdbc, new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
        propiedad = UUID.randomUUID();
    }

    Reserva reserva(String inicio, String fin) {
        return Reserva.crear(propiedad, UUID.randomUUID(), new DateRange(LocalDate.parse(inicio), LocalDate.parse(fin)),
                new Money(250000, "COP"));
    }

    Created create(Reserva r, String key) {
        return repository.create(r, key, ReservaEvents.of(r, "it-trace"));
    }

    static String key() {
        return "it-" + UUID.randomUUID();
    }

    @Test
    void roundTripKeepsEveryFieldAndWritesTheEventInTheSameTransaction() {
        Reserva r = reserva("2026-09-10", "2026-09-15");
        assertTrue(create(r, key()).created());

        Reserva stored = repository.findById(r.getId().value()).orElseThrow();
        assertEquals(r.getUsuarioId(), stored.getUsuarioId());
        assertEquals(r.getFechas(), stored.getFechas());
        assertEquals(r.getTotal(), stored.getTotal());
        assertEquals(EstadoReserva.PENDIENTE, stored.getEstado());
        assertEquals(r.getCreatedAt(), stored.getCreatedAt());
        assertEquals("ReservaCreada", jdbc.queryForObject("SELECT payload->>'eventType' FROM booking.outbox_event"
                + " WHERE aggregate_id = ? AND published = FALSE", String.class, r.getId().value()));
        assertEquals("it-trace", jdbc.queryForObject("SELECT payload->'metadata'->>'correlationId'"
                + " FROM booking.outbox_event WHERE aggregate_id = ?", String.class, r.getId().value()));
    }

    @Test
    void aRepeatedKeyReturnsTheFirstReservaEvenWithOverlappingDates() {
        String key = key();
        Reserva first = reserva("2026-09-10", "2026-09-15");
        create(first, key);

        Created retry = create(reserva("2026-09-10", "2026-09-15"), key);
        assertFalse(retry.created());
        assertEquals(first.getId(), retry.reserva().getId());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM booking.reservation WHERE property_id = ?",
                Integer.class, propiedad));
    }

    @Test
    void hu002Scenario2TheConstraintRejectsAnOverlapThatGotPastTheDomain() {
        create(reserva("2026-09-10", "2026-09-15"), key());
        String key = key();

        assertThrows(ReservaSuperpuestaException.class, () -> create(reserva("2026-09-14", "2026-09-20"), key));
        assertTrue(repository.findByKey(key).isEmpty(), "nothing of the rejected request is kept");
        assertTrue(create(reserva("2026-09-15", "2026-09-20"), key()).created(), "the check-out day is free");
    }

    @Test
    void hu002Scenario1TwoConcurrentRequestsForTheSameDatesOneWins() {
        var a = CompletableFuture.supplyAsync(() -> create(reserva("2026-10-01", "2026-10-05"), key()));
        var b = CompletableFuture.supplyAsync(() -> create(reserva("2026-10-03", "2026-10-07"), key()));
        int created = 0;
        int rejected = 0;
        for (var future : java.util.List.of(a, b)) {
            try {
                created += future.join().created() ? 1 : 0;
            } catch (CompletionException e) {
                assertTrue(e.getCause() instanceof ReservaSuperpuestaException, e.getCause().toString());
                rejected++;
            }
        }
        assertEquals(1, created);
        assertEquals(1, rejected);
    }

    @Test
    void aTransitionIsStoredWithItsEventAndAFailedOneChangesNothing() {
        Reserva r = reserva("2026-11-01", "2026-11-03");
        create(r, key());
        UUID id = r.getId().value();

        repository.change(id, reserva -> {
            reserva.cancelar("FONDOS_INSUFICIENTES");
            return ReservaEvents.of(reserva, "it-trace");
        });
        Reserva cancelled = repository.findById(id).orElseThrow();
        assertEquals(EstadoReserva.CANCELADA, cancelled.getEstado());
        assertEquals("FONDOS_INSUFICIENTES", cancelled.getMotivoCierre());
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM booking.outbox_event WHERE aggregate_id = ?",
                Integer.class, id));

        assertThrows(InvalidStatusTransitionException.class, () -> repository.change(id, reserva -> {
            reserva.confirmar();
            return ReservaEvents.of(reserva, "it-trace");
        }));
        assertEquals(EstadoReserva.CANCELADA, repository.findById(id).orElseThrow().getEstado());
        assertTrue(create(reserva("2026-11-01", "2026-11-03"), key()).created(), "a cancelled Reserva frees its dates");
        assertTrue(repository.change(UUID.randomUUID(), reserva -> java.util.List.of()).isEmpty());
    }

    @Test
    void findsTheActiveOverlapsAndPagesMostRecentFirst() {
        Reserva first = reserva("2026-12-01", "2026-12-05");
        create(first, key());
        Reserva second = reserva("2026-12-10", "2026-12-12");
        create(second, key());

        assertEquals(1, repository.findActiveOverlapping(propiedad,
                new DateRange(LocalDate.parse("2026-12-04"), LocalDate.parse("2026-12-06"))).size());
        assertTrue(repository.findActiveOverlapping(propiedad,
                new DateRange(LocalDate.parse("2026-12-05"), LocalDate.parse("2026-12-10"))).isEmpty());

        var page = repository.findPage(new Filter(second.getUsuarioId(), EstadoReserva.PENDIENTE, null), 0, 10);
        assertEquals(1, page.total());
        assertEquals(second.getId(), page.items().get(0).getId());
        var before = repository.findPage(new Filter(first.getUsuarioId(), null, second.getCreatedAt()), 0, 10);
        assertEquals(first.getId(), before.items().get(0).getId());
    }
}
