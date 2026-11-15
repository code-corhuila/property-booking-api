package co.edu.corhuila.property.booking.application.usecase;

import co.edu.corhuila.property.booking.application.port.in.Caller;
import co.edu.corhuila.property.booking.application.port.in.ReservaNoEncontradaException;
import co.edu.corhuila.property.booking.application.port.in.ReservaUseCases.ConfirmarReserva;
import co.edu.corhuila.property.booking.application.port.in.ReservaUseCases.CrearReserva;
import co.edu.corhuila.property.booking.application.port.in.ReservaUseCases.CrearReservaCommand;
import co.edu.corhuila.property.booking.application.port.in.ReservaUseCases.Creation;
import co.edu.corhuila.property.booking.application.port.out.PropiedadCatalogPort;
import co.edu.corhuila.property.booking.application.port.out.PropiedadCatalogPort.Propiedad;
import co.edu.corhuila.property.booking.application.port.out.ReservaRepository;
import co.edu.corhuila.property.booking.domain.model.DateRange;
import co.edu.corhuila.property.booking.domain.model.DomainException;
import co.edu.corhuila.property.booking.domain.model.Money;
import co.edu.corhuila.property.booking.domain.model.Reserva;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

/** The use cases of the Reserva. It orchestrates; the rules live in Reserva and DateRange. */
public class ReservaService implements CrearReserva, ConfirmarReserva {

    private final ReservaRepository reservas;
    private final PropiedadCatalogPort catalog;

    public ReservaService(ReservaRepository reservas, PropiedadCatalogPort catalog) {
        this.reservas = reservas;
        this.catalog = catalog;
    }

    /**
     * HU-BOOK-001. Service credential only (D-C14). A repeated key answers the first
     * Reserva before anything else, Catalog included. Catalog is read before the
     * transaction (ADR-015, rule 2); the total is the price of a night times the nights
     * of the half-open range, in the currency of the property. INV-001 is checked by
     * the domain, and the exclusion constraint stops whatever races past it.
     */
    @Override
    public Creation crear(CrearReservaCommand c) {
        c.caller().requireService();
        Optional<Reserva> first = reservas.findByKey(c.idempotencyKey());
        if (first.isPresent()) {
            return new Creation(first.get(), false);
        }
        DateRange fechas = new DateRange(c.fechaInicio(), c.fechaFin());
        Propiedad propiedad = catalog.find(c.propiedadId(), c.correlationId())
                .orElseThrow(() -> new DomainException("the property " + c.propiedadId() + " does not exist"));
        if (c.huespedes() > propiedad.capacidad()) {
            throw new DomainException("huespedes (" + c.huespedes() + ") exceeds the capacity of the property ("
                    + propiedad.capacidad() + ")");
        }
        long nights = ChronoUnit.DAYS.between(fechas.getFechaInicio(), fechas.getFechaFin());
        Money total = new Money(Math.multiplyExact(propiedad.precioNocheCents(), nights), propiedad.moneda());

        Reserva reserva = Reserva.crear(c.propiedadId(), c.usuarioId(), fechas, total);
        reserva.validarSinSuperposicion(reservas.findActiveOverlapping(c.propiedadId(), fechas));
        ReservaRepository.Created created = reservas.create(reserva, c.idempotencyKey(),
                ReservaEvents.of(reserva, c.correlationId()));
        return new Creation(created.reserva(), created.created());
    }

    /**
     * HU-BOOK-003. Service credential only. Only a PENDIENTE Reserva is confirmed: any
     * other state, CONFIRMADA included, is 422 and changes nothing (INV-002, D-C6). The
     * state and ReservaConfirmada are written in one transaction.
     */
    @Override
    public Reserva confirmar(Caller caller, UUID id, String correlationId) {
        caller.requireService();
        return reservas.change(id, reserva -> {
            reserva.confirmar();
            return ReservaEvents.of(reserva, correlationId);
        }).orElseThrow(() -> new ReservaNoEncontradaException(id));
    }
}
