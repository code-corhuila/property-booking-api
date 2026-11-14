package co.edu.corhuila.property.booking.domain.model;

import java.util.UUID;

/** A Reserva moved to CANCELADA and its dates are free again (02-domain/domain-events.md). */
public record ReservaCanceladaEvent(ReservaId reservaId, UUID propiedadId, String motivo) { }
