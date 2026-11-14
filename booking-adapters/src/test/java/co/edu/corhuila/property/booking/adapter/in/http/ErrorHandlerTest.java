package co.edu.corhuila.property.booking.adapter.in.http;

import static org.junit.jupiter.api.Assertions.assertEquals;

import co.edu.corhuila.property.booking.domain.model.DomainException;
import co.edu.corhuila.property.booking.domain.model.InvalidStatusTransitionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/** The domain errors become 422 with the right code, in this one place. */
class ErrorHandlerTest {

    final ErrorHandler handler = new ErrorHandler();

    @BeforeEach
    void correlation() {
        MDC.put(CorrelationFilter.MDC_KEY, "trace-422");
    }

    @AfterEach
    void clear() {
        MDC.clear();
    }

    @Test
    void anInvalidTransitionIs422InvalidStatusTransition() {
        var response = handler.invalidTransition(new InvalidStatusTransitionException("INV-002"));

        assertEquals(422, response.getStatusCode().value());
        assertEquals(ApiError.INVALID_STATUS_TRANSITION, response.getBody().error());
        assertEquals("trace-422", response.getBody().traceId());
    }

    @Test
    void anyOtherDomainRuleIs422BusinessRuleViolation() {
        var response = handler.businessRule(new DomainException("INV-001"));

        assertEquals(422, response.getStatusCode().value());
        assertEquals(ApiError.BUSINESS_RULE_VIOLATION, response.getBody().error());
    }

    @Test
    void anUnexpectedErrorIs500WithANeutralMessage() {
        var response = handler.unexpected(new IllegalStateException("connection refused to db-host:5432"));

        assertEquals(500, response.getStatusCode().value());
        assertEquals(ApiError.INTERNAL_ERROR, response.getBody().error());
        assertEquals("internal server error", response.getBody().message());
    }
}
