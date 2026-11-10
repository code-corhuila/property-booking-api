package co.edu.corhuila.property.booking.adapter.out.persistence;

import co.edu.corhuila.property.booking.application.port.out.OutboxEvent;
import co.edu.corhuila.property.booking.application.port.out.ReservaRepository;
import co.edu.corhuila.property.booking.application.port.out.ReservaSuperpuestaException;
import co.edu.corhuila.property.booking.domain.model.DateRange;
import co.edu.corhuila.property.booking.domain.model.EstadoReserva;
import co.edu.corhuila.property.booking.domain.model.Reserva;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * Only with PERSISTENCE=memory, for tests and local development: never a default
 * (norm 5.5.2). Same contract as the JDBC adapter, including the exclusion rule;
 * every reader gets its own copy, as it would from a database.
 */
public class InMemoryReservaRepository implements ReservaRepository {

    private final Map<UUID, Reserva> reservas = new HashMap<>();
    private final Map<String, UUID> keys = new HashMap<>();
    private final List<OutboxEvent> outbox = new ArrayList<>();

    @Override
    public synchronized Created create(Reserva reserva, String idempotencyKey, List<OutboxEvent> events) {
        UUID first = keys.get(idempotencyKey);
        if (first != null) {
            return new Created(copy(reservas.get(first)), false);
        }
        if (!findActiveOverlapping(reserva.getPropiedadId(), reserva.getFechas()).isEmpty()) {
            throw new ReservaSuperpuestaException();
        }
        reservas.put(reserva.getId().value(), copy(reserva));
        keys.put(idempotencyKey, reserva.getId().value());
        outbox.addAll(events);
        return new Created(copy(reserva), true);
    }

    @Override
    public synchronized Optional<Reserva> findById(UUID id) {
        return Optional.ofNullable(reservas.get(id)).map(InMemoryReservaRepository::copy);
    }

    @Override
    public synchronized Optional<Reserva> findByKey(String idempotencyKey) {
        return Optional.ofNullable(keys.get(idempotencyKey)).flatMap(this::findById);
    }

    @Override
    public synchronized List<Reserva> findActiveOverlapping(UUID propiedadId, DateRange fechas) {
        return reservas.values().stream()
                .filter(r -> r.getPropiedadId().equals(propiedadId) && r.getFechas().seSuperponeCon(fechas))
                .filter(r -> r.getEstado() == EstadoReserva.PENDIENTE || r.getEstado() == EstadoReserva.CONFIRMADA)
                .map(InMemoryReservaRepository::copy).toList();
    }

    @Override
    public synchronized Optional<Reserva> change(UUID id, Function<Reserva, List<OutboxEvent>> transition) {
        Optional<Reserva> current = findById(id);
        current.ifPresent(reserva -> {
            List<OutboxEvent> events = transition.apply(reserva);
            reservas.put(id, copy(reserva));
            outbox.addAll(events);
        });
        return current;
    }

    @Override
    public synchronized Page findPage(Filter f, int offset, int limit) {
        List<Reserva> matching = reservas.values().stream()
                .filter(r -> f.usuarioId() == null || r.getUsuarioId().equals(f.usuarioId()))
                .filter(r -> f.estado() == null || r.getEstado() == f.estado())
                .filter(r -> f.creadaAntesDe() == null || r.getCreatedAt().isBefore(f.creadaAntesDe()))
                .sorted(Comparator.comparing(Reserva::getCreatedAt).reversed()
                        .thenComparing(r -> r.getId().value(), Comparator.reverseOrder()))
                .toList();
        List<Reserva> page = matching.stream().skip(offset).limit(limit).map(InMemoryReservaRepository::copy).toList();
        return new Page(page, matching.size());
    }

    /** The events written so far, oldest first: what the outbox table would hold. */
    public synchronized List<OutboxEvent> outbox() {
        return List.copyOf(outbox);
    }

    private static Reserva copy(Reserva r) {
        return Reserva.restore(r.getId(), r.getPropiedadId(), r.getUsuarioId(), r.getFechas(), r.getTotal(),
                r.getEstado(), r.getMotivoCierre(), r.getCreatedAt(), r.getUpdatedAt());
    }
}
