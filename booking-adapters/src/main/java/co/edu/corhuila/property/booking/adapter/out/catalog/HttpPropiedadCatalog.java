package co.edu.corhuila.property.booking.adapter.out.catalog;

import co.edu.corhuila.property.booking.application.port.out.CatalogNoDisponibleException;
import co.edu.corhuila.property.booking.application.port.out.PropiedadCatalogPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * GET /api/v1/propiedades/{id} of Catalog (ADR-015): public, so no credential, but with
 * the X-Correlation-Id of the request (rule 5). Bounded: a time limit per attempt and
 * retries with exponential backoff and jitter, only for network errors, 429 and 5xx
 * (rule 3). It is called before the transaction opens (rule 2), by the use case.
 */
public class HttpPropiedadCatalog implements PropiedadCatalogPort {

    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();
    private final URI baseUrl;
    private final Duration timeout;
    private final int maxAttempts;
    private final Duration backoff;

    /** baseUrl: CATALOG_URL, e.g. http://catalog-api:3002 inside the network platform. */
    public HttpPropiedadCatalog(String baseUrl, Duration timeout, int maxAttempts, Duration backoff) {
        this.baseUrl = URI.create(baseUrl.endsWith("/") ? baseUrl : baseUrl + "/");
        this.timeout = timeout;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.backoff = backoff;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override
    public Optional<Propiedad> find(UUID propiedadId, String correlationId) {
        HttpRequest request = HttpRequest.newBuilder(baseUrl.resolve("api/v1/propiedades/" + propiedadId))
                .timeout(timeout).header("Accept", "application/json").header("X-Correlation-Id", correlationId)
                .GET().build();
        Exception last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (attempt > 1) {
                pause(attempt);
            }
            HttpResponse<String> response;
            try {
                response = http.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (IOException e) {            // timeout, refused connection, reset
                last = e;
                continue;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CatalogNoDisponibleException("interrupted while calling catalog", e);
            }
            int status = response.statusCode();
            if (status == 200) {
                return Optional.of(read(propiedadId, response.body()));
            }
            if (status == 404) {
                return Optional.empty();
            }
            last = new IOException("catalog answered " + status);
            if (status != 429 && status < 500) {
                break;                           // any other 4xx is not retried
            }
        }
        throw new CatalogNoDisponibleException("catalog did not answer: " + last.getMessage(), last);
    }

    /** precioNocheCents, moneda and capacidad cannot change shape without breaking Booking (E-06). */
    private Propiedad read(UUID id, String body) {
        try {
            JsonNode node = json.readTree(body);
            JsonNode price = node.path("precioNocheCents");
            JsonNode capacity = node.path("capacidad");
            if (price.isIntegralNumber() && price.canConvertToLong() && price.asLong() >= 1
                    && node.path("moneda").isTextual() && capacity.isInt() && capacity.asInt() >= 1) {
                return new Propiedad(id, price.asLong(), node.get("moneda").asText(), capacity.asInt());
            }
        } catch (IOException e) {
            throw new CatalogNoDisponibleException("catalog answered a body that is not JSON", e);
        }
        throw new CatalogNoDisponibleException("catalog answered a property outside its contract", null);
    }

    /** Exponential backoff with jitter: backoff, 2 x backoff... plus up to one backoff at random. */
    private void pause(int attempt) {
        long base = backoff.toMillis() << (attempt - 2);
        try {
            Thread.sleep(base + ThreadLocalRandom.current().nextLong(backoff.toMillis() + 1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
