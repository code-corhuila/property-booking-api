package co.edu.corhuila.property.booking.app;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.edu.corhuila.property.booking.adapter.out.persistence.InMemoryReservaRepository;
import co.edu.corhuila.property.booking.application.port.out.PropiedadCatalogPort;
import co.edu.corhuila.property.booking.application.port.out.PropiedadCatalogPort.Propiedad;
import co.edu.corhuila.property.booking.application.port.out.ReservaRepository;
import com.jayway.jsonpath.JsonPath;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Creates Reservas through E-01 for the tests of the operations that follow it. */
final class ReservaFixture {

    static final String SERVICE = TestKeys.bearer("\"sub\":\"svc-workflow\",\"scope\":\"service\"");
    private static final AtomicInteger NEXT = new AtomicInteger();

    private ReservaFixture() { }

    static String person(UUID id, String role) {
        return TestKeys.bearer("\"sub\":\"" + id + "\",\"roles\":[\"" + role + "\"]");
    }

    /** Every Propiedad exists in the simulated Catalog. */
    static void catalogKnowsEveryProperty(PropiedadCatalogPort catalog) {
        when(catalog.find(any(), anyString())).thenAnswer(call ->
                Optional.of(new Propiedad(call.getArgument(0), 10000000L, "COP", 6)));
    }

    /** A PENDIENTE Reserva of that guest, on its own property; returns its id. */
    static String pendiente(MockMvc mvc, UUID guest) throws Exception {
        LocalDate start = LocalDate.parse("2027-01-01").plusDays(NEXT.getAndIncrement() * 3L);
        String body = "{\"usuarioId\":\"" + guest + "\",\"propiedadId\":\"" + UUID.randomUUID() + "\",\"fechaInicio\":\""
                + start + "\",\"fechaFin\":\"" + start.plusDays(2) + "\",\"huespedes\":2}";
        String json = mvc.perform(post("/api/v1/reservas").header("Authorization", SERVICE)
                        .header("Idempotency-Key", UUID.randomUUID() + ":crear-reserva")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.id");
    }

    /** The events of the outbox for that Reserva, oldest first. */
    static java.util.List<String> eventsOf(ReservaRepository reservas, String id) {
        return ((InMemoryReservaRepository) reservas).outbox().stream()
                .filter(e -> e.aggregateId().toString().equals(id)).map(e -> e.eventType()).toList();
    }
}
