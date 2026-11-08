package com.finapp.app.security;

import java.net.InetAddress;
import org.springframework.boot.actuate.autoconfigure.web.server.ManagementPortType;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Refuses to start when the actuator would answer on every interface (`P15-TSK-009`, from `P15-TSK-008`'s review;
 * {@code THREAT_MODEL.md} T2).
 *
 * <p>The management port carries the unauthenticated health and scrape endpoints, so it binds loopback unless a deployment
 * names the pod's own address. {@code ${FINAPP_MANAGEMENT_ADDRESS:127.0.0.1}} applies its default only when the variable is
 * <em>unset</em>: an <strong>empty</strong> variable resolves to no address, Tomcat is given none, and the connector binds
 * every interface, with nothing saying so. That is one mistyped manifest away, so it is refused rather than warned about.
 *
 * <p>Three things are refused, each of which ends with the actuator reachable on every interface:
 *
 * <ul>
 *   <li>no address at all: an empty or blank variable;
 *   <li>an address that means every interface: {@code 0.0.0.0}, {@code ::}, and every spelling of them, judged on the
 *       address it parses to and never on its text;
 *   <li>no management port of its own: {@code management.server.port} unset, or the API's own, which serves the actuator on
 *       the API port.
 * </ul>
 *
 * <p>A disabled management server ({@code management.server.port: -1}) binds nothing and passes.
 *
 * <p>The address is bound here exactly as {@code ManagementServerProperties} binds it, so what is judged is what Tomcat is
 * handed. A bean: its constructor runs before any lifecycle starts, so a refused instance never opens a port. It reads
 * configuration only, per instance, with nothing shared.
 */
@Component
public class ManagementAddressGuard {

    /** The property the management server binds. Named in the refusal so the fix needs no documentation. */
    static final String ADDRESS = "management.server.address";

    ManagementAddressGuard(Environment environment) {
        verify(
                ManagementPortType.get(environment),
                Binder.get(environment).bind(ADDRESS, InetAddress.class).orElse(null));
    }

    /**
     * @param port how the management port relates to the API's
     * @param address what the management server would bind, {@code null} for no address
     * @throws IllegalStateException if the actuator would answer on every interface
     */
    static void verify(ManagementPortType port, InetAddress address) {
        if (port == ManagementPortType.DISABLED) {
            return;
        }
        if (port == ManagementPortType.SAME) {
            throw new IllegalStateException(
                    "Refusing to start: management.server.port is unset or is the API's own, so the actuator's"
                            + " unauthenticated endpoints would answer on the API port. Give it a port of its own.");
        }
        if (address == null) {
            throw new IllegalStateException(
                    "Refusing to start: " + ADDRESS + " resolves to no address, which binds the management port to every"
                            + " interface. FINAPP_MANAGEMENT_ADDRESS is set but empty: unset it for loopback, or name the"
                            + " pod's own address.");
        }
        if (address.isAnyLocalAddress()) {
            throw new IllegalStateException(
                    "Refusing to start: " + ADDRESS + " is " + address.getHostAddress() + ", which binds the management port"
                            + " to every interface. Name the pod's own address, or unset FINAPP_MANAGEMENT_ADDRESS for loopback.");
        }
    }
}
