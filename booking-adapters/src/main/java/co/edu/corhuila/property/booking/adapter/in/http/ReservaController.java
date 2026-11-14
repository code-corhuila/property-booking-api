package co.edu.corhuila.property.booking.adapter.in.http;

import co.edu.corhuila.property.booking.adapter.in.http.ApiError.FieldError;
import co.edu.corhuila.property.booking.adapter.in.http.ApiError.ValidationException;
import co.edu.corhuila.property.booking.adapter.in.http.ReservaViews.ReservaResponse;
import co.edu.corhuila.property.booking.application.port.in.Caller;
import co.edu.corhuila.property.booking.application.port.in.ReservaUseCases;
import co.edu.corhuila.property.booking.application.port.in.ReservaUseCases.CrearReservaCommand;
import co.edu.corhuila.property.booking.application.port.in.ReservaUseCases.Creation;
import co.edu.corhuila.property.booking.application.port.in.ReservaUseCases.ListarReservasQuery;
import co.edu.corhuila.property.booking.application.port.in.ReservaUseCases.ReservaPage;
import co.edu.corhuila.property.booking.domain.model.EstadoReserva;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
    private final ReservaUseCases.CancelarReserva cancelar;
    private final ReservaUseCases.ExpirarReserva expirar;
    private final ReservaUseCases.ObtenerReserva obtener;
    private final ReservaUseCases.ListarReservas listar;

    /** The codes a service may send as motivo (booking-service.yaml, CancelarReservaRequest). */
    static final List<String> MOTIVOS = List.of("SAGA_COMPENSADA", "FONDOS_INSUFICIENTES", "TARJETA_RECHAZADA",
            "TARJETA_VENCIDA", "PASARELA_NO_DISPONIBLE");

    public record CancelarReservaRequest(String motivo) { }

    public record PageMeta(int page, int limit, long total, long totalPages) { }

    public record PageResponse(List<ReservaResponse> data, PageMeta meta) { }

    public ReservaController(ReservaUseCases.CrearReserva crear, ReservaUseCases.ConfirmarReserva confirmar,
                             ReservaUseCases.CancelarReserva cancelar, ReservaUseCases.ExpirarReserva expirar,
                             ReservaUseCases.ObtenerReserva obtener, ReservaUseCases.ListarReservas listar) {
        this.crear = crear;
        this.confirmar = confirmar;
        this.cancelar = cancelar;
        this.expirar = expirar;
        this.obtener = obtener;
        this.listar = listar;
    }

    /** E-03. */
    @GetMapping("/{id}")
    public ReservaResponse obtener(@PathVariable String id, HttpServletRequest request) {
        return ReservaResponse.of(obtener.obtener(ReservaViews.caller(request), ReservaViews.requireId(id)));
    }

    /** E-02: {data, meta}, page from 1, limit 1 to 100 (20 by default), most recent first (D-C3). */
    @GetMapping
    public PageResponse listar(@RequestParam(required = false) String estado,
                               @RequestParam(required = false) String creadaAntesDe,
                               @RequestParam(required = false) String page,
                               @RequestParam(required = false) String limit, HttpServletRequest request) {
        List<FieldError> problems = new ArrayList<>();
        EstadoReserva estadoFilter = null;
        if (estado != null) {
            try {
                estadoFilter = EstadoReserva.valueOf(estado);
            } catch (IllegalArgumentException e) {
                problems.add(new FieldError("estado", "must be PENDIENTE, CONFIRMADA, CANCELADA or EXPIRADA"));
            }
        }
        Instant before = null;
        if (creadaAntesDe != null) {
            try {
                before = OffsetDateTime.parse(creadaAntesDe).toInstant();
            } catch (DateTimeParseException e) {
                problems.add(new FieldError("creadaAntesDe", "must be an RFC 3339 date-time"));
            }
        }
        int pageNumber = number(page, 1, Integer.MAX_VALUE, "page", "must be an integer >= 1", problems);
        int pageSize = number(limit, 20, 100, "limit", "must be an integer between 1 and 100", problems);
        if (!problems.isEmpty()) {
            throw new ValidationException("the query has invalid parameters", problems);
        }
        ReservaPage result = listar.listar(ReservaViews.caller(request),
                new ListarReservasQuery(estadoFilter, before, pageNumber, pageSize));
        long totalPages = (result.total() + result.limit() - 1) / result.limit();
        return new PageResponse(result.items().stream().map(ReservaResponse::of).toList(),
                new PageMeta(result.page(), result.limit(), result.total(), totalPages));
    }

    private static int number(String raw, int fallback, int max, String field, String message, List<FieldError> problems) {
        if (raw == null) {
            return fallback;
        }
        try {
            int n = Integer.parseInt(raw);
            if (n >= 1 && n <= max) {
                return n;
            }
        } catch (NumberFormatException ignored) {
            // reported below
        }
        problems.add(new FieldError(field, message));
        return fallback;
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

    /**
     * E-04. The workflow sends <sagaId>:crear-reserva:compensar, accepted and not stored.
     * The body is read only from a service; the body of a person is ignored.
     */
    @PostMapping("/{id}/cancelar")
    public ReservaResponse cancelar(@PathVariable String id, @RequestBody(required = false) CancelarReservaRequest body,
                                    @RequestHeader(name = "Idempotency-Key", required = false) String key,
                                    HttpServletRequest request) {
        UUID reservaId = ReservaViews.requireId(id);
        requireOptionalKey(key);
        Caller caller = ReservaViews.caller(request);
        String motivo = caller.service() && body != null ? body.motivo() : null;
        if (motivo != null && !MOTIVOS.contains(motivo)) {
            throw new ValidationException("the request has invalid fields",
                    List.of(new FieldError("motivo", "must be one of " + String.join(", ", MOTIVOS))));
        }
        return ReservaResponse.of(cancelar.cancelar(caller, reservaId, motivo, ReservaViews.correlationId()));
    }

    /** E-19, called by the job expire-stale-reservations of property-worker (ADR-014). */
    @PostMapping("/{id}/expirar")
    public ReservaResponse expirar(@PathVariable String id, HttpServletRequest request) {
        UUID reservaId = ReservaViews.requireId(id);
        return ReservaResponse.of(expirar.expirar(ReservaViews.caller(request), reservaId, ReservaViews.correlationId()));
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
