package co.edu.corhuila.property.booking.domain.model;

/** INV-002: the Reserva is not in a state that allows the transition. */
public class InvalidStatusTransitionException extends DomainException {
    public InvalidStatusTransitionException(String message) {
        super(message);
    }
}
