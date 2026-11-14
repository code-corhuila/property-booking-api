package co.edu.corhuila.property.booking.application.usecase;

import co.edu.corhuila.property.booking.application.port.out.OutboxEvent;
import co.edu.corhuila.property.booking.domain.model.Reserva;
import co.edu.corhuila.property.booking.domain.model.ReservaCanceladaEvent;
import co.edu.corhuila.property.booking.domain.model.ReservaConfirmadaEvent;
import co.edu.corhuila.property.booking.domain.model.ReservaCreadaEvent;
import co.edu.corhuila.property.booking.domain.model.ReservaExpiradaEvent;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Turns the events a Reserva raised into the envelopes of 02-domain/domain-events.md:
 * eventId, eventType, schemaVersion, aggregateId, aggregateType, occurredAt, version,
 * payload and metadata. metadata.correlationId is the X-Correlation-Id of the request;
 * metadata.userId is always the guest of the Reserva, never the sub of a service
 * token (D-C14); causationId is null, since every event comes from an HTTP call.
 */
public final class ReservaEvents {

    private ReservaEvents() { }

    public static List<OutboxEvent> of(Reserva reserva, String correlationId) {
        return reserva.obtenerEventosDominio().stream().map(event -> envelope(reserva, event, correlationId)).toList();
    }

    private static OutboxEvent envelope(Reserva r, Object event, String correlationId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reservaId", r.getId().value().toString());
        payload.put("propiedadId", r.getPropiedadId().toString());
        String type;
        Instant occurredAt = r.getUpdatedAt();
        if (event instanceof ReservaCreadaEvent) {
            type = "ReservaCreada";
            occurredAt = r.getCreatedAt();
            payload.put("usuarioId", r.getUsuarioId().toString());
            payload.put("fechaInicio", r.getFechas().getFechaInicio().toString());
            payload.put("fechaFin", r.getFechas().getFechaFin().toString());
            payload.put("montoTotalCents", r.getTotal().getMontoCents());
            payload.put("moneda", r.getTotal().getMoneda());
            payload.put("estado", "PENDIENTE");
        } else if (event instanceof ReservaConfirmadaEvent) {
            type = "ReservaConfirmada";
            payload.put("fechaInicio", r.getFechas().getFechaInicio().toString());
            payload.put("fechaFin", r.getFechas().getFechaFin().toString());
            payload.put("confirmadoEn", occurredAt.toString());
        } else if (event instanceof ReservaCanceladaEvent cancelada) {
            type = "ReservaCancelada";
            payload.put("motivo", cancelada.motivo());
            payload.put("canceladoEn", occurredAt.toString());
        } else if (event instanceof ReservaExpiradaEvent expirada) {
            type = "ReservaExpirada";
            payload.put("motivo", expirada.motivo());
            payload.put("expiradoEn", occurredAt.toString());
        } else {
            throw new IllegalArgumentException("unknown domain event " + event.getClass().getSimpleName());
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("correlationId", correlationId);
        metadata.put("causationId", null);
        metadata.put("userId", r.getUsuarioId().toString());

        UUID eventId = UUID.randomUUID();
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", eventId.toString());
        envelope.put("eventType", type);
        envelope.put("schemaVersion", 1);
        envelope.put("aggregateId", r.getId().value().toString());
        envelope.put("aggregateType", "Reserva");
        envelope.put("occurredAt", occurredAt.toString());
        envelope.put("version", 1);
        envelope.put("payload", payload);
        envelope.put("metadata", metadata);
        return new OutboxEvent(eventId, r.getId().value(), type, envelope);
    }
}
