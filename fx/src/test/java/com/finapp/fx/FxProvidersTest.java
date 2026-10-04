package com.finapp.fx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.sharedkernel.money.CurrencyCode;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The FX provider directory and declaration rules (`P9-TSK-006`). */
@DisplayName("the FX provider directory: one adapter per declared code (P9-TSK-006)")
class FxProvidersTest {

    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final CurrencyCode USD = CurrencyCode.of("USD");

    @Test
    @DisplayName("a composed provider is found by its code, with its declaration")
    void aProviderIsFoundByCode() {
        FxProviders providers =
                new FxProviders(List.of(new FxProviders.Composed(declaration("fx-a"), adapter("fx-a"))));
        assertThat(providers.find("fx-a")).isPresent();
        assertThat(providers.find("fx-a").orElseThrow().declaration().quotes(EUR, USD)).isTrue();
        assertThat(providers.find("fx-a").orElseThrow().declaration().quotes(USD, EUR)).isFalse();
        assertThat(providers.find("fx-b")).isEmpty();
        assertThat(providers.declarations()).hasSize(1);
    }

    @Test
    @DisplayName("two providers answering for one code, and an adapter under another's declaration,"
            + " are refused")
    void ambiguityIsRefused() {
        assertThatThrownBy(
                        () ->
                                new FxProviders(
                                        List.of(
                                                new FxProviders.Composed(declaration("fx-a"), adapter("fx-a")),
                                                new FxProviders.Composed(declaration("fx-a"), adapter("fx-a")))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FxProviders.Composed(declaration("fx-a"), adapter("fx-b")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a declaration quotes only what it settles, and its bounds are positive")
    void aDeclarationIsCoherent() {
        assertThatThrownBy(
                        () ->
                                new FxProviderDeclaration(
                                        "fx-a", 1,
                                        Set.of(new FxProviderDeclaration.QuotedPair(EUR, CurrencyCode.of("JPY"))),
                                        Set.of(EUR, USD), Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new FxProviderDeclaration(
                                        "fx-a", 1, Set.of(new FxProviderDeclaration.QuotedPair(EUR, USD)),
                                        Set.of(EUR, USD), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new FxProviderDeclaration(
                                        "FX A", 1, Set.of(new FxProviderDeclaration.QuotedPair(EUR, USD)),
                                        Set.of(EUR, USD), Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static FxProviderDeclaration declaration(String code) {
        return new FxProviderDeclaration(
                code, 1, Set.of(new FxProviderDeclaration.QuotedPair(EUR, USD)), Set.of(EUR, USD),
                Duration.ofMinutes(2));
    }

    private static FxProvider adapter(String code) {
        return new FxProvider() {
            @Override
            public String code() {
                return code;
            }

            @Override
            public FirmQuoteAnswer firmQuote(FirmQuoteRequest request) {
                return new FirmQuoteAnswer.NothingSent();
            }

            @Override
            public ExecutionAnswer execute(ExecutionRequest request) {
                return new ExecutionAnswer.NothingSent();
            }

            @Override
            public ExecutionAnswer inquire(String clientReference) {
                return new ExecutionAnswer.NothingSent();
            }
        };
    }
}
