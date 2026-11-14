package co.edu.corhuila.property.booking.adapter.out.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.corhuila.property.booking.application.port.out.CatalogNoDisponibleException;
import co.edu.corhuila.property.booking.application.port.out.PropiedadCatalogPort.Propiedad;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The HTTP adapter against a fake Catalog: a real server on a free port, scripted answers. */
class HttpPropiedadCatalogTest {

    static final UUID ID = UUID.fromString("a1d5e2f0-3333-4c3e-9a2d-000000000003");
    static final String PROPIEDAD = "{\"id\":\"" + ID + "\",\"titulo\":\"Apto\",\"precioNocheCents\":50000000,"
            + "\"moneda\":\"COP\",\"capacidad\":6,\"amenities\":[\"wifi\"]}";

    record Answer(int status, String body, long delayMs) { }

    HttpServer server;
    final Deque<Answer> answers = new ArrayDeque<>();
    final AtomicInteger calls = new AtomicInteger();
    final AtomicReference<String> path = new AtomicReference<>();
    final AtomicReference<String> correlation = new AtomicReference<>();
    HttpPropiedadCatalog catalog;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            path.set(exchange.getRequestURI().getPath());
            correlation.set(exchange.getRequestHeaders().getFirst("X-Correlation-Id"));
            Answer a = answers.size() > 1 ? answers.poll() : answers.peek();
            try {
                Thread.sleep(a.delayMs());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] body = a.body().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(a.status(), body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
        catalog = new HttpPropiedadCatalog("http://127.0.0.1:" + server.getAddress().getPort(),
                Duration.ofMillis(300), 3, Duration.ofMillis(10));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    void answer(int status, String body) {
        answers.add(new Answer(status, body, 0));
    }

    @Test
    void readsPriceCurrencyAndCapacityAndSendsTheCorrelationId() {
        answer(200, PROPIEDAD);

        Propiedad p = catalog.find(ID, "saga-trace-1").orElseThrow();
        assertEquals(50000000L, p.precioNocheCents());
        assertEquals("COP", p.moneda());
        assertEquals(6, p.capacidad());
        assertEquals("/api/v1/propiedades/" + ID, path.get());
        assertEquals("saga-trace-1", correlation.get());
    }

    @Test
    void anUnknownPropertyIsEmptyAndNotRetried() {
        answer(404, "{\"error\":\"NOT_FOUND\"}");

        assertTrue(catalog.find(ID, "t").isEmpty());
        assertEquals(1, calls.get());
    }

    @Test
    void a5xxOrA429IsRetriedAndASecondAnswerWins() {
        answer(503, "");
        answer(429, "");
        answer(200, PROPIEDAD);

        assertEquals(6, catalog.find(ID, "t").orElseThrow().capacidad());
        assertEquals(3, calls.get());
    }

    @Test
    void afterTheBoundedAttemptsItFailsAsUnavailable() {
        answer(500, "");

        assertThrows(CatalogNoDisponibleException.class, () -> catalog.find(ID, "t"));
        assertEquals(3, calls.get());
    }

    @Test
    void aSlowCatalogHitsTheTimeLimit() {
        answers.add(new Answer(200, PROPIEDAD, 1000));

        long start = System.nanoTime();
        assertThrows(CatalogNoDisponibleException.class, () -> catalog.find(ID, "t"));
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 2500, "three attempts of 300 ms");
    }

    @Test
    void anotherClientErrorIsNotRetried() {
        answer(400, "{\"error\":\"VALIDATION_ERROR\"}");

        assertThrows(CatalogNoDisponibleException.class, () -> catalog.find(ID, "t"));
        assertEquals(1, calls.get());
    }

    @Test
    void aPropertyOutsideTheContractIsNotTrusted() {
        answer(200, "{\"precioNocheCents\":500.5,\"moneda\":\"COP\",\"capacidad\":6}");
        assertThrows(CatalogNoDisponibleException.class, () -> catalog.find(ID, "t"));

        answers.clear();
        answer(200, "not json");
        assertThrows(CatalogNoDisponibleException.class, () -> catalog.find(ID, "t"));
    }

    @Test
    void anUnreachableCatalogIsUnavailable() {
        server.stop(0);

        assertThrows(CatalogNoDisponibleException.class, () -> catalog.find(ID, "t"));
    }
}
