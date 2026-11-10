package co.edu.corhuila.property.booking.app;

import co.edu.corhuila.property.booking.adapter.in.http.AuthFilter;
import co.edu.corhuila.property.booking.adapter.in.http.CorrelationFilter;
import co.edu.corhuila.property.booking.adapter.in.http.Rs256Verifier;
import co.edu.corhuila.property.booking.adapter.out.persistence.InMemoryReservaRepository;
import co.edu.corhuila.property.booking.adapter.out.persistence.JdbcReservaRepository;
import co.edu.corhuila.property.booking.application.port.out.ReservaRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Composition root: the only place that knows every concrete type. The filters
 * are registered here explicitly, in order: correlation first, so that even a
 * 401 carries its traceId.
 */
@Configuration
public class BookingConfiguration {

    /**
     * PERSISTENCE=postgres (the default) needs DATABASE_URL, DATABASE_USER (booking_app
     * in the platform) and DATABASE_PASSWORD: without them the service does not start.
     * PERSISTENCE=memory is for tests and local development only (norm 5.5.2).
     */
    @Bean
    Storage storage(@Value("${booking.persistence:postgres}") String persistence,
                    @Value("${booking.database.url:}") String url,
                    @Value("${booking.database.user:}") String user,
                    @Value("${booking.database.password:}") String password,
                    @Value("${booking.database.pool-max:10}") int poolMax,
                    @Value("${booking.database.statement-timeout-ms:5000}") int statementTimeoutMs) {
        return Storage.connect(persistence, url, user, password, poolMax, statementTimeoutMs);
    }

    @Bean
    ReservaRepository reservaRepository(Storage storage) {
        return storage.inMemory() ? new InMemoryReservaRepository()
                : new JdbcReservaRepository(storage.jdbc(), storage.tx());
    }

    /**
     * JWT_PUBLIC_KEY (the PEM itself; a one-line value with literal \n escapes, as
     * a .env file holds it, is accepted) or JWT_PUBLIC_KEY_FILE. Without a key
     * the service does not start.
     */
    @Bean
    Rs256Verifier tokenVerifier(@Value("${JWT_PUBLIC_KEY:}") String pem,
                                @Value("${JWT_PUBLIC_KEY_FILE:}") String file) throws IOException {
        String key = !pem.isBlank() ? pem.replace("\\n", "\n")
                : !file.isBlank() ? Files.readString(Path.of(file)) : "";
        return new Rs256Verifier(key);
    }

    @Bean
    FilterRegistrationBean<CorrelationFilter> correlationFilter() {
        FilterRegistrationBean<CorrelationFilter> bean = new FilterRegistrationBean<>(new CorrelationFilter());
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return bean;
    }

    @Bean
    FilterRegistrationBean<AuthFilter> authFilter(Rs256Verifier verifier, ObjectMapper json) {
        FilterRegistrationBean<AuthFilter> bean = new FilterRegistrationBean<>(new AuthFilter(verifier, json));
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return bean;
    }
}
