package co.edu.corhuila.property.booking.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class DateRangeTest {

    static DateRange rango(String inicio, String fin) {
        return new DateRange(LocalDate.parse(inicio), LocalDate.parse(fin));
    }

    @Test
    void rejectsMissingDates() {
        assertThrows(DomainException.class, () -> new DateRange(null, LocalDate.parse("2026-12-15")));
        assertThrows(DomainException.class, () -> new DateRange(LocalDate.parse("2026-12-10"), null));
    }

    @Test
    void fechaInicioMustBeStrictlyBeforeFechaFin() {
        assertThrows(DomainException.class, () -> rango("2026-12-15", "2026-12-15"));
        assertThrows(DomainException.class, () -> rango("2026-12-16", "2026-12-15"));
    }

    @Test
    void overlappingRangesOverlap() {
        assertTrue(rango("2026-12-10", "2026-12-15").seSuperponeCon(rango("2026-12-14", "2026-12-18")));
        assertTrue(rango("2026-12-10", "2026-12-15").seSuperponeCon(rango("2026-12-11", "2026-12-12")));
    }

    @Test
    void theCheckOutDayIsFree() {
        assertFalse(rango("2026-12-10", "2026-12-15").seSuperponeCon(rango("2026-12-15", "2026-12-18")));
        assertFalse(rango("2026-12-15", "2026-12-18").seSuperponeCon(rango("2026-12-10", "2026-12-15")));
    }

    @Test
    void isAValueObject() {
        assertEquals(rango("2026-12-10", "2026-12-15"), rango("2026-12-10", "2026-12-15"));
        assertEquals(rango("2026-12-10", "2026-12-15").hashCode(), rango("2026-12-10", "2026-12-15").hashCode());
    }
}
