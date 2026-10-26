package co.edu.corhuila.property.booking.domain.model;

import java.util.UUID;

/** A Reserva moved to CONFIRMADA (02-domain/domain-events.md). */
public record ReservaConfirmadaEvent(ReservaId reservaId, UUID propiedadId) { }
