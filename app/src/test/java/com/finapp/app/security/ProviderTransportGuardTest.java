package com.finapp.app.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
