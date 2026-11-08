package com.finapp.app.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.app.FinappApplication;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.actuate.autoconfigure.web.server.ManagementPortType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * An actuator that would answer on every interface refuses startup (`P15-TSK-009`, {@code THREAT_MODEL.md} T2): no address,
 * an any-local address, or no management port of its own.
 *
 * <p>The last three cases boot the application on production's own {@code application.yaml}, with the database and the broker
 * on closed ports. They are what shows the value reaches the guard as Tomcat would receive it: the argument
 * {@code --FINAPP_MANAGEMENT_ADDRESS=} resolves the same placeholder an empty environment variable does, so a guard that
 * looked at the wrong property or was never wired would let this context start, and the case fails.
 */
@Tag("slice")
@DisplayName("an actuator that would answer on every interface refuses startup (P15-TSK-009)")
class ManagementAddressGuardTest {

    @Test
    @DisplayName("no address - the empty variable's reading - is refused, and the refusal names the variable")
    void noAddressIsRefused() {
        assertThatThrownBy(() -> ManagementAddressGuard.verify(ManagementPortType.DIFFERENT, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FINAPP_MANAGEMENT_ADDRESS")
                .hasMessageContaining("every interface");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.0.0.0", "0", "::", "0:0:0:0:0:0:0:0", "::ffff:0.0.0.0", "[::]"})
    @DisplayName("every spelling of every interface is refused, judged on the address it parses to")
    void anyLocalIsRefused(String spelling) throws Exception {
        InetAddress parsed = InetAddress.getByName(spelling);
        assertThat(parsed.isAnyLocalAddress()).as("%s reads as every interface", spelling).isTrue();
        assertThatThrownBy(() -> ManagementAddressGuard.verify(ManagementPortType.DIFFERENT, parsed))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("every interface");
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "::1", "10.1.2.3", "fd00::1"})
    @DisplayName("loopback and a pod's own address, IPv4 or IPv6, are accepted")
    void aNamedAddressIsAccepted(String address) throws Exception {
        InetAddress parsed = InetAddress.getByName(address);
        assertThatCode(() -> ManagementAddressGuard.verify(ManagementPortType.DIFFERENT, parsed)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the actuator on the API port is refused, whatever the address; a disabled management server binds nothing and passes")
    void aSharedPortIsRefusedAndADisabledOnePasses() throws Exception {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        assertThatThrownBy(() -> ManagementAddressGuard.verify(ManagementPortType.SAME, loopback))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("API port");
        assertThatCode(() -> ManagementAddressGuard.verify(ManagementPortType.DISABLED, null)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("on production's own file an empty FINAPP_MANAGEMENT_ADDRESS refuses startup")
    void anEmptyVariableRefusesStartup() {
        assertThat(refusalOfAStartWith("--FINAPP_MANAGEMENT_ADDRESS=")).contains("resolves to no address");
    }

    @Test
    @DisplayName("on production's own file a blank FINAPP_MANAGEMENT_ADDRESS refuses startup, and so does 0.0.0.0")
    void aBlankVariableAndEveryInterfaceRefuseStartup() {
        assertThat(refusalOfAStartWith("--FINAPP_MANAGEMENT_ADDRESS=   ")).contains("resolves to no address");
        assertThat(refusalOfAStartWith("--FINAPP_MANAGEMENT_ADDRESS=0.0.0.0")).contains("is 0.0.0.0");
    }

    @Test
    @DisplayName("on production's own file an empty management port refuses startup")
    void anEmptyManagementPortRefusesStartup() {
        assertThat(refusalOfAStartWith("--management.server.port=")).contains("API port");
    }

    /** The chain's messages of a refused start. A start that succeeds fails the test, and is closed. */
    private static String refusalOfAStartWith(String argument) {
        List<String> arguments = new ArrayList<>(List.of(
                "--spring.config.location=classpath:/application.yaml",
                "--server.port=0",
                "--spring.datasource.url=jdbc:postgresql://127.0.0.1:1/absent",
                "--spring.datasource.hikari.initialization-fail-timeout=-1",
                "--spring.datasource.hikari.connection-timeout=250",
                "--finapp.kafka.bootstrap-servers=127.0.0.1:1"));
        if (!argument.startsWith("--management.server.port")) {
            arguments.add("--management.server.port=0");
        }
        arguments.add(argument);
        ConfigurableApplicationContext started;
        try {
            started = new SpringApplicationBuilder(FinappApplication.class).run(arguments.toArray(String[]::new));
        } catch (RuntimeException refused) {
            StringBuilder chain = new StringBuilder();
            for (Throwable cause = refused; cause != null; cause = cause.getCause()) {
                chain.append(cause.getMessage()).append('\n');
            }
            return chain.toString();
        }
        started.close();
        throw new AssertionError("the application started with " + argument);
    }
}
