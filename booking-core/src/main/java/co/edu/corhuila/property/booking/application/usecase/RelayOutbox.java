package co.edu.corhuila.property.booking.application.usecase;

import co.edu.corhuila.property.booking.application.port.out.EventPublisher;
import co.edu.corhuila.property.booking.application.port.out.EventPublisher.PublishException;
import co.edu.corhuila.property.booking.application.port.out.OutboxStore;
import co.edu.corhuila.property.booking.application.port.out.PendingEvent;
import java.util.Map;

/**
 * One pass of the outbox relay (ADR-005; HU-BOOK-001 scenario 5). It publishes the
 * unpublished rows in order and marks each one only after the broker confirmed it. On the
 * first failure it stops, so the order is kept and nothing is lost: the row is tried again
 * on the next pass. If the process dies between the confirm and the mark, the row is
 * published again: at least once, never lost (consumers deduplicate by eventId).
 */
public class RelayOutbox {

    /** The routing key of each event: its channel in 02-domain/domain-events.md. */
    public static final Map<String, String> ROUTING_KEYS = Map.of(
            "ReservaCreada", "reservas.reserva.creada",
            "ReservaConfirmada", "reservas.reserva.confirmada",
            "ReservaCancelada", "reservas.reserva.cancelada",
            "ReservaExpirada", "reservas.reserva.expirada");

    public record Pass(int published, boolean failed, int pending) { }

    private final OutboxStore outbox;
    private final EventPublisher broker;
    private final int batchSize;

    public RelayOutbox(OutboxStore outbox, EventPublisher broker, int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("the batch size must be at least 1");
        }
        this.outbox = outbox;
        this.broker = broker;
        this.batchSize = batchSize;
    }

    public Pass runOnce() {
        var rows = outbox.nextUnpublished(batchSize);
        int published = 0;
        for (PendingEvent row : rows) {
            String key = ROUTING_KEYS.get(row.eventType());
            if (key == null) {
                throw new IllegalStateException("no routing key for the event type " + row.eventType());
            }
            try {
                broker.publish(key, row);
            } catch (PublishException e) {
                return new Pass(published, true, rows.size());
            }
            outbox.markPublished(row.id());
            published++;
        }
        return new Pass(published, false, rows.size());
    }
}
