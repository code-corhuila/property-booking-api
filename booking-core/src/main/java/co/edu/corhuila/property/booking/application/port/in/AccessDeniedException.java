package co.edu.corhuila.property.booking.application.port.in;

/** A valid credential without permission for the operation: 403 FORBIDDEN. */
public class AccessDeniedException extends RuntimeException {
    public AccessDeniedException(String message) {
        super(message);
    }
}
