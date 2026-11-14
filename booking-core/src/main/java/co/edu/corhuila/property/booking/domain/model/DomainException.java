package co.edu.corhuila.property.booking.domain.model;

/**
 * An invariant of the domain was violated (02-domain/entities-and-rules.md). The
 * inbound adapter maps it to 422 BUSINESS_RULE_VIOLATION, or to 422
 * INVALID_STATUS_TRANSITION for {@link InvalidStatusTransitionException}.
 */
public class DomainException extends RuntimeException {
    public DomainException(String message) {
        super(message);
    }
}
