package com.finapp.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.platform.api.ApiException;
import com.finapp.sharedkernel.security.Sensitive;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** The conditional step-up's one body (`P15-TSK-039`): exactly the truth table the seventeen copies held. */
@DisplayName("the conditional step-up: MULTI_FACTOR exactly when a factor is active")
class ConditionalStepUpTest {

    private static final IdentityId IDENTITY = IdentityId.of(UUID.fromString("00000000-0000-7000-8000-000000000001"));
    private static final Connection UNIT_OF_WORK =
            (Connection) Proxy.newProxyInstance(ConditionalStepUpTest.class.getClassLoader(),
                    new Class<?>[] {Connection.class},
                    (proxy, method, args) -> method.getName().equals("equals") ? (Object) (proxy == args[0]) : null);

    private final List<Object[]> reads = new ArrayList<>();

    @Test
    @DisplayName("an identity with an active factor on a password session is refused 403 identity.AssuranceRequired,"
            + " the caller's message in the log and none of it on the wire")
    void anEnrolledPasswordSessionIsRefused() {
        assertThatThrownBy(() -> ConditionalStepUp.require(store(true), UNIT_OF_WORK, session(AssuranceLevel.PASSWORD),
                        "A withdrawal from an MFA-enrolled identity requires a MULTI_FACTOR session"))
                .isInstanceOfSatisfying(ApiException.class, refused -> {
                    assertThat(refused.errorCode()).isEqualTo(IdentityErrorCode.ASSURANCE_REQUIRED);
                    assertThat(refused.errorCode().status()).isEqualTo(403);
                    assertThat(refused.getMessage())
                            .isEqualTo("A withdrawal from an MFA-enrolled identity requires a MULTI_FACTOR session");
                    assertThat(refused.clientDetail()).isEmpty();
                });
    }

    @ParameterizedTest
    @EnumSource(value = AssuranceLevel.class, names = {"MULTI_FACTOR", "STRONG"})
    @DisplayName("an identity with an active factor on a session at MULTI_FACTOR or above passes")
    void aSteppedUpSessionPasses(AssuranceLevel level) {
        assertThatCode(() -> ConditionalStepUp.require(store(true), UNIT_OF_WORK, session(level), "refused"))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @EnumSource(AssuranceLevel.class)
    @DisplayName("an identity with no active factor passes at every level: it has nothing to step up with")
    void noFactorPasses(AssuranceLevel level) {
        assertThatCode(() -> ConditionalStepUp.require(store(false), UNIT_OF_WORK, session(level), "refused"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the factor is read on the caller's unit of work, for the session's identity and TOTP, once per decision")
    void theFactorIsAnAuthoritativeReadPerDecision() {
        MfaEnrolmentStore<Connection> store = store(false);
        ConditionalStepUp.require(store, UNIT_OF_WORK, session(AssuranceLevel.PASSWORD), "refused");
        ConditionalStepUp.require(store, UNIT_OF_WORK, session(AssuranceLevel.PASSWORD), "refused");

        assertThat(reads).hasSize(2);
        assertThat(reads.get(0)).containsExactly(UNIT_OF_WORK, IDENTITY, MfaFactorType.TOTP);
    }

    @SuppressWarnings("unchecked")
    private MfaEnrolmentStore<Connection> store(boolean enrolled) {
        return (MfaEnrolmentStore<Connection>) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {MfaEnrolmentStore.class}, (proxy, method, args) -> {
                    assertThat(method.getName()).as("the step-up only ever reads").isEqualTo("findActive");
                    reads.add(args);
                    return enrolled ? Optional.of(new Object()) : Optional.empty();
                });
    }

    private static Session session(AssuranceLevel level) {
        Instant at = Instant.parse("2026-11-09T10:00:00Z");
        return Session.rehydrate(SessionId.of(UUID.fromString("00000000-0000-7000-8000-000000000002")), IDENTITY,
                Sensitive.of("hash"), level, SessionStatus.ACTIVE, at, at.plusSeconds(3600), at.plusSeconds(86400), null,
                null);
    }
}
