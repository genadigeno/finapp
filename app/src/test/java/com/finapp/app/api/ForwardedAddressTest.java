package com.finapp.app.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.testing.RepositoryPaths;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Only the edge's word on the client's address is believed (`P15-TSK-011`, {@code THREAT_MODEL.md} T40).
 *
 * <p><strong>The default that would have been wrong.</strong> With {@code server.forward-headers-strategy} unset, Spring Boot
 * asks whether it is on a cloud platform, and Kubernetes counts: the remote-address valve switches on by itself, trusting
 * {@code X-Forwarded-For} from every private range. So the committed application, deployed, would have believed the header
 * from any pod, and no test on a laptop would ever have seen it. These cases say {@code cloud-platform=kubernetes} for that
 * reason, and run on a real Tomcat from production's own file (the three-case shape of {@code ManagementPortTest}): what is
 * asserted is the address a request sees, not a property's value.
 *
 * <p>The address is what {@code BnplOfferController}, {@code LoanOfferController} and {@code LoanController} record with an
 * acceptance, and what the per-source limits of {@code P15-TSK-022} will count: a value a client could choose would be a value
 * in the record that nobody can trust.
 */
@Tag("slice")
@DisplayName("the client's address is believed from the edge alone (P15-TSK-011)")
class ForwardedAddressTest {

    private static final String ATTACKER = "203.0.113.9";

    @Nested
    @DisplayName("deployed with no edge named")
    @SpringBootTest(classes = Echo.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = {"spring.config.location=classpath:/application.yaml", "spring.main.cloud-platform=kubernetes"})
    class NoEdgeNamed {
        @LocalServerPort private int port;

        @Test
        @DisplayName("a client's X-Forwarded-For is ignored, on Kubernetes too: the blank default trusts nobody")
        void aClientsHeaderIsIgnored() throws Exception {
            assertThat(addressSeenThrough(port, ATTACKER)).isEqualTo("127.0.0.1");
        }
    }

    @Nested
    @DisplayName("the connection comes from the edge")
    @SpringBootTest(classes = Echo.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = {"spring.config.location=classpath:/application.yaml", "FINAPP_EDGE_PROXIES=127.0.0.0/8"})
    class FromTheEdge {
        @LocalServerPort private int port;

        @Test
        @DisplayName("the address the edge set is the request's address")
        void theEdgesAddressIsBelieved() throws Exception {
            assertThat(addressSeenThrough(port, ATTACKER)).isEqualTo(ATTACKER);
        }

        @Test
        @DisplayName("a list is read from the right: the hop the edge appended wins over whatever a client put before it")
        void theRightmostHopWins() throws Exception {
            assertThat(addressSeenThrough(port, "198.51.100.7, " + ATTACKER)).isEqualTo(ATTACKER);
        }
    }

    @Nested
    @DisplayName("the connection does not come from the edge")
    @SpringBootTest(classes = Echo.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = {"spring.config.location=classpath:/application.yaml", "FINAPP_EDGE_PROXIES=10.0.0.0/8"})
    class FromElsewhere {
        @LocalServerPort private int port;

        @Test
        @DisplayName("a peer outside the named network gets no say: the connection's own address stands")
        void aPeerThatIsNotTheEdgeIsNotBelieved() throws Exception {
            assertThat(addressSeenThrough(port, ATTACKER)).isEqualTo("127.0.0.1");
        }
    }

    @Test
    @DisplayName("production's file states the strategy and a blank-by-default proxy list; no overlay or code can reach it another way")
    @SuppressWarnings("unchecked")
    void productionStatesTheContract() throws IOException {
        Map<String, Object> application = new Yaml(new SafeConstructor(new LoaderOptions()))
                .load(Files.readString(RepositoryPaths.locate("app/src/main/resources/application.yaml")));
        Map<String, Object> server = (Map<String, Object>) application.get("server");
        assertThat(server).containsEntry("forward-headers-strategy", "native");
        Map<String, Object> tomcat = (Map<String, Object>) server.get("tomcat");
        Map<String, Object> remoteip = (Map<String, Object>) tomcat.get("remoteip");
        assertThat(remoteip).containsOnlyKeys("internal-proxies");
        assertThat(remoteip.get("internal-proxies")).isEqualTo("${FINAPP_EDGE_PROXIES:}");
    }

    @Test
    @DisplayName("no production class reads a forwarded header or installs a filter that would: the valve is the one way in")
    void nothingElseReadsAForwardedHeader() throws IOException {
        List<String> offenders;
        try (Stream<Path> sources = Files.walk(RepositoryPaths.locate("settings.gradle.kts").getParent())) {
            offenders = sources
                    .filter(path -> path.toString().replace('\\', '/').contains("/src/main/java/"))
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(ForwardedAddressTest::readsAForwardedHeader)
                    .map(Path::toString)
                    .toList();
        }
        assertThat(offenders).isEmpty();
    }

    private static boolean readsAForwardedHeader(Path source) {
        try {
            String text = Files.readString(source, StandardCharsets.UTF_8);
            return text.contains("X-Forwarded") || text.contains("X-Real-Ip") || text.contains("X-Real-IP")
                    || text.contains("\"Forwarded\"") || text.contains("ForwardedHeaderFilter");
        } catch (IOException unreadable) {
            throw new java.io.UncheckedIOException(unreadable);
        }
    }

    private static String addressSeenThrough(int port, String forwardedFor) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/echo"))
                .timeout(Duration.ofSeconds(10))
                .header("X-Forwarded-For", forwardedFor)
                .GET()
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return response.body();
    }

    /** A real Tomcat on production's file, answering the address a request has by the time anything reads it. */
    // @Configuration and never @SpringBootConfiguration: a nested one in this package would be found by every other
    // @SpringBootTest in it, and they would boot this instead of the application.
    @Configuration
    @ImportAutoConfiguration(TomcatServletWebServerAutoConfiguration.class)
    static class Echo {
        @Bean
        ServletRegistrationBean<HttpServlet> echo() {
            return new ServletRegistrationBean<>(new HttpServlet() {
                @Override
                protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
                    response.getWriter().write(request.getRemoteAddr());
                }
            }, "/echo");
        }
    }
}
