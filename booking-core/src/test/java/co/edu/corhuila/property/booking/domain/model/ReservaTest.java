package co.edu.corhuila.property.booking.domain.model;

import static co.edu.corhuila.property.booking.domain.model.DateRangeTest.rango;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReservaTest {

    static final UUID PROPIEDAD = UUID.fromString("11111111-1111-4111-8111-111111111111");
    static final UUID OTRA_PROPIEDAD = UUID.fromString("44444444-4444-4444-8444-444444444444");
    static final UUID HUESPED = UUID.fromString("22222222-2222-4222-8222-222222222222");

    static Reserva reserva(UUID propiedad, String inicio, String fin) {
        return Reserva.crear(propiedad, HUESPED, rango(inicio, fin), new Money(450000, "COP"));
    }

    @Test
    void crearLeavesItPendienteAndRecordsReservaCreada() {
        Reserva reserva = reserva(PROPIEDAD, "2026-12-10", "2026-12-15");

        assertEquals(EstadoReserva.PENDIENTE, reserva.getEstado());
        assertNull(reserva.getMotivoCierre());
        assertEquals(1, reserva.obtenerEventosDominio().size());
        ReservaCreadaEvent evento = assertInstanceOf(ReservaCreadaEvent.class, reserva.obtenerEventosDominio().get(0));
        assertEquals(reserva.getId(), evento.reservaId());
    }

    @Test
    void inv003TheTotalMustBePositive() {
        assertThrows(DomainException.class,
                () -> Reserva.crear(PROPIEDAD, HUESPED, rango("2026-12-10", "2026-12-15"), new Money(0, "COP")));
    }

    @Test
    void confirmarMovesAPendienteToConfirmada() {
        Reserva reserva = reserva(PROPIEDAD, "2026-12-10", "2026-12-15");
        reserva.confirmar();

        assertEquals(EstadoReserva.CONFIRMADA, reserva.getEstado());
        assertInstanceOf(ReservaConfirmadaEvent.class, reserva.obtenerEventosDominio().get(1));
    }

    @Test
    void inv002ConfirmingTwiceIsAViolation() {
        Reserva reserva = reserva(PROPIEDAD, "2026-12-10", "2026-12-15");
        reserva.confirmar();

        assertThrows(InvalidStatusTransitionException.class, reserva::confirmar);
    }

    @Test
    void cancelarRecordsTheMotivoAndRepeatingItChangesNothing() {
        Reserva reserva = reserva(PROPIEDAD, "2026-12-10", "2026-12-15");
        reserva.cancelar("FONDOS_INSUFICIENTES");
        reserva.cancelar("SAGA_COMPENSADA");

        assertEquals(EstadoReserva.CANCELADA, reserva.getEstado());
        assertEquals("FONDOS_INSUFICIENTES", reserva.getMotivoCierre());
        assertInstanceOf(ReservaCanceladaEvent.class, reserva.obtenerEventosDominio().get(1));
        assertEquals(2, reserva.obtenerEventosDominio().size());
    }

    @Test
    void expirarRecordsTheMotivoAndRepeatingItChangesNothing() {
        Reserva reserva = reserva(PROPIEDAD, "2026-12-10", "2026-12-15");
        reserva.expirar("TIMEOUT_PAGO_EXCEDIDO");
        reserva.expirar("TIMEOUT_PAGO_EXCEDIDO");

        assertEquals(EstadoReserva.EXPIRADA, reserva.getEstado());
        assertInstanceOf(ReservaExpiradaEvent.class, reserva.obtenerEventosDominio().get(1));
        assertEquals(2, reserva.obtenerEventosDominio().size());
    }

    @Test
    void inv002TerminalStatesDoNotMove() {
        Reserva confirmada = reserva(PROPIEDAD, "2026-12-10", "2026-12-15");
        confirmada.confirmar();
        assertThrows(InvalidStatusTransitionException.class, () -> confirmada.cancelar("CANCELADA_POR_HUESPED"));
        assertThrows(InvalidStatusTransitionException.class, () -> confirmada.expirar("TIMEOUT_PAGO_EXCEDIDO"));

        Reserva cancelada = reserva(PROPIEDAD, "2026-12-10", "2026-12-15");
        cancelada.cancelar("SAGA_COMPENSADA");
        assertThrows(InvalidStatusTransitionException.class, cancelada::confirmar);
        assertThrows(InvalidStatusTransitionException.class, () -> cancelada.expirar("TIMEOUT_PAGO_EXCEDIDO"));
    }

    @Test
    void inv001RejectsAnOverlapWithAPendienteOrConfirmadaOfTheSameProperty() {
        Reserva pendiente = reserva(PROPIEDAD, "2026-12-10", "2026-12-15");
        Reserva confirmada = reserva(PROPIEDAD, "2026-12-15", "2026-12-18");
        confirmada.confirmar();

        assertThrows(DomainException.class,
                () -> reserva(PROPIEDAD, "2026-12-12", "2026-12-14").validarSinSuperposicion(List.of(pendiente)));
        assertThrows(DomainException.class,
                () -> reserva(PROPIEDAD, "2026-12-17", "2026-12-20").validarSinSuperposicion(List.of(confirmada)));
    }

    @Test
    void inv001AllowsWhatDoesNotHoldTheDates() {
        Reserva cancelada = reserva(PROPIEDAD, "2026-12-10", "2026-12-15");
        cancelada.cancelar("CANCELADA_POR_HUESPED");
        Reserva otraPropiedad = reserva(OTRA_PROPIEDAD, "2026-12-10", "2026-12-15");
        Reserva hastaElDia15 = reserva(PROPIEDAD, "2026-12-10", "2026-12-15");
        Reserva nueva = reserva(PROPIEDAD, "2026-12-15", "2026-12-18");

        assertDoesNotThrow(() -> nueva.validarSinSuperposicion(List.of(cancelada, otraPropiedad, hastaElDia15)));
        assertDoesNotThrow(() -> nueva.validarSinSuperposicion(List.of(nueva)));
    }
}
