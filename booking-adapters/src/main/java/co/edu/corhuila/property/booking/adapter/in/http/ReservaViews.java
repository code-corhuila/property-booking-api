package co.edu.corhuila.property.booking.adapter.in.http;

import co.edu.corhuila.property.booking.adapter.in.http.ApiError.FieldError;
import co.edu.corhuila.property.booking.adapter.in.http.ApiError.ValidationException;
import co.edu.corhuila.property.booking.application.port.in.Caller;
import co.edu.corhuila.property.booking.domain.model.Reserva;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;

/** The public shapes of booking-service.yaml, and what every controller reads from a request. */
final class ReservaViews {

    static final Pattern UUID_FORMAT = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private ReservaViews() { }

    /** ReservaResponse. The domain entity is never serialized directly (Annex C). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ReservaResponse(String id, String propiedadId, String usuarioId, String fechaInicio, String fechaFin,
                           long montoTotalCents, String moneda, String estado, String motivoCierre,
                           String createdAt, String updatedAt) {
        static ReservaResponse of(Reserva r) {
            return new ReservaResponse(r.getId().value().toString(), r.getPropiedadId().toString(),
                    r.getUsuarioId().toString(), r.getFechas().getFechaInicio().toString(),
                    r.getFechas().getFechaFin().toString(), r.getTotal().getMontoCents(), r.getTotal().getMoneda(),
                    r.getEstado().name(), r.getMotivoCierre(), r.getCreatedAt().truncatedTo(ChronoUnit.SECONDS).toString(),
                    r.getUpdatedAt().truncatedTo(ChronoUnit.SECONDS).toString());
        }
    }

    /** The caller of a protected route, from the claims AuthFilter verified. */
    static Caller caller(HttpServletRequest request) {
        JsonNode claims = (JsonNode) request.getAttribute(AuthFilter.CLAIMS_ATTRIBUTE);
        List<String> roles = new ArrayList<>();
        claims.path("roles").forEach(r -> roles.add(r.asText()));
        return new Caller(claims.path("sub").asText(), roles, "service".equals(claims.path("scope").asText()));
    }

    /** The X-Correlation-Id of the request (CorrelationFilter put it in the MDC). */
    static String correlationId() {
        return MDC.get(CorrelationFilter.MDC_KEY);
    }

    static UUID requireId(String id) {
        if (id == null || !UUID_FORMAT.matcher(id).matches()) {
            throw new ValidationException("the id must be a UUID", List.of(new FieldError("id", "must be a UUID")));
        }
        return UUID.fromString(id);
    }

    /** An Idempotency-Key, when one is sent: 8 to 128 characters (_shared.yaml). */
    static boolean validKey(String key) {
        return key != null && key.length() >= 8 && key.length() <= 128;
    }
}
