package co.edu.corhuila.property.booking.application.port.out;

import co.edu.corhuila.property.booking.domain.model.DateRange;
import co.edu.corhuila.property.booking.domain.model.EstadoReserva;
import co.edu.corhuila.property.booking.domain.model.Reserva;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * What the service needs to store Reservas (06-data/models.md, schema booking).
 * Every change of state is written with its outbox events in ONE transaction (ADR-005).
 */
public interface ReservaRepository {

    /** created is false when the key was already used: the Reserva of the first request. */
    record Created(Reserva reserva, boolean created) { }

    /** usuarioId null: every guest (ADMIN, service). estado and creadaAntesDe null: no filter. */
    record Filter(UUID usuarioId, EstadoReserva estado, Instant creadaAntesDe) { }

    record Page(List<Reserva> items, long total) { }

    /**
     * Stores the Reserva, its Idempotency-Key and its events in one transaction. A key
     * already used returns its Reserva with created=false.
     *
     * @throws ReservaSuperpuestaException when the exclusion constraint rejects the dates (INV-001)
     */
    Created create(Reserva reserva, String idempotencyKey, List<OutboxEvent> events);

    Optional<Reserva> findById(UUID id);

    Optional<Reserva> findByKey(String idempotencyKey);

    /** The PENDIENTE and CONFIRMADA Reservas of the property whose dates overlap these. */
    List<Reserva> findActiveOverlapping(UUID propiedadId, DateRange fechas);

    /**
     * Loads the Reserva locked against concurrent changes, applies the transition
     * (which returns the events it produced) and stores the new state with those
     * events, all in one transaction. A transition that throws changes nothing.
     * Empty when the Reserva does not exist.
     */
    Optional<Reserva> change(UUID id, Function<Reserva, List<OutboxEvent>> transition);

    /** Most recent first; never unbounded, the caller always passes a limit. */
    Page findPage(Filter filter, int offset, int limit);
}
