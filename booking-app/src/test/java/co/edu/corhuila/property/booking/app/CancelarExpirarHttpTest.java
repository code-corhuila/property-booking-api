package co.edu.corhuila.property.booking.app;

import static co.edu.corhuila.property.booking.app.ReservaFixture.SERVICE;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.edu.corhuila.property.booking.application.port.out.PropiedadCatalogPort;
import co.edu.corhuila.property.booking.application.port.out.ReservaRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** HU-BOOK-004 over HTTP (E-04), and the expiry of E-19 (ADR-014). */
@SpringBootTest
@AutoConfigureMockMvc
class CancelarExpirarHttpTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    ReservaRepository reservas;
    @MockitoBean
    PropiedadCatalogPort catalog;

    @DynamicPropertySource
    static void keys(DynamicPropertyRegistry registry) {
        TestKeys.register(registry);
    }

    @BeforeEach
    void catalog() {
        ReservaFixture.catalogKnowsEveryProperty(catalog);
    }

    ResultActions cancelar(String id, String bearer, String body) throws Exception {
        var request = post("/api/v1/reservas/" + id + "/cancelar").header("Authorization", bearer)
                .header("Idempotency-Key", UUID.randomUUID() + ":crear-reserva:compensar");
        return mvc.perform(body == null ? request : request.contentType(MediaType.APPLICATION_JSON).content(body));
    }

    ResultActions expirar(String id, String bearer) throws Exception {
        return mvc.perform(post("/api/v1/reservas/" + id + "/expirar").header("Authorization", bearer));
    }

    @Test
    void scenario1TheWorkflowCompensatesWithTheMotivoOfThePago() throws Exception {
        String id = ReservaFixture.pendiente(mvc, UUID.randomUUID());

        cancelar(id, SERVICE, "{\"motivo\":\"FONDOS_INSUFICIENTES\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.estado", is("CANCELADA")))
                .andExpect(jsonPath("$.motivoCierre", is("FONDOS_INSUFICIENTES")));
        assertEquals(List.of("ReservaCreada", "ReservaCancelada"), ReservaFixture.eventsOf(reservas, id));

        String other = ReservaFixture.pendiente(mvc, UUID.randomUUID());
        cancelar(other, SERVICE, null).andExpect(jsonPath("$.motivoCierre", is("SAGA_COMPENSADA")));
    }

    @Test
    void scenario2TheGuestWhoOwnsItCancelsAndTheBodyIsIgnored() throws Exception {
        UUID guest = UUID.randomUUID();
        String id = ReservaFixture.pendiente(mvc, guest);

        cancelar(id, ReservaFixture.person(guest, "GUEST"), "{\"motivo\":\"SAGA_COMPENSADA\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.motivoCierre", is("CANCELADA_POR_HUESPED")));
    }

    @Test
    void scenario3CancellingAgainIs200AndPublishesNoEvent() throws Exception {
        String id = ReservaFixture.pendiente(mvc, UUID.randomUUID());
        cancelar(id, SERVICE, null).andExpect(status().isOk());

        cancelar(id, SERVICE, null).andExpect(status().isOk()).andExpect(jsonPath("$.estado", is("CANCELADA")));
        assertEquals(2, ReservaFixture.eventsOf(reservas, id).size());
    }

    @Test
    void scenario4AConfirmedReservaCannotBeCancelled() throws Exception {
        String id = ReservaFixture.pendiente(mvc, UUID.randomUUID());
        mvc.perform(post("/api/v1/reservas/" + id + "/confirmar").header("Authorization", SERVICE)).andExpect(status().isOk());

        cancelar(id, SERVICE, null).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error", is("INVALID_STATUS_TRANSITION")));
    }

    @Test
    void anotherGuestOrAnAdminCannotCancelAndAServiceMotivoMustBeKnown() throws Exception {
        String id = ReservaFixture.pendiente(mvc, UUID.randomUUID());

        cancelar(id, ReservaFixture.person(UUID.randomUUID(), "GUEST"), null).andExpect(status().isForbidden());
        cancelar(id, ReservaFixture.person(UUID.randomUUID(), "ADMIN"), null).andExpect(status().isForbidden());
        cancelar(id, SERVICE, "{\"motivo\":\"CAMBIO_DE_PLANES\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0].field", is("motivo")));
        cancelar(UUID.randomUUID().toString(), SERVICE, null).andExpect(status().isNotFound());
    }

    @Test
    void expiringIsForTheWorkerAndSafeToRunTwice() throws Exception {
        String id = ReservaFixture.pendiente(mvc, UUID.randomUUID());

        expirar(id, ReservaFixture.person(UUID.randomUUID(), "ADMIN")).andExpect(status().isForbidden());
        expirar(id, SERVICE).andExpect(status().isOk())
                .andExpect(jsonPath("$.estado", is("EXPIRADA")))
                .andExpect(jsonPath("$.motivoCierre", is("TIMEOUT_PAGO_EXCEDIDO")));
        expirar(id, SERVICE).andExpect(status().isOk());
        assertEquals(List.of("ReservaCreada", "ReservaExpirada"), ReservaFixture.eventsOf(reservas, id));
        cancelar(id, SERVICE, null).andExpect(status().isUnprocessableEntity());

        String cancelled = ReservaFixture.pendiente(mvc, UUID.randomUUID());
        cancelar(cancelled, SERVICE, null).andExpect(status().isOk());
        expirar(cancelled, SERVICE).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error", is("INVALID_STATUS_TRANSITION")));
    }
}
