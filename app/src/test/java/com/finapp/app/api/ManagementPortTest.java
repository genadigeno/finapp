package com.finapp.app.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.catalina.connector.Connector;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;

/**
 * The actuator answers on the management port and nowhere else (`P15-TSK-008`, ADR-0120 decision 3, {@code THREAT_MODEL.md}
 * T2), and that port binds loopback unless a deployment says otherwise.
 *
 * <p><strong>On production's own configuration.</strong> {@code spring.config.location} names the main
 * {@code application.yaml} alone, so what is tested is production's choice of port, not the test overlay's. The port in
 * the running test is still a free one: Spring Boot's test support replaces a management port that differs from the
 * server's with {@code 0} - and only then. If production's file put the actuator back on the API port, or dropped the
 * management port, there would be nothing to replace, the actuator would answer on the API port, and this class fails.
 *
 * <p>The database and the broker are on closed ports; the background workers production starts run against them, as in
 * {@code AccountingGaugeWiringTest}.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.config.location=classpath:/application.yaml",
            "spring.datasource.url=jdbc:postgresql://127.0.0.1:1/absent",
            // The pool starts without its database and a borrower gives up in a quarter second. Otherwise every
            // borrower - thirty-odd schedules and a scrape's sixty gauges - queues on the pool's first start, one
            // refused connection after another, and a scrape takes minutes.
            "spring.datasource.hikari.initialization-fail-timeout=-1",
            "spring.datasource.hikari.connection-timeout=250",
            "finapp.kafka.bootstrap-servers=127.0.0.1:1"
        })
@Tag("slice")
@DisplayName("the actuator answers on the management port only, bound to loopback (P15-TSK-008)")
class ManagementPortTest {

    private static final List<String> ACTUATOR =
            List.of("/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness", "/actuator/info",
                    "/actuator/prometheus");

    private static final AtomicReference<TomcatWebServer> MANAGEMENT_SERVER = new AtomicReference<>();

    @LocalServerPort private int apiPort;

    @LocalManagementPort private int managementPort;

    @Autowired private ConfigurableEnvironment environment;

    @TestConfiguration
    static class WatchingTheManagementServer {

        @Bean
        ApplicationListener<WebServerInitializedEvent> managementServerWatcher() {
            return event -> {
                if ("management".equals(event.getApplicationContext().getServerNamespace())
                        && event.getWebServer() instanceof TomcatWebServer tomcat) {
                    MANAGEMENT_SERVER.set(tomcat);
                }
            };
        }
    }

    @Test
    @DisplayName("every actuator path answers on the management port, and the API port answers 404 for every one")
    void theActuatorAnswersOnlyOnTheManagementPort() throws Exception {
        assertThat(managementPort).as("a port of its own").isNotEqualTo(apiPort).isPositive();
        for (String path : ACTUATOR) {
            assertThat(get(managementPort, path).statusCode())
                    .as("%s on the management port (503 is readiness with the database down)", path)
                    .isIn(200, 503);
            assertThat(get(apiPort, path).statusCode())
                    .as("%s on the API port: no ingress routing the API can route it", path)
                    .isEqualTo(404);
        }
    }

    @Test
    @DisplayName("the management server listens on loopback by default; the API server on every address")
    void theManagementPortBindsLoopback() throws Exception {
        TomcatWebServer management = MANAGEMENT_SERVER.get();
        assertThat(management).as("the management server started, and was seen").isNotNull();
        Connector connector = management.getTomcat().getConnector();
        Object bound = connector.getProperty("address");
        assertThat(bound).as("the management connector's bound address").isInstanceOf(InetAddress.class);
        assertThat(((InetAddress) bound).isLoopbackAddress()).as("bound to %s", bound).isTrue();
        assertThat(connector.getLocalPort()).isEqualTo(managementPort);

        // And on the wire, where this host has an address that is not loopback: the API port is reached there, the
        // management port is refused there.
        Optional<InetAddress> outside = anAddressThatIsNotLoopback();
        Assumptions.assumeTrue(outside.isPresent(), "this host has no address outside loopback to try");
        assertThat(connects(outside.get(), apiPort)).as("the API port, from %s", outside.get()).isTrue();
        assertThat(connects(outside.get(), managementPort)).as("the management port, from %s", outside.get()).isFalse();
    }

    @Test
    @DisplayName("production's file states the runtime contract: 8081, loopback by default, graceful HTTP, a 30-second phase")
    void productionStatesTheContract() {
        PropertySource<?> production = environment.getPropertySources().stream()
                .filter(source -> source.getName().contains("application.yaml"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("production's application.yaml is not among the property sources"));
        assertThat(String.valueOf(production.getProperty("management.server.port"))).isEqualTo("8081");
        assertThat(String.valueOf(production.getProperty("management.server.address")))
                .isEqualTo("${FINAPP_MANAGEMENT_ADDRESS:127.0.0.1}");
        assertThat(String.valueOf(production.getProperty("server.shutdown"))).isEqualTo("graceful");
        assertThat(String.valueOf(production.getProperty("spring.lifecycle.timeout-per-shutdown-phase"))).isEqualTo("30s");
    }

    // ------------------------------------------------------------------------------------------------------------------

    private static Optional<InetAddress> anAddressThatIsNotLoopback() throws SocketException {
        for (NetworkInterface face : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!face.isUp() || face.isLoopback()) {
                continue;
            }
            for (InetAddress address : Collections.list(face.getInetAddresses())) {
                if (address instanceof Inet4Address && !address.isLoopbackAddress() && !address.isLinkLocalAddress()) {
                    return Optional.of(address);
                }
            }
        }
        return Optional.empty();
    }

    private static boolean connects(InetAddress address, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(address, port), 2_000);
            return true;
        } catch (IOException refused) {
            return false;
        }
    }

    private static HttpResponse<String> get(int port, String path) throws Exception {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build()
                .send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
    }
}
