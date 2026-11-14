package co.edu.corhuila.property.booking.application.usecase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.corhuila.property.booking.application.port.out.OutboxEvent;
import co.edu.corhuila.property.booking.domain.model.DateRange;
import co.edu.corhuila.property.booking.domain.model.EstadoReserva;
import co.edu.corhuila.property.booking.domain.model.Money;
import co.edu.corhuila.property.booking.domain.model.Reserva;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The envelopes match 02-domain/domain-events.md, field by field. */
class ReservaEventsTest {

    static final UUID PROPIEDAD = UUID.randomUUID();
    static final UUID HUESPED = UUID.randomUUID();

    static Reserva nueva() {
        return Reserva.crear(PROPIEDAD, HUESPED,
                new DateRange(LocalDate.parse("2026-09-10"), LocalDate.parse("2026-09-15")), new Money(45000, "COP"));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> payload(OutboxEvent e) {
        return (Map<String, Object>) e.envelope().get("payload");
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> metadata(OutboxEvent e) {
        return (Map<String, Object>) e.envelope().get("metadata");
    }

    @Test
    void reservaCreadaCarriesTheEnvelopeAndThePayloadOfTheCatalog() {
        Reserva r = nueva();
        List<OutboxEvent> events = ReservaEvents.of(r, "saga-trace-1");

        assertEquals(1, events.size());
        OutboxEvent e = events.get(0);
        assertEquals("ReservaCreada", e.eventType());
        assertEquals(r.getId().value(), e.aggregateId());
        assertEquals(e.id().toString(), e.envelope().get("eventId"));
        assertEquals(1, e.envelope().get("schemaVersion"));
        assertEquals(1, e.envelope().get("version"));
        assertEquals("Reserva", e.envelope().get("aggregateType"));
        assertEquals(r.getCreatedAt().toString(), e.envelope().get("occurredAt"));
        assertEquals(Map.of("reservaId", r.getId().value().toString(), "propiedadId", PROPIEDAD.toString(),
                "usuarioId", HUESPED.toString(), "fechaInicio", "2026-09-10", "fechaFin", "2026-09-15",
                "montoTotalCents", 45000L, "moneda", "COP", "estado", "PENDIENTE"), payload(e));
        assertEquals("saga-trace-1", metadata(e).get("correlationId"));
        assertNull(metadata(e).get("causationId"));
        assertTrue(metadata(e).containsKey("causationId"));
        assertEquals(HUESPED.toString(), metadata(e).get("userId"));
    }

    @Test
    void theTransitionsCarryTheirInstantAndMotivo() {
        Reserva confirmada = Reserva.restore(nueva().getId(), PROPIEDAD, HUESPED, nueva().getFechas(), nueva().getTotal(),
                EstadoReserva.PENDIENTE, null, java.time.Instant.EPOCH, java.time.Instant.EPOCH);
        confirmada.confirmar();
        OutboxEvent c = ReservaEvents.of(confirmada, "t").get(0);
        assertEquals("ReservaConfirmada", c.eventType());
        assertEquals(confirmada.getUpdatedAt().toString(), payload(c).get("confirmadoEn"));
        assertEquals("2026-09-15", payload(c).get("fechaFin"));

        Reserva cancelada = Reserva.restore(nueva().getId(), PROPIEDAD, HUESPED, nueva().getFechas(), nueva().getTotal(),
                EstadoReserva.PENDIENTE, null, java.time.Instant.EPOCH, java.time.Instant.EPOCH);
        cancelada.cancelar("FONDOS_INSUFICIENTES");
        OutboxEvent x = ReservaEvents.of(cancelada, "t").get(0);
        assertEquals("ReservaCancelada", x.eventType());
        assertEquals("FONDOS_INSUFICIENTES", payload(x).get("motivo"));
        assertTrue(payload(x).containsKey("canceladoEn"));
        assertEquals(HUESPED.toString(), metadata(x).get("userId"), "the guest, not the service that called");

        Reserva expirada = Reserva.restore(nueva().getId(), PROPIEDAD, HUESPED, nueva().getFechas(), nueva().getTotal(),
                EstadoReserva.PENDIENTE, null, java.time.Instant.EPOCH, java.time.Instant.EPOCH);
        expirada.expirar("TIMEOUT_PAGO_EXCEDIDO");
        OutboxEvent ex = ReservaEvents.of(expirada, "t").get(0);
        assertEquals("ReservaExpirada", ex.eventType());
        assertEquals("TIMEOUT_PAGO_EXCEDIDO", payload(ex).get("motivo"));
        assertTrue(payload(ex).containsKey("expiradoEn"));
    }

    @Test
    void aRepeatedCancellationRaisesNoEvent() {
        Reserva r = Reserva.restore(nueva().getId(), PROPIEDAD, HUESPED, nueva().getFechas(), nueva().getTotal(),
                EstadoReserva.CANCELADA, "SAGA_COMPENSADA", java.time.Instant.EPOCH, java.time.Instant.EPOCH);
        r.cancelar("SAGA_COMPENSADA");

        assertTrue(ReservaEvents.of(r, "t").isEmpty());
    }
}
