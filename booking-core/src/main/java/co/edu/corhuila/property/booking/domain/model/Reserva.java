package co.edu.corhuila.property.booking.domain.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Aggregate root of the booking domain (02-domain/entities-and-rules.md): a guest's
 * request to book a property for a date range, from creation to confirmation,
 * cancellation or expiration.
 */
public class Reserva {

    private final ReservaId id;
    private final UUID propiedadId;
    private final UUID usuarioId;
    private final DateRange fechas;
    private final Money total;
    private EstadoReserva estado;
    private String motivoCierre;
    private final Instant createdAt;
    private Instant updatedAt;
    private final List<Object> eventosDominio = new ArrayList<>();

    private Reserva(ReservaId id, UUID propiedadId, UUID usuarioId,
                    DateRange fechas, Money total, EstadoReserva estado) {
        this.id = id;
        this.propiedadId = propiedadId;
        this.usuarioId = usuarioId;
        this.fechas = fechas;
        this.total = total;
        this.estado = estado;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public static Reserva crear(UUID propiedadId, UUID usuarioId,
                                DateRange fechas, Money total) {
        if (propiedadId == null || usuarioId == null || fechas == null || total == null) {
            throw new DomainException("propiedadId, usuarioId, fechas y total son requeridos");
        }
        if (total.esMenorOIgualACero()) {
            throw new DomainException("INV-003: El monto total debe ser mayor a cero");
        }
        Reserva reserva = new Reserva(ReservaId.nueva(), propiedadId, usuarioId,
                fechas, total, EstadoReserva.PENDIENTE);
        reserva.eventosDominio.add(new ReservaCreadaEvent(reserva.id, propiedadId,
                usuarioId, fechas, total));
        return reserva;
    }

    /**
     * INV-001, the domain validation prior to persistence: no other PENDIENTE or
     * CONFIRMADA Reserva of the same property may overlap these dates. The
     * exclusion constraint of the database is the second line of defence.
     */
    public void validarSinSuperposicion(Collection<Reserva> reservasDeLaPropiedad) {
        for (Reserva otra : reservasDeLaPropiedad) {
            if (!otra.id.equals(this.id)
                    && otra.propiedadId.equals(this.propiedadId)
                    && otra.bloqueaSusFechas()
                    && otra.fechas.seSuperponeCon(this.fechas)) {
                throw new DomainException(
                    "INV-001: Las fechas se superponen con la reserva " + otra.id.value());
            }
        }
    }

    public void confirmar() {
        validarEstadoPendiente("confirmar");   // confirming twice is a violation (INV-002)
        this.estado = EstadoReserva.CONFIRMADA;
        this.updatedAt = Instant.now();
        this.eventosDominio.add(new ReservaConfirmadaEvent(this.id, this.propiedadId));
    }

    public void cancelar(String motivo) {
        if (this.estado == EstadoReserva.CANCELADA) return;    // repeated call: no change, no event
        validarEstadoPendiente("cancelar");
        this.estado = EstadoReserva.CANCELADA;
        this.motivoCierre = motivo;
        this.updatedAt = Instant.now();
        this.eventosDominio.add(new ReservaCanceladaEvent(this.id, this.propiedadId, motivo));
    }

    public void expirar(String motivo) {
        if (this.estado == EstadoReserva.EXPIRADA) return;     // repeated call: no change, no event
        validarEstadoPendiente("expirar");
        this.estado = EstadoReserva.EXPIRADA;
        this.motivoCierre = motivo;
        this.updatedAt = Instant.now();
        this.eventosDominio.add(new ReservaExpiradaEvent(this.id, this.propiedadId, motivo));
    }

    private void validarEstadoPendiente(String accion) {
        if (this.estado != EstadoReserva.PENDIENTE) {
            throw new InvalidStatusTransitionException(
                String.format("INV-002: No se puede %s una reserva en estado %s", accion, this.estado));
        }
    }

    /** Only PENDIENTE and CONFIRMADA hold their dates (INV-001). */
    private boolean bloqueaSusFechas() {
        return estado == EstadoReserva.PENDIENTE || estado == EstadoReserva.CONFIRMADA;
    }

    public List<Object> obtenerEventosDominio() {
        return Collections.unmodifiableList(eventosDominio);
    }

    public ReservaId getId() { return id; }
    public UUID getPropiedadId() { return propiedadId; }
    public UUID getUsuarioId() { return usuarioId; }
    public DateRange getFechas() { return fechas; }
    public Money getTotal() { return total; }
    public EstadoReserva getEstado() { return estado; }
    public String getMotivoCierre() { return motivoCierre; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
