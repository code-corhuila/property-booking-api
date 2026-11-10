package co.edu.corhuila.property.booking.adapter.out.persistence;

import co.edu.corhuila.property.booking.application.port.out.OutboxEvent;
import co.edu.corhuila.property.booking.application.port.out.ReservaRepository;
import co.edu.corhuila.property.booking.application.port.out.ReservaSuperpuestaException;
import co.edu.corhuila.property.booking.domain.model.DateRange;
import co.edu.corhuila.property.booking.domain.model.EstadoReserva;
import co.edu.corhuila.property.booking.domain.model.Money;
import co.edu.corhuila.property.booking.domain.model.Reserva;
import co.edu.corhuila.property.booking.domain.model.ReservaId;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reads and writes the schema owned by property-booking-db, as booking_app. It knows
 * the column names; the domain does not (models.md, naming note).
 */
public class JdbcReservaRepository implements ReservaRepository {

    private static final String COLUMNS = "r.id, r.property_id, r.guest_id, r.start_date, r.end_date, "
            + "r.total_amount_cents, r.currency, r.status, r.closure_reason, r.created_at, r.updated_at";
    /** SQLSTATE of an exclusion constraint violation: excl_reservation_overlapping_dates (INV-001). */
    private static final String EXCLUSION_VIOLATION = "23P01";
    private static final int MAX_ATTEMPTS = 3;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper json = new ObjectMapper();

    public JdbcReservaRepository(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    /**
     * Two inserts that conflict on the exclusion constraint can each wait for the other:
     * PostgreSQL aborts one of them as a deadlock. Running it again settles it, since
     * the other one has finished by then: it finds the dates taken (422) or its key.
     */
    @Override
    public Created create(Reserva r, String idempotencyKey, List<OutboxEvent> events) {
        for (int attempt = 1; ; attempt++) {
            try {
                return createOnce(r, idempotencyKey, events);
            } catch (PessimisticLockingFailureException e) {
                if (attempt == MAX_ATTEMPTS) {
                    throw e;
                }
            }
        }
    }

    private Created createOnce(Reserva r, String idempotencyKey, List<OutboxEvent> events) {
        Created created = tx.execute(status -> {
            Optional<Reserva> first = findByKey(idempotencyKey);
            if (first.isPresent()) {
                return new Created(first.get(), false);
            }
            try {
                jdbc.update("INSERT INTO booking.reservation (id, property_id, guest_id, start_date, end_date, "
                                + "total_amount_cents, currency, status, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        r.getId().value(), r.getPropiedadId(), r.getUsuarioId(), r.getFechas().getFechaInicio(),
                        r.getFechas().getFechaFin(), r.getTotal().getMontoCents(), r.getTotal().getMoneda(),
                        r.getEstado().name(), utc(r.getCreatedAt()), utc(r.getUpdatedAt()));
            } catch (DataIntegrityViolationException e) {
                if (!isExclusionViolation(e)) {
                    throw e;
                }
                status.setRollbackOnly();
                return null;
            }
            int inserted = jdbc.update("INSERT INTO booking.idempotency_key (key, reservation_id, created_at) "
                    + "VALUES (?, ?, ?) ON CONFLICT (key) DO NOTHING", idempotencyKey, r.getId().value(), utc(r.getCreatedAt()));
            if (inserted == 0) {
                status.setRollbackOnly();
                return null;
            }
            writeOutbox(events);
            return new Created(r, true);
        });
        if (created != null) {
            return created;
        }
        // Rolled back: a request with the same key won the race (its Reserva is returned),
        // or the exclusion constraint rejected the dates.
        return findByKey(idempotencyKey).map(first -> new Created(first, false))
                .orElseThrow(ReservaSuperpuestaException::new);
    }

    @Override
    public Optional<Reserva> findById(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM booking.reservation r WHERE r.id = ?", (rs, n) -> map(rs), id)
                .stream().findFirst();
    }

    @Override
    public Optional<Reserva> findByKey(String idempotencyKey) {
        return jdbc.query("SELECT " + COLUMNS + " FROM booking.idempotency_key k JOIN booking.reservation r"
                + " ON r.id = k.reservation_id WHERE k.key = ?", (rs, n) -> map(rs), idempotencyKey).stream().findFirst();
    }

    @Override
    public List<Reserva> findActiveOverlapping(UUID propiedadId, DateRange f) {
        return jdbc.query("SELECT " + COLUMNS + " FROM booking.reservation r WHERE r.property_id = ?"
                        + " AND r.status IN ('PENDIENTE', 'CONFIRMADA')"
                        + " AND daterange(r.start_date, r.end_date, '[)') && daterange(?, ?, '[)')",
                (rs, n) -> map(rs), propiedadId, f.getFechaInicio(), f.getFechaFin());
    }

    @Override
    public Optional<Reserva> change(UUID id, Function<Reserva, List<OutboxEvent>> transition) {
        return tx.execute(status -> {
            // FOR UPDATE: two transitions of the same Reserva run one after the other.
            Optional<Reserva> current = jdbc.query("SELECT " + COLUMNS + " FROM booking.reservation r WHERE r.id = ?"
                    + " FOR UPDATE", (rs, n) -> map(rs), id).stream().findFirst();
            current.ifPresent(r -> {
                List<OutboxEvent> events = transition.apply(r);
                if (!events.isEmpty()) {
                    jdbc.update("UPDATE booking.reservation SET status = ?, closure_reason = ?, updated_at = ? WHERE id = ?",
                            r.getEstado().name(), r.getMotivoCierre(), utc(r.getUpdatedAt()), id);
                    writeOutbox(events);
                }
            });
            return current;
        });
    }

    @Override
    public Page findPage(Filter f, int offset, int limit) {
        // Only the clauses that apply are added, and every value stays a parameter.
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (f.usuarioId() != null) {
            where.append(" AND r.guest_id = ?");
            args.add(f.usuarioId());
        }
        if (f.estado() != null) {
            where.append(" AND r.status = ?");
            args.add(f.estado().name());
        }
        if (f.creadaAntesDe() != null) {
            where.append(" AND r.created_at < ?");
            args.add(utc(f.creadaAntesDe()));
        }
        Long total = jdbc.queryForObject("SELECT count(*) FROM booking.reservation r" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(limit);
        pageArgs.add(offset);
        List<Reserva> items = jdbc.query("SELECT " + COLUMNS + " FROM booking.reservation r" + where
                + " ORDER BY r.created_at DESC, r.id DESC LIMIT ? OFFSET ?", (rs, n) -> map(rs), pageArgs.toArray());
        return new Page(items, total == null ? 0 : total);
    }

    private void writeOutbox(List<OutboxEvent> events) {
        for (OutboxEvent e : events) {
            try {
                jdbc.update("INSERT INTO booking.outbox_event (id, aggregate_id, event_type, payload) VALUES (?, ?, ?, ?::jsonb)",
                        e.id(), e.aggregateId(), e.eventType(), json.writeValueAsString(e.envelope()));
            } catch (JsonProcessingException ex) {
                throw new IllegalStateException("the event cannot be written", ex);
            }
        }
    }

    private static boolean isExclusionViolation(DataIntegrityViolationException e) {
        return e.getMostSpecificCause() instanceof SQLException sql && EXCLUSION_VIOLATION.equals(sql.getSQLState());
    }

    private static Reserva map(ResultSet rs) throws SQLException {
        return Reserva.restore(new ReservaId(rs.getObject("id", UUID.class)), rs.getObject("property_id", UUID.class),
                rs.getObject("guest_id", UUID.class),
                new DateRange(rs.getObject("start_date", LocalDate.class), rs.getObject("end_date", LocalDate.class)),
                new Money(rs.getLong("total_amount_cents"), rs.getString("currency")),
                EstadoReserva.valueOf(rs.getString("status")), rs.getString("closure_reason"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
