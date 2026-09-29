package com.finapp.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The pure decision function (`P8-TSK-011`, ADR-0068 §3): every verdict from frozen inputs
 * alone, the definitive precedence as ADR-0069 §2 lists it, and the shuffled-order property
 * — the same hits in any order produce the same verdict, because determinism is what makes
 * the stored snapshot a replayable record ({@code INV-REC-04} as amended).
 */
@DisplayName("the match engine decides purely (P8-TSK-011)")
class MatchEngineTest {

    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final CurrencyCode GBP = CurrencyCode.of("GBP");
    private static final LocalDate BUSINESS = LocalDate.parse("2026-09-25");
    private static final LocalDate EXPECTED_BY = LocalDate.parse("2026-09-28");
    private static final int TOLERANCE = 2;

    // ----------------------------------------------------------------- allocation

    @Test
    @DisplayName("one live candidate at the exact amount allocates whole: no excess, no"
            + " under, no timing inside the window")
    void exactMatchAllocates() {
        MatchEngine.HitFacts hit = hit(100_00, 100_00, ExpectationDirection.INBOUND, EUR);
        MatchEngine.Verdict verdict =
                MatchEngine.decide(
                        item(100_00, EUR, EXPECTED_BY), fired(hit), true, TOLERANCE);

        assertThat(verdict.kind()).isEqualTo(MatchEngine.VerdictKind.ALLOCATE);
        assertThat(verdict.candidate()).contains(hit);
        assertThat(verdict.allocation().orElseThrow().minorUnits()).isEqualTo(100_00);
        assertThat(verdict.excess()).isEmpty();
        assertThat(verdict.underRemainder()).isEmpty();
        assertThat(verdict.timing()).isEmpty();
    }

    @Test
    @DisplayName("an item over the remainder allocates the remainder and names the excess"
            + " - never over-allocation (INV-REC-07)")
    void overAllocatesRemainderAndExcess() {
        MatchEngine.HitFacts hit = hit(60_00, 60_00, ExpectationDirection.INBOUND, EUR);
        MatchEngine.Verdict verdict =
                MatchEngine.decide(
                        item(100_00, EUR, EXPECTED_BY), fired(hit), true, TOLERANCE);

        assertThat(verdict.kind()).isEqualTo(MatchEngine.VerdictKind.ALLOCATE);
        assertThat(verdict.allocation().orElseThrow().minorUnits()).isEqualTo(60_00);
        assertThat(verdict.excess().orElseThrow().minorUnits()).isEqualTo(40_00);
        assertThat(verdict.underRemainder()).isEmpty();
    }

    @Test
    @DisplayName("an item under the remainder allocates itself whole and names the"
            + " expectation's remaining shortfall")
    void underAllocatesWholeAndNamesTheShortfall() {
        MatchEngine.HitFacts hit = hit(100_00, 100_00, ExpectationDirection.INBOUND, EUR);
        MatchEngine.Verdict verdict =
                MatchEngine.decide(
                        item(70_00, EUR, EXPECTED_BY), fired(hit), true, TOLERANCE);

        assertThat(verdict.kind()).isEqualTo(MatchEngine.VerdictKind.ALLOCATE);
        assertThat(verdict.allocation().orElseThrow().minorUnits()).isEqualTo(70_00);
        assertThat(verdict.excess()).isEmpty();
        assertThat(verdict.underRemainder().orElseThrow().minorUnits()).isEqualTo(30_00);
    }

    @Test
    @DisplayName("a partially allocated candidate offers only its remainder")
    void partiallyAllocatedCandidateOffersItsRemainder() {
        MatchEngine.HitFacts hit = hit(100_00, 25_00, ExpectationDirection.INBOUND, EUR);
        MatchEngine.Verdict verdict =
                MatchEngine.decide(
                        item(25_00, EUR, EXPECTED_BY), fired(hit), true, TOLERANCE);

        assertThat(verdict.kind()).isEqualTo(MatchEngine.VerdictKind.ALLOCATE);
        assertThat(verdict.allocation().orElseThrow().minorUnits()).isEqualTo(25_00);
        assertThat(verdict.excess()).isEmpty();
        assertThat(verdict.underRemainder()).isEmpty();
    }

    // ----------------------------------------------------------------- timing

    @Test
    @DisplayName("timing is judged on stored dates: over the window a zero-value"
            + " observation rides the allocation, at the window nothing (INV-SET-03)")
    void timingIsObservedNeverRefused() {
        MatchEngine.HitFacts hit = hit(100_00, 100_00, ExpectationDirection.INBOUND, EUR);

        MatchEngine.Verdict late =
                MatchEngine.decide(
                        item(100_00, EUR, EXPECTED_BY.plusDays(TOLERANCE + 1)),
                        fired(hit),
                        true,
                        TOLERANCE);
        assertThat(late.kind()).isEqualTo(MatchEngine.VerdictKind.ALLOCATE);
        assertThat(late.timing().orElseThrow().deviationDays()).isEqualTo(TOLERANCE + 1);
        assertThat(late.timing().orElseThrow().toleranceDays()).isEqualTo(TOLERANCE);

        MatchEngine.Verdict atTheWindow =
                MatchEngine.decide(
                        item(100_00, EUR, EXPECTED_BY.plusDays(TOLERANCE)),
                        fired(hit),
                        true,
                        TOLERANCE);
        assertThat(atTheWindow.timing()).as("exactly at the tolerance is inside").isEmpty();
    }

    @Test
    @DisplayName("the effective settlement date is the counterparty's, falling back to the"
            + " business day")
    void effectiveSettlementDateFallsBack() {
        MatchEngine.ItemFacts withoutDate =
                new MatchEngine.ItemFacts(
                        UUID.randomUUID(),
                        ExternalLineType.CAPTURE,
                        ExpectationDirection.INBOUND,
                        Money.ofPersisted(100_00, EUR, 2),
                        BUSINESS,
                        Optional.empty(),
                        false);
        assertThat(withoutDate.effectiveSettlementDate()).isEqualTo(BUSINESS);
    }

    // ----------------------------------------------------------------- definitives

    @Test
    @DisplayName("two live candidates are AMBIGUOUS - the engine never guesses")
    void twoCandidatesAreAmbiguous() {
        MatchEngine.Verdict verdict =
                MatchEngine.decide(
                        item(100_00, EUR, EXPECTED_BY),
                        fired(
                                hit(100_00, 100_00, ExpectationDirection.INBOUND, EUR),
                                hit(100_00, 100_00, ExpectationDirection.INBOUND, EUR)),
                        true,
                        TOLERANCE);
        assertThat(verdict.kind()).isEqualTo(MatchEngine.VerdictKind.AMBIGUOUS);
        assertThat(verdict.allocation()).isEmpty();
    }

    @Test
    @DisplayName("hit failures follow ADR-0069 section 2's precedence: direction, then"
            + " currency, then exhausted")
    void definitivePrecedence() {
        MatchEngine.HitFacts wrongDirection =
                hit(100_00, 100_00, ExpectationDirection.OUTBOUND, EUR);
        MatchEngine.HitFacts wrongCurrency =
                hit(100_00, 100_00, ExpectationDirection.INBOUND, GBP);
        MatchEngine.HitFacts exhausted = hit(100_00, 0, ExpectationDirection.INBOUND, EUR);

        assertThat(
                        MatchEngine.decide(
                                        item(100_00, EUR, EXPECTED_BY),
                                        fired(wrongDirection, wrongCurrency, exhausted),
                                        true,
                                        TOLERANCE)
                                .kind())
                .as("direction outranks the rest")
                .isEqualTo(MatchEngine.VerdictKind.DIRECTION_CONTRADICTED);
        assertThat(
                        MatchEngine.decide(
                                        item(100_00, EUR, EXPECTED_BY),
                                        fired(wrongCurrency, exhausted),
                                        true,
                                        TOLERANCE)
                                .kind())
                .as("currency outranks exhausted")
                .isEqualTo(MatchEngine.VerdictKind.CURRENCY_CONTRADICTED);
        assertThat(
                        MatchEngine.decide(
                                        item(100_00, EUR, EXPECTED_BY),
                                        fired(exhausted),
                                        true,
                                        TOLERANCE)
                                .kind())
                .as("an exhausted expectation is the counterparty repeating itself")
                .isEqualTo(MatchEngine.VerdictKind.DUPLICATE);
    }

    @Test
    @DisplayName("a live candidate wins over a failing sibling - the failures only matter"
            + " when nothing is left")
    void oneLiveCandidateBeatsFailingSiblings() {
        MatchEngine.HitFacts live = hit(100_00, 100_00, ExpectationDirection.INBOUND, EUR);
        MatchEngine.HitFacts exhausted = hit(100_00, 0, ExpectationDirection.INBOUND, EUR);
        MatchEngine.Verdict verdict =
                MatchEngine.decide(
                        item(100_00, EUR, EXPECTED_BY),
                        fired(exhausted, live),
                        true,
                        TOLERANCE);
        assertThat(verdict.kind()).isEqualTo(MatchEngine.VerdictKind.ALLOCATE);
        assertThat(verdict.candidate()).contains(live);
    }

    @Test
    @DisplayName("an earlier identical fingerprint is definitive before anything else"
            + " (ADR-0068 section 4)")
    void repeatedFingerprintIsDefinitiveFirst() {
        MatchEngine.ItemFacts repeated =
                new MatchEngine.ItemFacts(
                        UUID.randomUUID(),
                        ExternalLineType.CAPTURE,
                        ExpectationDirection.INBOUND,
                        Money.ofPersisted(100_00, EUR, 2),
                        BUSINESS,
                        Optional.of(EXPECTED_BY),
                        true);
        MatchEngine.Verdict verdict =
                MatchEngine.decide(
                        repeated,
                        fired(hit(100_00, 100_00, ExpectationDirection.INBOUND, EUR)),
                        true,
                        TOLERANCE);
        assertThat(verdict.kind()).isEqualTo(MatchEngine.VerdictKind.DUPLICATE);
    }

    @Test
    @DisplayName("no landed rule is NO_RULE; a landed rule whose keys reached nothing is"
            + " NO_CANDIDATES - the caller's lookup types the second, never the first")
    void noRuleAndNoCandidatesAreDistinct() {
        assertThat(
                        MatchEngine.decide(
                                        item(100_00, EUR, EXPECTED_BY),
                                        Optional.empty(),
                                        false,
                                        TOLERANCE)
                                .kind())
                .isEqualTo(MatchEngine.VerdictKind.NO_RULE);
        assertThat(
                        MatchEngine.decide(
                                        item(100_00, EUR, EXPECTED_BY),
                                        Optional.empty(),
                                        true,
                                        TOLERANCE)
                                .kind())
                .isEqualTo(MatchEngine.VerdictKind.NO_CANDIDATES);
    }

    @Test
    @DisplayName("the rule's expectation kind screens foreign hits before anything counts")
    void expectationKindScreens() {
        MatchEngine.HitFacts foreignKind =
                new MatchEngine.HitFacts(
                        UUID.randomUUID(),
                        ExpectationKind.CARD_REFUND,
                        ExpectationDirection.INBOUND,
                        Money.ofPersisted(100_00, EUR, 2),
                        100_00,
                        Instant.parse("2026-09-25T12:00:00Z"),
                        EXPECTED_BY,
                        KeyKind.PSP_CAPTURE_REF,
                        "op-ref");
        MatchEngine.Verdict verdict =
                MatchEngine.decide(
                        item(100_00, EUR, EXPECTED_BY), fired(foreignKind), true, TOLERANCE);
        // Screened out entirely: no live candidate and no recorded failure of the listed
        // kinds, so the remainder is the lookup's to type.
        assertThat(verdict.kind()).isEqualTo(MatchEngine.VerdictKind.NO_CANDIDATES);
    }

    // ----------------------------------------------------------------- determinism

    @Test
    @DisplayName("the shuffled-order property: the same hits in any order produce the same"
            + " verdict, byte for byte (INV-REC-04 as amended)")
    void shuffledHitsDecideIdentically() {
        MatchEngine.HitFacts live = hit(100_00, 100_00, ExpectationDirection.INBOUND, EUR);
        List<MatchEngine.HitFacts> hits =
                new ArrayList<>(
                        List.of(
                                hit(100_00, 0, ExpectationDirection.INBOUND, EUR),
                                hit(100_00, 100_00, ExpectationDirection.OUTBOUND, EUR),
                                hit(100_00, 100_00, ExpectationDirection.INBOUND, GBP),
                                live));
        MatchEngine.ItemFacts item = item(100_00, EUR, EXPECTED_BY);
        MatchEngine.Verdict first =
                MatchEngine.decide(item, fired(hits), true, TOLERANCE);

        Random random = new Random(2026_09_30L); // Seeded: the property is reproducible.
        for (int round = 0; round < 50; round++) {
            Collections.shuffle(hits, random);
            assertThat(MatchEngine.decide(item, fired(hits), true, TOLERANCE))
                    .as("round %s: order must not matter", round)
                    .isEqualTo(first);
        }
        assertThat(first.kind()).isEqualTo(MatchEngine.VerdictKind.ALLOCATE);
        assertThat(first.candidate()).contains(live);
    }

    // ----------------------------------------------------------------- fixtures

    private static MatchEngine.ItemFacts item(
            long minor, CurrencyCode currency, LocalDate settlementDate) {
        return new MatchEngine.ItemFacts(
                UUID.randomUUID(),
                ExternalLineType.CAPTURE,
                ExpectationDirection.INBOUND,
                Money.ofPersisted(minor, currency, 2),
                BUSINESS,
                Optional.of(settlementDate),
                false);
    }

    private static MatchEngine.HitFacts hit(
            long amountMinor,
            long remainderMinor,
            ExpectationDirection direction,
            CurrencyCode currency) {
        return new MatchEngine.HitFacts(
                UUID.randomUUID(),
                ExpectationKind.CARD_CAPTURE,
                direction,
                Money.ofPersisted(amountMinor, currency, 2),
                remainderMinor,
                Instant.parse("2026-09-25T12:00:00Z"),
                EXPECTED_BY,
                KeyKind.PSP_CAPTURE_REF,
                "op-ref");
    }

    private static Optional<MatchEngine.FiredRule> fired(MatchEngine.HitFacts... hits) {
        return fired(List.of(hits));
    }

    private static Optional<MatchEngine.FiredRule> fired(List<MatchEngine.HitFacts> hits) {
        return Optional.of(
                new MatchEngine.FiredRule(
                        1,
                        KeyKind.PSP_CAPTURE_REF,
                        Optional.of(ExpectationKind.CARD_CAPTURE),
                        Cardinality.ONE_TO_ONE,
                        48,
                        hits));
    }
}
