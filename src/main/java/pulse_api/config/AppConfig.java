package pulse_api.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class AppConfig {

    /** One clock for everything time-based so tests can pin "now". */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /** Lenient Jackson 2 mapper for third-party payloads (Bookvas, Netlify, GitHub) — never our own DTOs. */
    @Bean
    public ObjectMapper upstreamJson() {
        return new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /**
     * Outbound HTTP for connectors (contract §7: RestClient, not RestTemplate/WebClient). Built
     * directly: Spring Boot 4 only auto-configures a RestClient.Builder with the restclient module.
     */
    @Bean
    public RestClient restClient() {
        return RestClient.builder().build();
    }

    /** Ask streams run off the request thread so the servlet container is not held. */
    @Bean(destroyMethod = "shutdownNow")
    public ExecutorService askExecutor() {
        return Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("ask-", 0).factory());
    }
}
