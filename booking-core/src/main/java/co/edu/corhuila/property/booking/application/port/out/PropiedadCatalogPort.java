package co.edu.corhuila.property.booking.application.port.out;

import java.util.Optional;
import java.util.UUID;

/**
 * What Booking reads from Catalog when it creates a Reserva (ADR-015): the price of a
 * night, its currency and the capacity of the Propiedad, from GET /propiedades/{id}.
 * The use case is tested with a fake; the HTTP client is an adapter (rule 1).
 */
public interface PropiedadCatalogPort {

    record Propiedad(UUID id, long precioNocheCents, String moneda, int capacidad) { }

    /**
     * Empty when Catalog answers 404: the Propiedad does not exist.
     *
     * @throws CatalogNoDisponibleException when Catalog did not answer within its limit,
     *         after the bounded retries: nothing is created (ADR-015, rule 4)
     */
    Optional<Propiedad> find(UUID propiedadId, String correlationId);
}
