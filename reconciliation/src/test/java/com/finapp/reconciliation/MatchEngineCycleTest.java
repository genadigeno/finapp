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
 * The cycle comparison inside the pure decision function (`P8-TSK-017`, ADR-0067 §5): the
 * report's cycle (its run's) against the cycle the completion ANNOUNCED — compared only when
 * both exist. A shift is a zero-value timing observation riding the allocation, never a
 * refusal; a return announced no cycle and a card report carries none, so neither is ever a
 * shift; a shift on a late line is still ONE observation. The scheme's rules name no
 * expectation kind, so a pay-in and a parking reachable under one reference are two live
 * candidates — the engine refuses to choose ({@code AMBIGUOUS}), whatever their order.
 */
@DisplayName("the match engine compares the report's cycle with the announced one (P8-TSK-017)")
class MatchEngineCycleTest {

    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final LocalDate EXPECTED_BY = LocalDate.parse("2026-10-02");
    private static final int TOLERANCE = 2;
    private static final String C1 = "SIMSCHEME-2026-10-01-C1";
    private static final String C2 = "SIMSCHEME-2026-10-01-C2";

    // ----------------------------------------------------------------- the comparison

    @Test
    @DisplayName("the same cycle on both sides, inside the date window: an allocation and no"
            + " timing observation")
    void theSameCycleRaisesNoTiming() {
        MatchEngine.HitFacts payIn =
                hit(ExpectationKind.PUSH_PAY_IN, ExpectationDirection.INBOUND, Optional.of(C1));
        MatchEngine.Verdict verdict =
                MatchEngine.decide(
                        credit(EXPECTED_BY, Optional.of(C1)),
                        fired(KeyKind.SCHEME_REF, payIn),
                        true,
                        TOLERANCE);

        assertThat(verdict.kind()).isEqualTo(MatchEngine.VerdictKind.ALLOCATE);
        assertThat(verdict.candidate()).contains(payIn);
        assertThat(verdict.allocation().orElseThrow().minorUnits()).isEqualTo(100_00);
        assertThat(verdict.timing()).isEmpty();
    }

    @Test
    @DisplayName("a shift - the report settled the line in another cycle than the one"
            + " announced - is a timing observation with cycleShift, the date deviation inside"
            + " its window, and the money still allocates whole (INV-SET-03)")
    void aShiftIsObservedNeverRefused() {
        MatchEngine.HitFacts payIn =
                hit(ExpectationKind.PUSH_PAY_IN, ExpectationDirection.INBOUND, Optional.of(C1));
        MatchEngine.Verdict verdict =
                MatchEngine.decide(
                        credit(EXPECTED_BY, Optional.of(C2)),
                        fired(KeyKind.SCHEME_REF, payIn),
                        true,
                        TOLERANCE);

        assertThat(verdict.kind()).isEqualTo(MatchEngine.VerdictKind.ALLOCATE);
        assertThat(verdict.allocation().orElseThrow().minorUnits()).isEqualTo(100_00);
        assertThat(verdict.excess()).isEmpty();
        assertThat(verdict.underRemainder()).isEmpty();
        MatchEngine.Verdict.Timing timing = verdict.timing().orElseThrow();
        assertThat(timing.cycleShift()).isTrue();
        assertThat(timing.deviationDays())
                .as("the dates agree: the shift alone is the observation")
                .isZero();
        assertThat(timing.toleranceDays()).isEqualTo(TOLERANCE);
    }

    @Test
    @DisplayName("a return announced no cycle - it LEARNS one from the report - so its"
            + " allocation is never a shift")
    void aReturnAnnouncedNoCycle() {
        MatchEngine.HitFacts pushReturn =
                hit(ExpectationKind.PUSH_RETURN, ExpectationDirection.OUTBOUND, Optional.empty());
        MatchEngine.Verdict verdict =
                MatchEngine.decide(
                        item(
                                ExternalLineType.DEBIT_OUT, ExpectationDirection.OUTBOUND,
                                EXPECTED_BY, Optional.of(C2)),
                        fired(KeyKind.OUR_REF, pushReturn),
                        true,
                        TOLERANCE);

        assertThat(verdict.kind()).isEqualTo(MatchEngine.VerdictKind.ALLOCATE);
        assertThat(verdict.candidate()).contains(pushReturn);
        assertThat(verdict.timing()).isEmpty();
    }

    @Test
    @DisplayName("a card report's line carries no cycle (the P8-TSK-011 shape): nothing to"
            + " compare, whatever the candidate announced")
    void aLineWithoutACycleIsNeverAShift() {
        MatchEngine.ItemFacts cardShape =
                new MatchEngine.ItemFacts(
                        UUID.randomUUID(),
                        ExternalLineType.CAPTURE,
                        ExpectationDirection.INBOUND,
                        Money.ofPersisted(100_00, EUR, 2),
                        EXPECTED_BY,
                        Optional.of(EXPECTED_BY),
                        false);
        assertThat(cardShape.cycle()).as("the compat shape carries no cycle").isEmpty();

        MatchEngine.Verdict verdict =
                MatchEngine.decide(
                        cardShape,
                        fired(
                                KeyKind.PSP_CAPTURE_REF,
                                hit(
                                        ExpectationKind.CARD_CAPTURE,
                                        ExpectationDirection.INBOUND,
                                        Optional.of(C1))),
                        true,
                        TOLERANCE);

        assertThat(verdict.kind()).isEqualTo(MatchEngine.VerdictKind.ALLOCATE);
        assertThat(verdict.timing()).isEmpty();
    }

    @Test
    @DisplayName("a shift AND a late date are ONE observation - the shift names it - while a"
            + " late date alone stays the plain LATE_MATCH shape")
    void aShiftAndALateDateAreOneObservation() {
        MatchEngine.HitFacts payIn =
                hit(ExpectationKind.PUSH_PAY_IN, ExpectationDirection.INBOUND, Optional.of(C1));
        LocalDate late = EXPECTED_BY.plusDays(TOLERANCE + 1);

        MatchEngine.Verdict shiftedAndLate =
                MatchEngine.decide(
                        credit(late, Optional.of(C2)),
                        fired(KeyKind.SCHEME_REF, payIn),
                        true,
                        TOLERANCE);
        MatchEngine.Verdict.Timing both = shiftedAndLate.timing().orElseThrow();
        assertThat(both.cycleShift()).isTrue();
        assertThat(both.deviationDays()).isEqualTo(TOLERANCE + 1);
        assertThat(both.toleranceDays()).isEqualTo(TOLERANCE);

        MatchEngine.Verdict lateOnly =
                MatchEngine.decide(
                        credit(late, Optional.of(C1)),
                        fired(KeyKind.SCHEME_REF, payIn),
                        true,
                        TOLERANCE);
        MatchEngine.Verdict.Timing dateOnly = lateOnly.timing().orElseThrow();
        assertThat(dateOnly.cycleShift()).isFalse();
        assertThat(dateOnly.deviationDays()).isEqualTo(TOLERANCE + 1);
        assertThat(dateOnly)
                .as("the P8-TSK-011 shape is a date deviation with no shift")
                .isEqualTo(new MatchEngine.Verdict.Timing(TOLERANCE + 1, TOLERANCE));
    }

    @Test
    @DisplayName("the earlier shapes announce no cycle: the compat hit and item constructors,"
            + " and a Timing of dates alone")
    void theEarlierShapesAnnounceNoCycle() {
        MatchEngine.HitFacts bankShape =
                new MatchEngine.HitFacts(
                        UUID.randomUUID(),
                        ExpectationKind.REMITTANCE,
                        ExpectationDirection.INBOUND,
                        Money.ofPersisted(100_00, EUR, 2),
                        100_00,
                        Instant.parse("2026-10-01T12:00:00Z"),
                        EXPECTED_BY,
                        Optional.empty(),
                        "op-bank");
        MatchEngine.HitFacts cardShape =
                new MatchEngine.HitFacts(
                        UUID.randomUUID(),
                        ExpectationKind.CARD_CAPTURE,
                        ExpectationDirection.INBOUND,
                        Money.ofPersisted(100_00, EUR, 2),
                        100_00,
                        Instant.parse("2026-10-01T12:00:00Z"),
                        EXPECTED_BY,
                        KeyKind.PSP_CAPTURE_REF,
                        "op-card");
        assertThat(bankShape.settlementCycle()).isEmpty();
        assertThat(cardShape.settlementCycle()).isEmpty();
        assertThat(new MatchEngine.Verdict.Timing(3, 2).cycleShift()).isFalse();
    }

    // ----------------------------------------------------------------- the engine never guesses

    @Test
    @DisplayName("a pay-in and an unmatched confirmation reachable under ONE scheme reference"
            + " are two live candidates - the scheme's rules name no kind - so the engine"
            + " refuses to choose, in any order")
    void twoReachableKindsAreAmbiguous() {
        MatchEngine.HitFacts payIn =
                hit(ExpectationKind.PUSH_PAY_IN, ExpectationDirection.INBOUND, Optional.of(C1));
        MatchEngine.HitFacts parking =
                hit(
                        ExpectationKind.UNMATCHED_CONFIRMATION,
                        ExpectationDirection.INBOUND,
                        Optional.of(C1));
        List<MatchEngine.HitFacts> hits = new ArrayList<>(List.of(payIn, parking));
        MatchEngine.ItemFacts credit = credit(EXPECTED_BY, Optional.of(C1));

        Random random = new Random(2026_10_17L); // Seeded: the property is reproducible.
        for (int round = 0; round < 10; round++) {
            Collections.shuffle(hits, random);
            MatchEngine.Verdict verdict =
                    MatchEngine.decide(
                            credit,
                            Optional.of(
                                    new MatchEngine.FiredRule(
                                            1,
                                            KeyKind.SCHEME_REF,
                                            Optional.empty(),
                                            Cardinality.ONE_TO_ONE,
                                            48,
                                            hits)),
                            true,
                            TOLERANCE);
            assertThat(verdict.kind())
                    .as("round %s", round)
                    .isEqualTo(MatchEngine.VerdictKind.AMBIGUOUS);
            assertThat(verdict.candidate()).isEmpty();
            assertThat(verdict.allocation()).isEmpty();
            assertThat(verdict.timing()).isEmpty();
        }
    }

    // ----------------------------------------------------------------- fixtures

    private static MatchEngine.ItemFacts credit(LocalDate settlementDate, Optional<String> cycle) {
        return item(
                ExternalLineType.CREDIT_IN, ExpectationDirection.INBOUND, settlementDate, cycle);
    }

    private static MatchEngine.ItemFacts item(
            ExternalLineType type,
            ExpectationDirection direction,
            LocalDate settlementDate,
            Optional<String> cycle) {
        return new MatchEngine.ItemFacts(
                UUID.randomUUID(),
                type,
                direction,
                Money.ofPersisted(100_00, EUR, 2),
                settlementDate,
                Optional.of(settlementDate),
                false,
                cycle);
    }

    private static MatchEngine.HitFacts hit(
            ExpectationKind kind, ExpectationDirection direction, Optional<String> cycle) {
        return new MatchEngine.HitFacts(
                UUID.randomUUID(),
                kind,
                direction,
                Money.ofPersisted(100_00, EUR, 2),
                100_00,
                Instant.parse("2026-10-01T12:00:00Z"),
                EXPECTED_BY,
                Optional.of(KeyKind.SCHEME_REF),
                "op-" + kind.name().toLowerCase(java.util.Locale.ROOT),
                cycle);
    }

    /** The scheme's rules name no expectation kind: the key's own expectation decides. */
    private static Optional<MatchEngine.FiredRule> fired(
            KeyKind keyKind, MatchEngine.HitFacts... hits) {
        return Optional.of(
                new MatchEngine.FiredRule(
                        1, keyKind, Optional.empty(), Cardinality.ONE_TO_ONE, 48,
                        List.of(hits)));
    }
}
