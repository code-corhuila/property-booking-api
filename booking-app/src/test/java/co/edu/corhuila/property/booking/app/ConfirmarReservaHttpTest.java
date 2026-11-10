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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** HU-BOOK-003 over HTTP (E-18). */
@SpringBootTest
@AutoConfigureMockMvc
class ConfirmarReservaHttpTest {

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

    ResultActions confirmar(String id, String bearer) throws Exception {
        return mvc.perform(post("/api/v1/reservas/" + id + "/confirmar").header("Authorization", bearer)
                .header("Idempotency-Key", UUID.randomUUID() + ":confirmar-reserva"));
    }

    @Test
    void scenario1APendingReservaIsConfirmedWithItsEvent() throws Exception {
        String id = ReservaFixture.pendiente(mvc, UUID.randomUUID());

        confirmar(id, SERVICE).andExpect(status().isOk())
                .andExpect(jsonPath("$.estado", is("CONFIRMADA")))
                .andExpect(jsonPath("$.motivoCierre").doesNotExist());
        assertEquals(List.of("ReservaCreada", "ReservaConfirmada"), ReservaFixture.eventsOf(reservas, id));
    }

    @Test
    void scenario2ConfirmingAgainIs422AndChangesNothing() throws Exception {
        String id = ReservaFixture.pendiente(mvc, UUID.randomUUID());
        confirmar(id, SERVICE).andExpect(status().isOk());

        confirmar(id, SERVICE).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error", is("INVALID_STATUS_TRANSITION")));
        assertEquals(2, ReservaFixture.eventsOf(reservas, id).size());
    }

    @Test
    void onlyAServiceConfirmsAndTheIdAndKeyAreChecked() throws Exception {
        UUID guest = UUID.randomUUID();
        String id = ReservaFixture.pendiente(mvc, guest);

        confirmar(id, ReservaFixture.person(guest, "GUEST")).andExpect(status().isForbidden());
        confirmar(UUID.randomUUID().toString(), SERVICE).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error", is("NOT_FOUND")));
        confirmar("not-a-uuid", SERVICE).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0].field", is("id")));
        mvc.perform(post("/api/v1/reservas/" + id + "/confirmar").header("Authorization", SERVICE)
                .header("Idempotency-Key", "short")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/reservas/" + id + "/confirmar").header("Authorization", SERVICE))
                .andExpect(status().isOk());
    }
}
