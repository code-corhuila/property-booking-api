package co.edu.corhuila.property.booking.adapter.in.http;

import co.edu.corhuila.property.booking.adapter.in.http.ApiError.FieldError;
import co.edu.corhuila.property.booking.adapter.in.http.ApiError.ValidationException;
import co.edu.corhuila.property.booking.adapter.in.http.ReservaViews.ReservaResponse;
import co.edu.corhuila.property.booking.application.port.in.ReservaUseCases;
import co.edu.corhuila.property.booking.application.port.in.ReservaUseCases.CrearReservaCommand;
import co.edu.corhuila.property.booking.application.port.in.ReservaUseCases.Creation;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** E-01 POST /reservas and the transitions of the Reserva. Validates the SHAPE of the input and holds no rule. */
@RestController
@RequestMapping("/api/v1/reservas")
public class ReservaController {

    /** huespedes is read as BigDecimal so that 2.5 is rejected instead of silently truncated. */
    public record CrearReservaRequest(String usuarioId, String propiedadId, String fechaInicio, String fechaFin,
                                      BigDecimal huespedes) { }

    private final ReservaUseCases.CrearReserva crear;
    private final ReservaUseCases.ConfirmarReserva confirmar;

    public ReservaController(ReservaUseCases.CrearReserva crear, ReservaUseCases.ConfirmarReserva confirmar) {
        this.crear = crear;
        this.confirmar = confirmar;
    }

    @PostMapping
    public ResponseEntity<ReservaResponse> crear(@RequestBody CrearReservaRequest body,
                                                 @RequestHeader(name = "Idempotency-Key", required = false) String key,
                                                 HttpServletRequest request) {
        List<FieldError> problems = new ArrayList<>();
        UUID usuarioId = uuid(body.usuarioId(), "usuarioId", problems);
        UUID propiedadId = uuid(body.propiedadId(), "propiedadId", problems);
        LocalDate inicio = date(body.fechaInicio(), "fechaInicio", problems);
        LocalDate fin = date(body.fechaFin(), "fechaFin", problems);
        if (inicio != null && fin != null && !fin.isAfter(inicio)) {
            problems.add(new FieldError("fechaFin", "must be after fechaInicio"));
        }
        BigDecimal huespedes = body.huespedes();
        if (huespedes == null || huespedes.stripTrailingZeros().scale() > 0
                || huespedes.compareTo(BigDecimal.ONE) < 0 || huespedes.compareTo(BigDecimal.valueOf(20)) > 0) {
            problems.add(new FieldError("huespedes", "must be an integer from 1 to 20"));
        }
        if (!ReservaViews.validKey(key)) {
            problems.add(new FieldError("Idempotency-Key", "header required, 8 to 128 characters"));
        }
        if (!problems.isEmpty()) {
            throw new ValidationException("the request has invalid fields", problems);
        }
        Creation result = crear.crear(new CrearReservaCommand(ReservaViews.caller(request), usuarioId, propiedadId,
                inicio, fin, huespedes.intValueExact(), key, ReservaViews.correlationId()));
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)   // 200: a retry
                .header("Location", "/api/v1/reservas/" + result.reserva().getId().value())
                .body(ReservaResponse.of(result.reserva()));
    }

    /**
     * E-18, step confirmar-reserva. The workflow sends <sagaId>:confirmar-reserva: it is
     * accepted and not stored, since the operation creates nothing (D-C7).
     */
    @PostMapping("/{id}/confirmar")
    public ReservaResponse confirmar(@PathVariable String id,
                                     @RequestHeader(name = "Idempotency-Key", required = false) String key,
                                     HttpServletRequest request) {
        UUID reservaId = ReservaViews.requireId(id);
        requireOptionalKey(key);
        return ReservaResponse.of(confirmar.confirmar(ReservaViews.caller(request), reservaId, ReservaViews.correlationId()));
    }

    static void requireOptionalKey(String key) {
        if (key != null && !ReservaViews.validKey(key)) {
            throw new ValidationException("the request has invalid fields",
                    List.of(new FieldError("Idempotency-Key", "8 to 128 characters")));
        }
    }

    private static UUID uuid(String value, String field, List<FieldError> problems) {
        if (value == null || !ReservaViews.UUID_FORMAT.matcher(value).matches()) {
            problems.add(new FieldError(field, "must be a UUID"));
            return null;
        }
        return UUID.fromString(value);
    }

    private static LocalDate date(String value, String field, List<FieldError> problems) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException | NullPointerException e) {
            problems.add(new FieldError(field, "must be a date, YYYY-MM-DD"));
            return null;
        }
    }
}
