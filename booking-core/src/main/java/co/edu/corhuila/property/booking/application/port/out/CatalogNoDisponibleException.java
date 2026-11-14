package co.edu.corhuila.property.booking.application.port.out;

/**
 * Catalog did not answer within its time limit, or answered something that is not its
 * contract. Not a business rule: the answer is 500 INTERNAL_ERROR and nothing is
 * created (ADR-015, rule 4; E-01).
 */
public class CatalogNoDisponibleException extends RuntimeException {
    public CatalogNoDisponibleException(String message, Throwable cause) {
        super(message, cause);
    }
}
