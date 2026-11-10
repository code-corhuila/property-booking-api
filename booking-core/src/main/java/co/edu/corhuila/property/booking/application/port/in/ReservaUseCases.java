package co.edu.corhuila.property.booking.application.port.in;

import co.edu.corhuila.property.booking.domain.model.EstadoReserva;
import co.edu.corhuila.property.booking.domain.model.Reserva;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** What the service offers about Reservas. Inbound adapters depend on these interfaces only. */
public final class ReservaUseCases {

    private ReservaUseCases() { }

    /** E-01, step crear-reserva. The shape was already validated by the inbound adapter. */
    public record CrearReservaCommand(Caller caller, UUID usuarioId, UUID propiedadId, LocalDate fechaInicio,
                                      LocalDate fechaFin, int huespedes, String idempotencyKey, String correlationId) { }

    /** created is false when the key was already used: the Reserva of the first request. */
    public record Creation(Reserva reserva, boolean created) { }

    public interface CrearReserva {
        Creation crear(CrearReservaCommand command);
    }

    /** E-18, step confirmar-reserva. */
    public interface ConfirmarReserva {
        Reserva confirmar(Caller caller, UUID id, String correlationId);
    }

    /** E-04. motivo is read only from a service; null records SAGA_COMPENSADA. */
    public interface CancelarReserva {
        Reserva cancelar(Caller caller, UUID id, String motivo, String correlationId);
    }

    /** E-19, the job expire-stale-reservations of property-worker (ADR-014). */
    public interface ExpirarReserva {
        Reserva expirar(Caller caller, UUID id, String correlationId);
    }

    /** E-03. */
    public interface ObtenerReserva {
        Reserva obtener(Caller caller, UUID id);
    }

    /** E-02 filters: estado and creadaAntesDe are optional (null = no filter). */
    public record ListarReservasQuery(EstadoReserva estado, Instant creadaAntesDe, int page, int limit) { }

    public record ReservaPage(List<Reserva> items, long total, int page, int limit) { }

    /** E-02. */
    public interface ListarReservas {
        ReservaPage listar(Caller caller, ListarReservasQuery query);
    }
}
