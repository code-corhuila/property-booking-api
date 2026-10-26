package co.edu.corhuila.property.booking.domain.model;

import java.util.UUID;

/** A Reserva was created in PENDIENTE and its dates are held (02-domain/domain-events.md). */
public record ReservaCreadaEvent(ReservaId reservaId, UUID propiedadId, UUID usuarioId,
                                 DateRange fechas, Money total) { }
