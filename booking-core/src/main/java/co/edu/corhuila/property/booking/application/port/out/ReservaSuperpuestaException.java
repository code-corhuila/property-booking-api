package co.edu.corhuila.property.booking.application.port.out;

import co.edu.corhuila.property.booking.domain.model.DomainException;

/**
 * INV-001 at the last line of defence: the exclusion constraint of booking.reservation
 * rejected the dates, typically because a concurrent request took them first
 * (HU-BOOK-002). 422 BUSINESS_RULE_VIOLATION, like the domain check.
 */
public class ReservaSuperpuestaException extends DomainException {
    public ReservaSuperpuestaException() {
        super("INV-001: the dates overlap another reservation of this property");
    }
}
