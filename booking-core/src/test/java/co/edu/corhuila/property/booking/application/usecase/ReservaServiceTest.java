package co.edu.corhuila.property.booking.application.usecase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.corhuila.property.booking.application.port.in.AccessDeniedException;
import co.edu.corhuila.property.booking.application.port.in.Caller;
import co.edu.corhuila.property.booking.application.port.in.ReservaUseCases.CrearReservaCommand;
import co.edu.corhuila.property.booking.application.port.in.ReservaUseCases.Creation;
import co.edu.corhuila.property.booking.application.port.out.OutboxEvent;
import co.edu.corhuila.property.booking.application.port.out.PropiedadCatalogPort;
import co.edu.corhuila.property.booking.application.port.out.ReservaRepository;
import co.edu.corhuila.property.booking.domain.model.DateRange;
import co.edu.corhuila.property.booking.domain.model.DomainException;
import co.edu.corhuila.property.booking.domain.model.EstadoReserva;
import co.edu.corhuila.property.booking.domain.model.Reserva;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/** E-01 at the level of the use case, with fakes of the two ports. */
class ReservaServiceTest {

    static final Caller WORKFLOW = new Caller("svc-workflow", null, true);
    static final UUID PROPIEDAD = UUID.randomUUID();

    /** Stores what it receives; the exclusion rule is the JDBC adapter's, tested there. */
    static class FakeReservas implements ReservaRepository {
        final Map<String, Reserva> byKey = new HashMap<>();
        final List<Reserva> all = new ArrayList<>();
        final List<OutboxEvent> outbox = new ArrayList<>();

        @Override public Created create(Reserva r, String key, List<OutboxEvent> events) {
            byKey.put(key, r);
            all.add(r);
            outbox.addAll(events);
            return new Created(r, true);
        }
        @Override public Optional<Reserva> findById(UUID id) { return Optional.empty(); }
        @Override public Optional<Reserva> findByKey(String key) { return Optional.ofNullable(byKey.get(key)); }
        @Override public List<Reserva> findActiveOverlapping(UUID p, DateRange f) {
            return all.stream().filter(r -> r.getFechas().seSuperponeCon(f)).toList();
        }
        @Override public Optional<Reserva> change(UUID id, Function<Reserva, List<OutboxEvent>> t) { return Optional.empty(); }
        @Override public Page findPage(Filter f, int offset, int limit) { return new Page(List.of(), 0); }
    }

    final FakeReservas reservas = new FakeReservas();
    final AtomicInteger catalogCalls = new AtomicInteger();
    final PropiedadCatalogPort catalog = (id, correlationId) -> {
        catalogCalls.incrementAndGet();
        return id.equals(PROPIEDAD) ? Optional.of(new PropiedadCatalogPort.Propiedad(id, 30000000L, "COP", 3))
                : Optional.empty();
    };
    final ReservaService service = new ReservaService(reservas, catalog);

    CrearReservaCommand command(Caller caller, UUID propiedad, String inicio, String fin, int huespedes, String key) {
        return new CrearReservaCommand(caller, UUID.randomUUID(), propiedad, LocalDate.parse(inicio), LocalDate.parse(fin),
                huespedes, key, "trace-1");
    }

    @Test
    void theTotalIsThePriceOfANightTimesTheNightsOfTheHalfOpenRange() {
        Creation c = service.crear(command(WORKFLOW, PROPIEDAD, "2026-09-10", "2026-09-13", 3, "key-00001"));

        assertTrue(c.created());
        assertEquals(EstadoReserva.PENDIENTE, c.reserva().getEstado());
        assertEquals(90000000L, c.reserva().getTotal().getMontoCents());
        assertEquals("COP", c.reserva().getTotal().getMoneda());
        assertEquals("ReservaCreada", reservas.outbox.get(0).eventType());
    }

    @Test
    void aRepeatedKeyAnswersTheFirstReservaWithoutCallingCatalog() {
        Creation first = service.crear(command(WORKFLOW, PROPIEDAD, "2026-09-10", "2026-09-13", 1, "key-00001"));
        Creation again = service.crear(command(WORKFLOW, PROPIEDAD, "2026-09-10", "2026-09-13", 1, "key-00001"));

        assertFalse(again.created());
        assertEquals(first.reserva().getId(), again.reserva().getId());
        assertEquals(1, catalogCalls.get());
    }

    @Test
    void theRulesOfCreationAreBusinessViolations() {
        service.crear(command(WORKFLOW, PROPIEDAD, "2026-09-10", "2026-09-13", 1, "key-00001"));

        assertThrows(DomainException.class,
                () -> service.crear(command(WORKFLOW, PROPIEDAD, "2026-09-12", "2026-09-14", 1, "key-00002")));
        assertThrows(DomainException.class,
                () -> service.crear(command(WORKFLOW, PROPIEDAD, "2026-10-01", "2026-10-02", 4, "key-00003")));
        assertThrows(DomainException.class,
                () -> service.crear(command(WORKFLOW, UUID.randomUUID(), "2026-10-01", "2026-10-02", 1, "key-00004")));
        assertEquals(1, reservas.all.size());
    }

    @Test
    void aPersonNeverCreatesAReserva() {
        Caller admin = new Caller(UUID.randomUUID().toString(), List.of("ADMIN"), false);

        assertThrows(AccessDeniedException.class,
                () -> service.crear(command(admin, PROPIEDAD, "2026-09-10", "2026-09-13", 1, "key-00001")));
        assertEquals(0, catalogCalls.get());
    }
}
