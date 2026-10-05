package com.finapp.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.sharedkernel.money.CountryCode;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The rail operations directory (`P9-TSK-014`, ADR-0080 section 2) and the corridor declaration's
 * coherence (ADR-0080 section 1): every declared corridor rail has exactly one coherent declaration,
 * a push adapter speaks only for a push rail that is no corridor, a corridor adapter only for a
 * declared corridor rail under its own id - and a lookup of an operation a rail lacks is empty.
 */
@DisplayName("RailOperations and CorridorDeclaration (P9-TSK-014)")
class RailOperationsTest {

    private static final PaymentRails RAILS =
            PaymentRails.of(List.of(
                    SimulatedCardPspAdapter.RAIL,
                    SimulatedInstantSchemeAdapter.RAIL,
                    BookRail.RAIL,
                    SimulatedCorridorAdapter.RAIL));

    private static final RailId INSTANT = SimulatedInstantSchemeAdapter.RAIL.id();
    private static final RailId CORRIDOR = SimulatedCorridorAdapter.RAIL.id();

    @Test
    @DisplayName("the composed directory looks each rail up by the port it speaks; a corridor has no push"
            + " operation and the instant rail no corridor one")
    void lookupsFollowThePorts() {
        PushRail push = stub(PushRail.class);
        CorridorRail corridor = corridorStub(CORRIDOR);
        RailOperations operations = RailOperations.of(
                RAILS, List.of(SimulatedCorridorAdapter.DECLARATION), Map.of(INSTANT, push), Map.of(CORRIDOR, corridor));
        assertThat(operations.pushRail(INSTANT)).containsSame(push);
        assertThat(operations.pushRail(CORRIDOR)).as("a corridor speaks no push operation").isEmpty();
        assertThat(operations.corridorRail(CORRIDOR)).containsSame(corridor);
        assertThat(operations.corridorRail(INSTANT)).isEmpty();
        assertThat(operations.corridorDeclaration(CORRIDOR)).contains(SimulatedCorridorAdapter.DECLARATION);
        assertThat(operations.anyPushRail()).isTrue();
        assertThat(operations.pushRailIds()).containsExactly(INSTANT);
        // The declarations hold whether or not the deployment configures an adapter.
        RailOperations unconfigured =
                RailOperations.of(RAILS, List.of(SimulatedCorridorAdapter.DECLARATION), Map.of(), Map.of());
        assertThat(unconfigured.corridorDeclarations()).containsExactly(SimulatedCorridorAdapter.DECLARATION);
        assertThat(unconfigured.anyPushRail()).isFalse();
        assertThat(RailOperations.none().pushRail(INSTANT)).isEmpty();
    }

    @Test
    @DisplayName("every incoherent composition is refused: a corridor rail without its declaration, a"
            + " declaration of no rail or of a refunding one, a push adapter on the corridor, a corridor"
            + " adapter on the instant rail or under another id")
    void incoherentCompositionsAreRefused() {
        assertThatThrownBy(() -> RailOperations.of(RAILS, List.of(), Map.of(), Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("corridor-sim-a")
                .hasMessageContaining("no corridor declaration");
        assertThatThrownBy(() -> RailOperations.of(
                        PaymentRails.of(List.of(SimulatedInstantSchemeAdapter.RAIL)),
                        List.of(SimulatedCorridorAdapter.DECLARATION), Map.of(), Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no rail of this build declares");
        assertThatThrownBy(() -> RailOperations.of(
                        RAILS,
                        List.of(SimulatedCorridorAdapter.DECLARATION, declarationOf(INSTANT, "US", "USD")),
                        Map.of(), Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("RefundMode.NONE");
        assertThatThrownBy(() -> RailOperations.of(
                        RAILS, List.of(SimulatedCorridorAdapter.DECLARATION),
                        Map.of(CORRIDOR, stub(PushRail.class)), Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no corridor");
        assertThatThrownBy(() -> RailOperations.of(
                        RAILS, List.of(SimulatedCorridorAdapter.DECLARATION),
                        Map.of(), Map.of(INSTANT, corridorStub(INSTANT))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no declared corridor rail");
        assertThatThrownBy(() -> RailOperations.of(
                        RAILS, List.of(SimulatedCorridorAdapter.DECLARATION),
                        Map.of(), Map.of(CORRIDOR, corridorStub(RailId.of("corridor-sim-b")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("speaks for 'corridor-sim-b'");
    }

    @Test
    @DisplayName("a corridor declaration covers only currencies its rail carries, at least one place,"
            + " with positive windows; its counterparty is its rail id")
    void theDeclarationIsCoherent() {
        assertThat(SimulatedCorridorAdapter.DECLARATION.counterparty()).isEqualTo("corridor-sim-a");
        assertThatThrownBy(() -> declarationOf(CORRIDOR, "DE", "EUR").requireCoherentWith(SimulatedCorridorAdapter.RAIL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not carry");
        assertThatThrownBy(() -> new CorridorDeclaration(
                        CORRIDOR, Set.of(), Duration.ofDays(30), Duration.ofHours(4), Duration.ofDays(1),
                        CorridorDeclaration.ChargeBearer.OUR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one");
        assertThatThrownBy(() -> new CorridorDeclaration(
                        CORRIDOR,
                        Set.of(new CorridorDeclaration.Coverage(CountryCode.of("US"), CurrencyCode.of("USD"))),
                        Duration.ZERO, Duration.ofHours(4), Duration.ofDays(1), CorridorDeclaration.ChargeBearer.OUR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
        SimulatedCorridorAdapter.DECLARATION.requireCoherentWith(SimulatedCorridorAdapter.RAIL);
    }

    // -----------------------------------------------------------------

    private static CorridorDeclaration declarationOf(RailId rail, String country, String currency) {
        return new CorridorDeclaration(
                rail,
                Set.of(new CorridorDeclaration.Coverage(CountryCode.of(country), CurrencyCode.of(currency))),
                Duration.ofDays(30),
                Duration.ofHours(4),
                Duration.ofDays(1),
                CorridorDeclaration.ChargeBearer.OUR);
    }

    /** A port that answers nothing - the directory never calls it. */
    private static <T> T stub(Class<T> port) {
        return port.cast(Proxy.newProxyInstance(
                port.getClassLoader(), new Class<?>[] {port}, (proxy, method, args) -> {
                    throw new AssertionError("the directory never calls " + method.getName());
                }));
    }

    private static CorridorRail corridorStub(RailId id) {
        return (CorridorRail) Proxy.newProxyInstance(
                CorridorRail.class.getClassLoader(), new Class<?>[] {CorridorRail.class}, (proxy, method, args) -> {
                    if (method.getName().equals("id")) {
                        return id;
                    }
                    throw new AssertionError("the directory never calls " + method.getName());
                });
    }
}
