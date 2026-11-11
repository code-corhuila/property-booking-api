package co.edu.corhuila.property.booking.application.port.out;

import java.util.UUID;

/**
 * A row of booking.outbox_event not published yet. {@code id} is the eventId of the
 * envelope, so consumers deduplicate by it; {@code payload} is the envelope as stored.
 */
public record PendingEvent(UUID id, String eventType, String payload, String correlationId) { }
