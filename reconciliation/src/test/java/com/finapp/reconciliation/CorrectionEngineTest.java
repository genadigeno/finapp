package com.finapp.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The correction's pure seat (`P8-TSK-012`): a same-direction top-up of the open
 * remainder (min, any excess the caller's to park — C1), an EXACT opposite offset of the
 * parked remainder — never partial — and the unreached fall-through; the top-up
 * precedence when both could apply.
 */
@DisplayName("the correction engine decides purely (P8-TSK-012)")
class CorrectionEngineTest {

    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final CurrencyCode GBP = CurrencyCode.of("GBP");

    @Test
    @DisplayName("a same-direction correction tops up the open remainder: min(item,"
            + " remainder), the excess named")
    void topUpAllocatesMinWithExcess() {
        MatchEngine.HitFacts open = hit(100_00, 60_00, ExpectationDirection.INBOUND, EUR);
        CorrectionEngine.Verdict exact =
                CorrectionEngine.decide(
                        item(60_00, ExpectationDirection.INBOUND, EUR),
                        List.of(open),
                        List.of());
        assertThat(exact.kind()).isEqualTo(CorrectionEngine.Kind.TOP_UP);
        assertThat(exact.allocation().orElseThrow().minorUnits()).isEqualTo(60_00L);
        assertThat(exact.excess()).isEmpty();

        CorrectionEngine.Verdict over =
                CorrectionEngine.decide(
                        item(75_00, ExpectationDirection.INBOUND, EUR),
                        List.of(open),
                        List.of());
        assertThat(over.kind()).isEqualTo(CorrectionEngine.Kind.TOP_UP);
        assertThat(over.allocation().orElseThrow().minorUnits()).isEqualTo(60_00L);
        assertThat(over.excess().orElseThrow().minorUnits()).isEqualTo(15_00L);
    }

    @Test
    @DisplayName("an opposite-direction correction offsets the parked remainder only"
            + " EXACTLY - never a partial offset")
    void offsetIsExactOrNothing() {
        CorrectionEngine.ParkedOriginal parked = parked(SuspenseSide.CREDIT, 40_00, EUR);
        CorrectionEngine.Verdict exact =
                CorrectionEngine.decide(
                        item(40_00, ExpectationDirection.OUTBOUND, EUR),
                        List.of(),
                        List.of(parked));
        assertThat(exact.kind()).isEqualTo(CorrectionEngine.Kind.OFFSET);
        assertThat(exact.offset()).contains(parked);

        assertThat(CorrectionEngine.decide(
                        item(39_99, ExpectationDirection.OUTBOUND, EUR),
                        List.of(),
                        List.of(parked))
                        .kind())
                .as("one minor unit off is no offset")
                .isEqualTo(CorrectionEngine.Kind.UNREACHED);
        assertThat(CorrectionEngine.decide(
                        item(40_00, ExpectationDirection.INBOUND, EUR),
                        List.of(),
                        List.of(parked))
                        .kind())
                .as("an INBOUND correction never offsets a CREDIT parking")
                .isEqualTo(CorrectionEngine.Kind.UNREACHED);
        assertThat(CorrectionEngine.decide(
                        item(40_00, ExpectationDirection.OUTBOUND, GBP),
                        List.of(),
                        List.of(parked))
                        .kind())
                .as("never converted (INV-MON-04)")
                .isEqualTo(CorrectionEngine.Kind.UNREACHED);
    }

    @Test
    @DisplayName("a DEBIT parking (an OUTBOUND original's) is offset by an INBOUND"
            + " correction - the claw-back's mirror")
    void debitSideMirrors() {
        CorrectionEngine.ParkedOriginal parked = parked(SuspenseSide.DEBIT, 25_00, EUR);
        assertThat(CorrectionEngine.decide(
                        item(25_00, ExpectationDirection.INBOUND, EUR),
                        List.of(),
                        List.of(parked))
                        .kind())
                .isEqualTo(CorrectionEngine.Kind.OFFSET);
    }

    @Test
    @DisplayName("when both could apply, the top-up wins: the expectation's remainder is"
            + " the older, primary record")
    void topUpPrecedesOffset() {
        CorrectionEngine.Verdict verdict =
                CorrectionEngine.decide(
                        item(30_00, ExpectationDirection.OUTBOUND, EUR),
                        List.of(hit(80_00, 30_00, ExpectationDirection.OUTBOUND, EUR)),
                        List.of(parked(SuspenseSide.CREDIT, 30_00, EUR)));
        assertThat(verdict.kind()).isEqualTo(CorrectionEngine.Kind.TOP_UP);
    }

    @Test
    @DisplayName("a correction whose original is not yet reported is unreached - it"
            + " waits like any grace-class remainder")
    void unreachedWaits() {
        assertThat(CorrectionEngine.decide(
                        item(10_00, ExpectationDirection.INBOUND, EUR),
                        List.of(),
                        List.of())
                        .kind())
                .isEqualTo(CorrectionEngine.Kind.UNREACHED);
        assertThat(CorrectionEngine.decide(
                        item(10_00, ExpectationDirection.INBOUND, EUR),
                        List.of(hit(50_00, 0, ExpectationDirection.INBOUND, EUR)),
                        List.of())
                        .kind())
                .as("an exhausted remainder tops up nothing")
                .isEqualTo(CorrectionEngine.Kind.UNREACHED);
    }

    // ----------------------------------------------------------------- fixtures

    private static MatchEngine.ItemFacts item(
            long minor, ExpectationDirection direction, CurrencyCode currency) {
        return new MatchEngine.ItemFacts(
                UUID.randomUUID(),
                ExternalLineType.COUNTERPARTY_ADJUSTMENT,
                direction,
                Money.ofPersisted(minor, currency, 2),
                LocalDate.parse("2026-09-25"),
                Optional.of(LocalDate.parse("2026-09-25")),
                false);
    }

    private static MatchEngine.HitFacts hit(
            long amountMinor, long remainderMinor, ExpectationDirection direction,
            CurrencyCode currency) {
        return new MatchEngine.HitFacts(
                UUID.randomUUID(),
                ExpectationKind.CARD_CAPTURE,
                direction,
                Money.ofPersisted(amountMinor, currency, 2),
                remainderMinor,
                Instant.parse("2026-09-25T12:00:00Z"),
                LocalDate.parse("2026-09-28"),
                KeyKind.PSP_CAPTURE_REF,
                "op-ref");
    }

    private static CorrectionEngine.ParkedOriginal parked(
            SuspenseSide side, long remainderMinor, CurrencyCode currency) {
        return new CorrectionEngine.ParkedOriginal(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                side,
                remainderMinor,
                currency,
                2,
                UUID.randomUUID());
    }
}
