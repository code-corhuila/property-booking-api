package co.edu.corhuila.property.booking.domain.model;

import java.time.LocalDate;
import java.util.Objects;

/**
 * The requested booking dates. Half-open: fechaInicio is the check-in day and
 * fechaFin the check-out day, which is not booked (AGGR-INV-001).
 */
public final class DateRange {

    private final LocalDate fechaInicio;
    private final LocalDate fechaFin;

    public DateRange(LocalDate fechaInicio, LocalDate fechaFin) {
        if (fechaInicio == null || fechaFin == null) {
            throw new DomainException("Las fechas no pueden ser nulas");
        }
        if (!fechaInicio.isBefore(fechaFin)) {
            throw new DomainException("fechaInicio debe ser estrictamente anterior a fechaFin");
        }
        this.fechaInicio = fechaInicio;
        this.fechaFin = fechaFin;
    }

    public boolean seSuperponeCon(DateRange otro) {
        return this.fechaInicio.isBefore(otro.fechaFin)
            && otro.fechaInicio.isBefore(this.fechaFin);
    }

    public LocalDate getFechaInicio() { return fechaInicio; }
    public LocalDate getFechaFin() { return fechaFin; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DateRange other)) return false;
        return fechaInicio.equals(other.fechaInicio) && fechaFin.equals(other.fechaFin);
    }

    @Override
    public int hashCode() {
        return Objects.hash(fechaInicio, fechaFin);
    }
}
