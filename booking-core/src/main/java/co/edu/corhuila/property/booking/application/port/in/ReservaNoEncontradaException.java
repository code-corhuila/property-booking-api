package co.edu.corhuila.property.booking.application.port.in;

import java.util.UUID;

/** No Reserva has that id: 404 NOT_FOUND. */
public class ReservaNoEncontradaException extends RuntimeException {
    public ReservaNoEncontradaException(UUID id) {
        super("the reservation " + id + " does not exist");
    }
}
