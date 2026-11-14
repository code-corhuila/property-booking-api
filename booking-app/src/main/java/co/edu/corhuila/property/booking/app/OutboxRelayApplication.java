package co.edu.corhuila.property.booking.app;

import co.edu.corhuila.property.booking.adapter.in.relay.OutboxRelayLoop;
import co.edu.corhuila.property.booking.adapter.out.broker.RabbitEventPublisher;
import co.edu.corhuila.property.booking.adapter.out.persistence.JdbcOutboxStore;
import co.edu.corhuila.property.booking.application.usecase.RelayOutbox;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * The composition root of the outbox relay: the same jar, started with --outbox-relay, as a
 * process apart from the API (ADR-005; norm 5.3.11). No web server, no controllers, no token.
 * It is deliberately not a @Configuration, so the component scan of the API never loads it.
 */
@EnableAutoConfiguration(exclude = DataSourceAutoConfiguration.class)
public class OutboxRelayApplication {

    /** The relay reads and marks the outbox, so the database is required, as for the API. */
    @Bean
    Storage storage(@Value("${booking.database.url:}") String url,
                    @Value("${booking.database.user:}") String user,
                    @Value("${booking.database.password:}") String password,
                    @Value("${booking.relay.pool-max:2}") int poolMax,
                    @Value("${booking.database.statement-timeout-ms:5000}") int statementTimeoutMs) {
        return Storage.connect("postgres", url, user, password, poolMax, statementTimeoutMs);
    }

    @Bean(destroyMethod = "close")
    RabbitEventPublisher publisher(@Value("${booking.broker.host}") String host,
                                   @Value("${booking.broker.port}") int port,
                                   @Value("${booking.broker.user}") String user,
                                   @Value("${booking.broker.password:}") String password,
                                   @Value("${booking.broker.confirm-timeout-ms}") long confirmTimeoutMs) {
        return new RabbitEventPublisher(host, port, user, password, "reservas", Duration.ofMillis(confirmTimeoutMs));
    }

    @Bean
    OutboxRelayLoop relayLoop(Storage storage, RabbitEventPublisher publisher,
                              @Value("${booking.relay.batch-size}") int batchSize,
                              @Value("${booking.relay.poll-ms}") long pollMs) {
        return new OutboxRelayLoop(new RelayOutbox(new JdbcOutboxStore(storage.jdbc()), publisher, batchSize),
                Duration.ofMillis(pollMs));
    }

    @Bean
    ApplicationRunner runRelay(OutboxRelayLoop loop) {
        Runtime.getRuntime().addShutdownHook(new Thread(loop::stop));
        return args -> loop.run();
    }
}
