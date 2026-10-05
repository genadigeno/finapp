package com.finapp.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.ledger.AccountPurpose;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A cover leg's key is its currency's (`P9-TSK-012`, carried from `P9-TSK-011`): the same rule at
 * both sides, applied by reconciliation's own constructors - an expectation of a cover leg from its
 * amount, an FX provider item from its own - so a cover's two legs hold two keys under one source's
 * unique, and an item reaches only the leg in its own currency. Nothing else is touched.
 */
@DisplayName("a cover leg's key is qualified by its currency, at both sides (P9-TSK-012)")
class CoverLegKeyTest {

    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final CurrencyCode USD = CurrencyCode.of("USD");
    private static final String T = "T-0123456789abcdef0123456789abcdef";

    @Test
    @DisplayName("qualify, and back: idempotent; the reference is what the provider and the platform call it")
    void qualifyAndBack() {
        assertThat(CoverLegKey.qualify(T, EUR)).isEqualTo(T + ":EUR");
        assertThat(CoverLegKey.qualify(T + ":EUR", EUR)).isEqualTo(T + ":EUR");
        assertThat(CoverLegKey.reference(T + ":USD")).isEqualTo(T);
        assertThat(CoverLegKey.reference(T)).isEqualTo(T);
        assertThat(CoverLegKey.currencyOf(T + ":USD")).contains("USD");
        assertThat(CoverLegKey.currencyOf("FT-1")).isEmpty();
    }

    @Test
    @DisplayName("a cover's two legs: the same T becomes two keys - the sell leg's EUR, the buy leg's USD")
    void twoLegsTwoKeys() {
        assertThat(leg(ExpectationKind.FX_SELL_LEG, Money.ofPersisted(100_000, EUR, 2)).keys())
                .containsExactly(new NewExpectation.ExpectationKey(KeyKind.COVER_REF, T + ":EUR"),
                        new NewExpectation.ExpectationKey(KeyKind.FX_TRADE_REF, "FT-1:EUR"));
        assertThat(leg(ExpectationKind.FX_BUY_LEG, Money.ofPersisted(108_502, USD, 2)).keys())
                .containsExactly(new NewExpectation.ExpectationKey(KeyKind.COVER_REF, T + ":USD"),
                        new NewExpectation.ExpectationKey(KeyKind.FX_TRADE_REF, "FT-1:USD"));
    }

    @Test
    @DisplayName("an FX provider line's cover references take its currency - the legs and the fee's ORIGINAL_REF;"
            + " a non-FX line and a non-cover key are untouched")
    void theItemSide() {
        assertThat(item(ExternalLineType.FX_BOUGHT, USD, Map.of(ItemKeyKind.COVER_REF, T, ItemKeyKind.FX_TRADE_REF, "FT-1")).keys())
                .isEqualTo(Map.of(ItemKeyKind.COVER_REF, T + ":USD", ItemKeyKind.FX_TRADE_REF, "FT-1:USD"));
        assertThat(item(ExternalLineType.FX_FEE, EUR, Map.of(ItemKeyKind.ORIGINAL_REF, T)).keys())
                .isEqualTo(Map.of(ItemKeyKind.ORIGINAL_REF, T + ":EUR"));
        assertThat(item(ExternalLineType.CAPTURE, EUR, Map.of(ItemKeyKind.PSP_CAPTURE_REF, "cap_1")).keys())
                .isEqualTo(Map.of(ItemKeyKind.PSP_CAPTURE_REF, "cap_1"));
        assertThat(item(ExternalLineType.PROCESSING_FEE, EUR, Map.of(ItemKeyKind.ORIGINAL_REF, "cap_1")).keys())
                .as("a Phase 8 fee's original stays the capture's reference")
                .isEqualTo(Map.of(ItemKeyKind.ORIGINAL_REF, "cap_1"));
    }

    @Test
    @DisplayName("only a cover leg's expectation is qualified: a capture's key is untouched")
    void otherKindsAreUntouched() {
        NewExpectation capture = new NewExpectation(
                ExpectationKind.CARD_CAPTURE, "op", "posting", UUID.randomUUID(), AccountPurpose.SETTLEMENT_CLEARING,
                UUID.randomUUID(), ExpectationDirection.INBOUND, Money.ofPersisted(100, EUR, 2), Optional.of(UUID.randomUUID()),
                LocalDate.now(), Optional.empty(), LocalDate.now(), UUID.randomUUID(),
                List.of(new NewExpectation.ExpectationKey(KeyKind.PSP_CAPTURE_REF, "cap_1")),
                new Actor("system", ActorType.SYSTEM), Instant.now(), CorrelationId.of("c"));
        assertThat(capture.keys()).containsExactly(new NewExpectation.ExpectationKey(KeyKind.PSP_CAPTURE_REF, "cap_1"));
    }

    // -----------------------------------------------------------------

    private static NewExpectation leg(ExpectationKind kind, Money amount) {
        return new NewExpectation(
                kind, "cover", "fx-cover:x", UUID.randomUUID(), AccountPurpose.FX_PROVIDER_CLEARING, UUID.randomUUID(),
                kind == ExpectationKind.FX_SELL_LEG ? ExpectationDirection.OUTBOUND : ExpectationDirection.INBOUND,
                amount, Optional.of(UUID.randomUUID()), LocalDate.now(), Optional.empty(), LocalDate.now(), UUID.randomUUID(),
                List.of(new NewExpectation.ExpectationKey(KeyKind.COVER_REF, T),
                        new NewExpectation.ExpectationKey(KeyKind.FX_TRADE_REF, "FT-1")),
                new Actor("system", ActorType.SYSTEM), Instant.now(), CorrelationId.of("c"));
    }

    private static ExternalItems.NewItem item(ExternalLineType type, CurrencyCode currency, Map<ItemKeyKind, String> keys) {
        return new ExternalItems.NewItem(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, type,
                ExpectationDirection.INBOUND, Money.ofPersisted(100, currency, 2),
                Optional.of(AccountPurpose.FX_PROVIDER_CLEARING), Optional.empty(), LocalDate.now(),
                Optional.empty(), Optional.empty(), new byte[32], keys, Instant.now(), CorrelationId.of("c"));
    }
}
