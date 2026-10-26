package co.edu.corhuila.property.booking.domain.model;

/** Lifecycle of a Reserva. Only PENDIENTE can transition; the other three are terminal (INV-002). */
public enum EstadoReserva {
    PENDIENTE,
    CONFIRMADA,
    CANCELADA,
    EXPIRADA
}
