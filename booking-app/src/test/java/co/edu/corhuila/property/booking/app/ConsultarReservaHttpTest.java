package co.edu.corhuila.property.booking.app;

import static co.edu.corhuila.property.booking.app.ReservaFixture.SERVICE;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.edu.corhuila.property.booking.application.port.out.PropiedadCatalogPort;
import java.time.Instant;
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

/** HU-BOOK-006 over HTTP (E-02, E-03), and the listing contract of Annex C. */
@SpringBootTest
@AutoConfigureMockMvc
class ConsultarReservaHttpTest {

    @Autowired
    MockMvc mvc;
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

    @Test
    void scenario1And2AGuestReadsTheStateOfHerReservaAndAnUnknownOneIs404() throws Exception {
        UUID guest = UUID.randomUUID();
        String id = ReservaFixture.pendiente(mvc, guest);

        mvc.perform(get("/api/v1/reservas/" + id).header("Authorization", ReservaFixture.person(guest, "GUEST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(id)))
                .andExpect(jsonPath("$.estado", is("PENDIENTE")))
                .andExpect(jsonPath("$.montoTotalCents", is(20000000)))
                .andExpect(jsonPath("$.createdAt").isString());
        mvc.perform(get("/api/v1/reservas/" + UUID.randomUUID()).header("Authorization", SERVICE))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/reservas/not-a-uuid").header("Authorization", SERVICE))
                .andExpect(status().isBadRequest());
    }

    @Test
    void scenario3AnotherGuestGets403ButAnAdminAndAServiceRead() throws Exception {
        String id = ReservaFixture.pendiente(mvc, UUID.randomUUID());

        mvc.perform(get("/api/v1/reservas/" + id).header("Authorization", ReservaFixture.person(UUID.randomUUID(), "HOST")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error", is("FORBIDDEN")));
        mvc.perform(get("/api/v1/reservas/" + id).header("Authorization", ReservaFixture.person(UUID.randomUUID(), "ADMIN")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/reservas/" + id).header("Authorization", SERVICE)).andExpect(status().isOk());
    }

    @Test
    void scenario4AGuestListsOnlyHerOwnPaginatedMostRecentFirst() throws Exception {
        UUID guest = UUID.randomUUID();
        String first = ReservaFixture.pendiente(mvc, guest);
        Thread.sleep(5);
        String second = ReservaFixture.pendiente(mvc, guest);
        ReservaFixture.pendiente(mvc, UUID.randomUUID());
        String bearer = ReservaFixture.person(guest, "GUEST");

        mvc.perform(get("/api/v1/reservas?limit=1").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()", is(1)))
                .andExpect(jsonPath("$.data[0].id", is(second)))
                .andExpect(jsonPath("$.meta.page", is(1)))
                .andExpect(jsonPath("$.meta.limit", is(1)))
                .andExpect(jsonPath("$.meta.total", is(2)))
                .andExpect(jsonPath("$.meta.totalPages", is(2)));
        mvc.perform(get("/api/v1/reservas?page=2&limit=1").header("Authorization", bearer))
                .andExpect(jsonPath("$.data[0].id", is(first)));
        mvc.perform(get("/api/v1/reservas").header("Authorization", bearer))
                .andExpect(jsonPath("$.meta.limit", is(20)))
                .andExpect(jsonPath("$.data[*].usuarioId", everyItem(is(guest.toString()))));
    }

    @Test
    void scenario5AServiceListsThePendingOnesOfEveryGuestCreatedBeforeAnInstant() throws Exception {
        ReservaFixture.pendiente(mvc, UUID.randomUUID());
        ReservaFixture.pendiente(mvc, UUID.randomUUID());
        String later = Instant.now().plusSeconds(60).toString();

        mvc.perform(get("/api/v1/reservas?estado=PENDIENTE&creadaAntesDe=" + later + "&limit=100").header("Authorization", SERVICE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].estado", everyItem(is("PENDIENTE"))));
        mvc.perform(get("/api/v1/reservas?creadaAntesDe=2000-01-01T00:00:00Z").header("Authorization", SERVICE))
                .andExpect(jsonPath("$.meta.total", is(0)))
                .andExpect(jsonPath("$.data.length()", is(0)));
    }

    @Test
    void anOutOfRangeLimitOrAnUnknownFilterIs400() throws Exception {
        mvc.perform(get("/api/v1/reservas?limit=101&estado=PAGADA&creadaAntesDe=ayer&page=0").header("Authorization", SERVICE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.length()", is(4)));
    }
}
