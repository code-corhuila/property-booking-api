package co.edu.corhuila.property.booking.domain.model;

import java.util.UUID;

/** A Reserva left in PENDIENTE expired and its dates are free again (02-domain/domain-events.md). */
public record ReservaExpiradaEvent(ReservaId reservaId, UUID propiedadId, String motivo) { }
