package com.finapp.app.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * The provider hop's TLS, enforced at last (the Phase 7 -&gt; 8 transition):
 * {@code SECURITY_ARCHITECTURE.md} named it "Phase 5, with the first adapter" and nothing held it.
 */
@DisplayName("the provider transport guard (the Phase 7 -> 8 transition)")
class ProviderTransportGuardTest {

    @Test
    @DisplayName("every provider URL off this machine over plain http is refused, naming its own"
            + " setting")
    void plainHttpOffTheMachineIsRefusedForEveryProvider() {
        for (String property : ProviderTransportGuard.PROVIDER_URLS) {
            assertThatThrownBy(
                            () ->
                                    ProviderTransportGuard.verify(
                                            property, Optional.of("http://psp.internal:8080")))
                    .as(property)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(property)
                    .hasMessageContaining("psp.internal")
                    .hasMessageContaining("https");
        }
    }

    @Test
    @DisplayName("https anywhere, plain http to loopback, and an unconfigured provider all start")
    void verifiedOrLocalOrAbsentStarts() {
        for (String allowed :
                java.util.List.of(
                        "https://psp.example.com",
                        "https://10.0.0.7:8443/v1",
                        "http://localhost:8089",
                        "http://127.0.0.1:9",
                        "http://[::1]:8080")) {
            assertThatCode(
                            () ->
                                    ProviderTransportGuard.verify(
                                            "finapp.payments.instant.url", Optional.of(allowed)))
                    .as(allowed)
                    .doesNotThrowAnyException();
        }
        assertThatCode(() -> ProviderTransportGuard.verify("finapp.kyc.provider.url",
                        Optional.empty()))
                .doesNotThrowAnyException();
        // "false" switches a provider off for @ConditionalOnProperty, so it is no hop either.
        assertThatCode(() -> ProviderTransportGuard.verify("finapp.payments.provider.url",
                        Optional.of("false")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a settlement pull source off this machine is refused over any cleartext channel,"
            + " naming its own setting; https or sftp anywhere, plain transport to loopback, or"
            + " no source at all starts - and sftp stays refused for every other provider"
            + " (P8-TSK-021, ADR-0066 section 1)")
    void settlementSourcesAreHttpsOrSftpOffTheMachine() {
        for (String property : ProviderTransportGuard.SOURCE_URLS) {
            for (String cleartext :
                    java.util.List.of("http://reports.psp.internal", "ftp://reports.psp.internal")) {
                assertThatThrownBy(
                                () ->
                                        ProviderTransportGuard.verifySource(
                                                property, Optional.of(cleartext)))
                        .as(property + " " + cleartext)
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining(property)
                        .hasMessageContaining("reports.psp.internal");
            }
            for (String allowed :
                    java.util.List.of(
                            "https://reports.psp.example.com",
                            "sftp://reports.bank.example.com:22/outbox",
                            "http://localhost:8089",
                            "http://127.0.0.1:9")) {
                assertThatCode(() -> ProviderTransportGuard.verifySource(
                                property, Optional.of(allowed)))
                        .as(property + " " + allowed)
                        .doesNotThrowAnyException();
            }
            assertThatCode(() -> ProviderTransportGuard.verifySource(property, Optional.empty()))
                    .doesNotThrowAnyException();
        }
        assertThatThrownBy(
                        () ->
                                ProviderTransportGuard.verify(
                                        "finapp.payments.provider.url",
                                        Optional.of("sftp://psp.example.com")))
                .as("sftp is admitted for a pull source alone")
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("startup consults every settlement pull source URL: a cleartext source off"
            + " this machine refuses the guard's construction, naming its own setting"
            + " (P8-TSK-021)")
    void startupConsultsEverySourceUrl() {
        for (String property : ProviderTransportGuard.SOURCE_URLS) {
            MockEnvironment environment =
                    new MockEnvironment().withProperty(property, "http://reports.bank.internal");
            assertThatThrownBy(() -> new ProviderTransportGuard(environment))
                    .as(property)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(property);
        }
        assertThatCode(
                        () ->
                                new ProviderTransportGuard(
                                        new MockEnvironment()
                                                .withProperty(
                                                        "finapp.settlement.bank.statement.url",
                                                        "sftp://reports.bank.example.com")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("fails closed: no scheme, no host, or an unreadable URL is refused")
    void failsClosed() {
        for (String unreadable :
                java.util.List.of("psp.internal:8080", "http://", "ftp://psp.internal",
                        "http://exa mple.com")) {
            assertThatThrownBy(
                            () ->
                                    ProviderTransportGuard.verify(
                                            "finapp.payments.provider.url",
                                            Optional.of(unreadable)))
                    .as(unreadable)
                    .isInstanceOf(IllegalStateException.class);
        }
    }
}
