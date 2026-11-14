package co.edu.corhuila.property.booking.app;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.edu.corhuila.property.booking.adapter.out.persistence.InMemoryReservaRepository;
import co.edu.corhuila.property.booking.application.port.out.CatalogNoDisponibleException;
import co.edu.corhuila.property.booking.application.port.out.OutboxEvent;
import co.edu.corhuila.property.booking.application.port.out.PropiedadCatalogPort;
import co.edu.corhuila.property.booking.application.port.out.PropiedadCatalogPort.Propiedad;
import co.edu.corhuila.property.booking.application.port.out.ReservaRepository;
import java.util.Map;
import java.util.Optional;
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

/** HU-BOOK-001 and HU-BOOK-002 over HTTP (E-01), with Catalog simulated behind its port. */
@SpringBootTest
@AutoConfigureMockMvc
class CrearReservaHttpTest {

    static final String SERVICE = TestKeys.bearer("\"sub\":\"svc-workflow\",\"scope\":\"service\"");

    @Autowired
    MockMvc mvc;
    @Autowired
    ReservaRepository reservas;
    @MockitoBean
    PropiedadCatalogPort catalog;

    UUID propiedad;

    @DynamicPropertySource
    static void keys(DynamicPropertyRegistry registry) {
        TestKeys.register(registry);
    }

    @BeforeEach
    void aPropertyOf500000PerNightFor4Guests() {
        propiedad = UUID.randomUUID();
        when(catalog.find(eq(propiedad), anyString())).thenReturn(Optional.of(new Propiedad(propiedad, 50000000L, "COP", 4)));
    }

    ResultActions crear(String inicio, String fin, int huespedes, String key, String bearer) throws Exception {
        String body = "{\"usuarioId\":\"c8f2a1b0-4444-4c3e-9a2d-000000000004\",\"propiedadId\":\"" + propiedad
                + "\",\"fechaInicio\":\"" + inicio + "\",\"fechaFin\":\"" + fin + "\",\"huespedes\":" + huespedes + "}";
        return mvc.perform(post("/api/v1/reservas").header("Authorization", bearer).header("Idempotency-Key", key)
                .header("X-Correlation-Id", "saga-trace-7").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    static String key() {
        return UUID.randomUUID() + ":crear-reserva";
    }

    @Test
    void scenario1APendingReservaPricedFromCatalogWithItsEventInTheOutbox() throws Exception {
        String id = com.jayway.jsonpath.JsonPath.read(crear("2026-09-10", "2026-09-15", 2, key(), SERVICE)
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/v1/reservas/")))
                .andExpect(jsonPath("$.estado", is("PENDIENTE")))
                .andExpect(jsonPath("$.montoTotalCents", is(250000000)))
                .andExpect(jsonPath("$.moneda", is("COP")))
                .andExpect(jsonPath("$.motivoCierre").doesNotExist())
                .andReturn().getResponse().getContentAsString(), "$.id");

        OutboxEvent event = ((InMemoryReservaRepository) reservas).outbox().stream()
                .filter(e -> e.aggregateId().toString().equals(id)).findFirst().orElseThrow();
        assertEquals("ReservaCreada", event.eventType());
        assertEquals("saga-trace-7", ((Map<?, ?>) event.envelope().get("metadata")).get("correlationId"));
        verify(catalog).find(eq(propiedad), eq("saga-trace-7"));
    }

    @Test
    void scenario2DatesAlreadyTakenAre422AndNothingIsCreated() throws Exception {
        crear("2026-09-10", "2026-09-15", 2, key(), SERVICE).andExpect(status().isCreated());

        crear("2026-09-12", "2026-09-20", 2, key(), SERVICE)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error", is("BUSINESS_RULE_VIOLATION")))
                .andExpect(jsonPath("$.message", containsString("INV-001")));
        crear("2026-09-15", "2026-09-17", 2, key(), SERVICE).andExpect(status().isCreated());
    }

    @Test
    void scenario3UnknownPropertyOrTooManyGuestsAre422() throws Exception {
        crear("2026-09-10", "2026-09-15", 5, key(), SERVICE)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message", containsString("capacity")));

        when(catalog.find(eq(propiedad), anyString())).thenReturn(Optional.empty());
        crear("2026-09-10", "2026-09-15", 2, key(), SERVICE)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error", is("BUSINESS_RULE_VIOLATION")))
                .andExpect(jsonPath("$.message", containsString("does not exist")));
    }

    @Test
    void scenario4ARepeatedKeyAnswers200WithTheSameReservaWithoutAskingCatalog() throws Exception {
        String key = key();
        String first = crear("2026-10-01", "2026-10-03", 2, key, SERVICE).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        when(catalog.find(any(), anyString())).thenThrow(new CatalogNoDisponibleException("down", null));
        crear("2026-10-01", "2026-10-03", 2, key, SERVICE)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is((String) com.jayway.jsonpath.JsonPath.read(first, "$.id"))));
    }

    @Test
    void whenCatalogDoesNotAnswerItIs500AndNothingIsCreated() throws Exception {
        when(catalog.find(any(), anyString())).thenThrow(new CatalogNoDisponibleException("timeout", null));

        crear("2026-11-01", "2026-11-03", 2, key(), SERVICE)
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error", is("INTERNAL_ERROR")))
                .andExpect(jsonPath("$.message", is("internal server error")));
        assertEquals(0, reservas.findActiveOverlapping(propiedad, new co.edu.corhuila.property.booking.domain.model.DateRange(
                java.time.LocalDate.parse("2026-11-01"), java.time.LocalDate.parse("2026-11-03"))).size());
    }

    @Test
    void onlyAServiceCredentialCreates() throws Exception {
        crear("2026-09-10", "2026-09-15", 2, key(), TestKeys.bearer("\"sub\":\"" + UUID.randomUUID() + "\",\"roles\":[\"ADMIN\"]"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error", is("FORBIDDEN")));
        mvc.perform(post("/api/v1/reservas").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void everyInvalidFieldAndTheKeyAreNamed() throws Exception {
        mvc.perform(post("/api/v1/reservas").header("Authorization", SERVICE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"usuarioId\":\"x\",\"fechaInicio\":\"2026-09-15\",\"fechaFin\":\"2026-09-15\",\"huespedes\":2.5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("VALIDATION_ERROR")))
                .andExpect(jsonPath("$.details[*].field",
                        containsInAnyOrder("usuarioId", "propiedadId", "fechaFin", "huespedes", "Idempotency-Key")));
        crear("2026-09-10", "2026-09-15", 21, key(), SERVICE).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0].field", is("huespedes")));
    }
}
