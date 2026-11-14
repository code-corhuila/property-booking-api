package co.edu.corhuila.property.booking.adapter.out.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import org.junit.jupiter.api.Test;

/** The in-memory twin keeps the contract of the port, as the JDBC adapter does. */
class InMemoryReservaRepositoryTest {

    static final UUID PROPIEDAD = UUID.randomUUID();
    final InMemoryReservaRepository repository = new InMemoryReservaRepository();

    static Reserva reserva(UUID huesped, String inicio, String fin) {
        return Reserva.crear(PROPIEDAD, huesped, new DateRange(LocalDate.parse(inicio), LocalDate.parse(fin)),
                new Money(45000, "COP"));
    }

    Reserva create(Reserva r, String key) {
        return repository.create(r, key, ReservaEvents.of(r, "t")).reserva();
    }

    @Test
    void storesTheReservaItsKeyAndItsEvent() {
        Reserva r = create(reserva(UUID.randomUUID(), "2026-09-10", "2026-09-15"), "key-00001");

        assertEquals(r.getId(), repository.findById(r.getId().value()).orElseThrow().getId());
        assertEquals(r.getId(), repository.findByKey("key-00001").orElseThrow().getId());
        assertEquals("ReservaCreada", repository.outbox().get(0).eventType());
    }

    @Test
    void aRepeatedKeyReturnsTheFirstReservaAndWritesNoEvent() {
        Reserva first = create(reserva(UUID.randomUUID(), "2026-09-10", "2026-09-15"), "key-00001");
        Reserva other = reserva(UUID.randomUUID(), "2026-10-10", "2026-10-15");

        var retry = repository.create(other, "key-00001", ReservaEvents.of(other, "t"));
        assertFalse(retry.created());
        assertEquals(first.getId(), retry.reserva().getId());
        assertEquals(1, repository.outbox().size());
    }

    @Test
    void overlappingActiveDatesAreRejectedAndTheCheckOutDayIsFree() {
        create(reserva(UUID.randomUUID(), "2026-09-10", "2026-09-15"), "key-00001");

        assertThrows(ReservaSuperpuestaException.class,
                () -> create(reserva(UUID.randomUUID(), "2026-09-14", "2026-09-16"), "key-00002"));
        create(reserva(UUID.randomUUID(), "2026-09-15", "2026-09-18"), "key-00003");
    }

    @Test
    void aTransitionIsStoredWithItsEventAndAFailedOneChangesNothing() {
        Reserva r = create(reserva(UUID.randomUUID(), "2026-09-10", "2026-09-15"), "key-00001");
        UUID id = r.getId().value();

        repository.change(id, reserva -> {
            reserva.cancelar("SAGA_COMPENSADA");
            return ReservaEvents.of(reserva, "t");
        });
        assertEquals(EstadoReserva.CANCELADA, repository.findById(id).orElseThrow().getEstado());
        assertEquals("ReservaCancelada", repository.outbox().get(1).eventType());

        assertThrows(InvalidStatusTransitionException.class, () -> repository.change(id, reserva -> {
            reserva.confirmar();
            return ReservaEvents.of(reserva, "t");
        }));
        assertEquals(EstadoReserva.CANCELADA, repository.findById(id).orElseThrow().getEstado());
        assertTrue(repository.change(UUID.randomUUID(), reserva -> null).isEmpty());
        // A cancelled Reserva frees its dates.
        create(reserva(UUID.randomUUID(), "2026-09-10", "2026-09-15"), "key-00002");
    }

    @Test
    void thePageIsFilteredBoundedAndMostRecentFirst() throws Exception {
        UUID ana = UUID.randomUUID();
        Reserva first = create(reserva(ana, "2026-09-01", "2026-09-02"), "key-00001");
        Thread.sleep(2);
        Reserva second = create(reserva(ana, "2026-09-03", "2026-09-04"), "key-00002");
        create(reserva(UUID.randomUUID(), "2026-09-05", "2026-09-06"), "key-00003");

        var page = repository.findPage(new Filter(ana, null, null), 0, 1);
        assertEquals(2, page.total());
        assertEquals(second.getId(), page.items().get(0).getId());
        assertEquals(first.getId(), repository.findPage(new Filter(ana, null, null), 1, 1).items().get(0).getId());
        assertEquals(3, repository.findPage(new Filter(null, EstadoReserva.PENDIENTE, null), 0, 10).total());
        assertEquals(1, repository.findPage(new Filter(null, null, second.getCreatedAt()), 0, 10).total());
    }
}
